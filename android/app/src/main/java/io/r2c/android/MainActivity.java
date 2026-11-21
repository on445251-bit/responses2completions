package io.r2c.android;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Main screen: start/stop the proxy, see the endpoint to configure in a client, and
 * jump to settings.
 *
 * <p>The Activity is a thin view over {@link ProxyService}: it binds to the service,
 * mirrors its snapshot, and never owns the server itself. That way rotating,
 * backgrounding or closing the UI cannot disturb the running port.
 */
public class MainActivity extends AppCompatActivity implements ProxyService.Listener {

    private static final int REQ_NOTIFICATIONS = 1001;

    private ProxyService service;
    private boolean bound;

    private FloatingWindow floating;

    private View statusDot;
    private TextView statusText;
    private TextView statusDetail;
    private TextView endpointText;
    private TextView statsText;
    private Button toggleButton;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ProxyService.LocalBinder) binder).service();
            bound = true;
            service.addListener(MainActivity.this);
            syncOverlay();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusDot = findViewById(R.id.status_dot);
        statusText = findViewById(R.id.status_text);
        statusDetail = findViewById(R.id.status_detail);
        endpointText = findViewById(R.id.endpoint_text);
        statsText = findViewById(R.id.stats_text);
        toggleButton = findViewById(R.id.toggle_button);

        toggleButton.setOnClickListener(v -> {
            AppSettings settings = AppSettings.load(this);
            if (!AppSettings.isValidPort(settings.port)) {
                toast(getString(R.string.error_bad_port));
                return;
            }
            boolean running = service != null && service.snapshot().running;
            if (running) {
                ProxyService.stop(this);
                if (floating != null) floating.hide();
            } else {
                ensureServiceStarted();
            }
        });

        findViewById(R.id.settings_button).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        findViewById(R.id.copy_button).setOnClickListener(v -> {
            String text = endpointText.getText().toString();
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("endpoint", text));
                toast(getString(R.string.copied));
            }
        });

        findViewById(R.id.overlay_button).setOnClickListener(v -> toggleOverlay());
        findViewById(R.id.battery_button).setOnClickListener(v -> requestBatteryExemption());

        requestNotificationPermission();
        applyIntentOverrides(getIntent());
        ensureServiceStarted();
    }

    /**
     * Debug/config entry point: lets the settings be written from the outside
     * without touching the UI, e.g.
     * {@code am start -n io.r2c.android/.MainActivity --es r2c_base_url ...}.
     *
     * <p>SharedPreferences cannot be seeded by writing the XML from a shell: the
     * app process keeps its own in-memory copy and overwrites the file on the next
     * save, and the loader ignores files it did not write itself. Going through the
     * app guarantees the values are actually adopted.
     */
    private void applyIntentOverrides(Intent intent) {
        if (intent == null) return;
        boolean touched = false;
        AppSettings s = AppSettings.load(this);

        String baseUrl = intent.getStringExtra("r2c_base_url");
        if (baseUrl != null) {
            s.baseUrl = baseUrl.trim();
            touched = true;
        }
        String apiKey = intent.getStringExtra("r2c_api_key");
        if (apiKey != null) {
            s.apiKey = apiKey.trim();
            touched = true;
        }
        String model = intent.getStringExtra("r2c_model");
        if (model != null) {
            s.defaultModel = model.trim();
            touched = true;
        }
        String ua = intent.getStringExtra("r2c_user_agent");
        if (ua != null) {
            s.userAgent = ua.trim();
            touched = true;
        }
        int port = intent.getIntExtra("r2c_port", -1);
        if (AppSettings.isValidPort(port)) {
            s.port = port;
            touched = true;
        }
        int timeout = intent.getIntExtra("r2c_timeout", -1);
        if (timeout > 0) {
            s.timeoutSeconds = timeout;
            touched = true;
        }

        if (touched) {
            if (service != null) {
                service.applySettings(s);
            } else {
                s.save(this);
            }
            toast("配置已写入");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent i = new Intent(this, ProxyService.class);
        bindService(i, connection, Context.BIND_AUTO_CREATE);
        render(AppSettings.load(this), service == null ? null : service.snapshot());
    }

    @Override
    protected void onStop() {
        if (bound) {
            if (service != null) service.removeListener(this);
            unbindService(connection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncOverlay();
        render(AppSettings.load(this), service == null ? null : service.snapshot());
    }

    // ------------------------------------------------------------------ actions

    private void ensureServiceStarted() {
        AppSettings settings = AppSettings.load(this);
        ProxyService.start(this);
        if (settings.overlay) {
            // Overlay needs the permission; ask once, then show it when granted.
            if (FloatingWindow.canDraw(this)) {
                showOverlay();
            }
        }
    }

    private void toggleOverlay() {
        if (FloatingWindow.canDraw(this)) {
            AppSettings settings = AppSettings.load(this);
            settings.overlay = !settings.overlay;
            settings.save(this);
            syncOverlay();
        } else {
            requestOverlayPermission();
        }
    }

    private void syncOverlay() {
        AppSettings settings = AppSettings.load(this);
        boolean wanted = settings.overlay && FloatingWindow.canDraw(this);
        if (wanted) {
            showOverlay();
        } else if (floating != null) {
            floating.hide();
        }
        TextView button = findViewById(R.id.overlay_button);
        button.setText(settings.overlay ? R.string.overlay_on : R.string.overlay_off);
    }

    private void showOverlay() {
        if (floating == null) {
            floating = new FloatingWindow(this, new FloatingWindow.Actions() {
                @Override
                public void onToggle() {
                    Boolean running = service == null ? null : service.snapshot().running;
                    if (running != null && running) {
                        ProxyService.stop(MainActivity.this);
                        if (floating != null) floating.hide();
                    } else {
                        ensureServiceStarted();
                    }
                }

                @Override
                public void onOpen() {
                    startActivity(new Intent(MainActivity.this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                }

                @Override
                public void onClose() {
                    if (floating != null) floating.hide();
                }
            });
        }
        floating.show();
        if (service != null) floating.render(service.snapshot());
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            toast(getString(R.string.error_no_overlay_settings));
        }
    }

    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast(getString(R.string.battery_not_needed));
            return;
        }
        android.os.PowerManager pm =
                (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast(getString(R.string.battery_already));
            return;
        }
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            // Some ROMs do not expose the dialog; send the user to the list instead.
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException ignored) {
                toast(getString(R.string.error_no_battery_settings));
            }
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            // Without this the foreground service notification is hidden, which makes
            // the OS far more likely to kill the process.
            toast(getString(R.string.error_no_notification));
        }
    }

    // -------------------------------------------------------------------- render

    @Override
    public void onSnapshot(ProxyService.Snapshot snapshot) {
        render(AppSettings.load(this), snapshot);
        if (floating != null) floating.render(snapshot);
    }

    private void render(AppSettings settings, ProxyService.Snapshot snapshot) {
        boolean running = snapshot != null && snapshot.running;
        int port = running ? snapshot.port : settings.port;

        statusText.setText(running ? R.string.status_running : R.string.status_stopped);
        statusText.setTextColor(running ? Color.parseColor("#22C55E") : Color.parseColor("#EF4444"));
        statusDot.setBackgroundResource(running ? R.drawable.dot_on : R.drawable.dot_off);

        if (snapshot != null && snapshot.lastError != null) {
            statusDetail.setText(snapshot.lastError);
        } else if (settings.apiKey.isEmpty()) {
            statusDetail.setText(R.string.hint_need_key);
        } else {
            statusDetail.setText(getString(R.string.status_upstream, settings.toConfig().upstreamHost()));
        }

        String host = settings.bindLan ? NetUtils.localIpv4() : null;
        String endpoint = "http://" + (host == null ? "127.0.0.1" : host) + ":" + port + "/v1";
        endpointText.setText(endpoint);

        if (snapshot != null && snapshot.requests > 0) {
            statsText.setText(getString(R.string.stats_format,
                    snapshot.requests, snapshot.tokens));
        } else {
            statsText.setText(R.string.stats_empty);
        }

        toggleButton.setText(running ? R.string.action_stop : R.string.action_start);
        toggleButton.setBackgroundResource(running ? R.drawable.btn_danger : R.drawable.btn_primary);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}