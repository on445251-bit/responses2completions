package io.r2c.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Settings screen.
 *
 * <p>Everything the proxy reads is editable here, with the port front and centre
 * since that is what most users change. Saving while the proxy runs restarts it so
 * a new port or upstream takes effect immediately instead of on the next launch.
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText portField;
    private EditText baseUrlField;
    private EditText apiKeyField;
    private EditText modelField;
    private EditText userAgentField;
    private EditText timeoutField;
    private EditText accessKeysField;
    private EditText aliasesField;
    private EditText headersField;

    private CheckBox bindLanBox;
    private CheckBox autoStartBox;
    private CheckBox bootBox;
    private CheckBox overlayBox;
    private CheckBox passThroughBox;
    private CheckBox corsBox;

    private ProxyService service;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ProxyService.LocalBinder) binder).service();
            bound = true;
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
        setContentView(R.layout.activity_settings);

        portField = findViewById(R.id.port_field);
        baseUrlField = findViewById(R.id.base_url_field);
        apiKeyField = findViewById(R.id.api_key_field);
        modelField = findViewById(R.id.model_field);
        userAgentField = findViewById(R.id.ua_field);
        timeoutField = findViewById(R.id.timeout_field);
        accessKeysField = findViewById(R.id.access_keys_field);
        aliasesField = findViewById(R.id.aliases_field);
        headersField = findViewById(R.id.headers_field);

        bindLanBox = findViewById(R.id.bind_lan_box);
        autoStartBox = findViewById(R.id.auto_start_box);
        bootBox = findViewById(R.id.boot_box);
        overlayBox = findViewById(R.id.overlay_box);
        passThroughBox = findViewById(R.id.pass_through_box);
        corsBox = findViewById(R.id.cors_box);

        timeoutField.setInputType(InputType.TYPE_CLASS_NUMBER);

        loadIntoFields();

        findViewById(R.id.save_button).setOnClickListener(v -> save());
        findViewById(R.id.cancel_button).setOnClickListener(v -> finish());
        findViewById(R.id.reset_button).setOnClickListener(v -> resetDefaults());

        Intent i = new Intent(this, ProxyService.class);
        bindService(i, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }

    // -------------------------------------------------------------------- fields

    private void loadIntoFields() {
        AppSettings s = AppSettings.load(this);
        portField.setText(String.valueOf(s.port));
        baseUrlField.setText(s.baseUrl);
        apiKeyField.setText(s.apiKey);
        modelField.setText(s.defaultModel);
        userAgentField.setText(s.userAgent);
        timeoutField.setText(String.valueOf(s.timeoutSeconds));
        accessKeysField.setText(s.accessKeys);
        aliasesField.setText(s.modelAliases);
        headersField.setText(s.extraHeaders);

        bindLanBox.setChecked(s.bindLan);
        autoStartBox.setChecked(s.autoStart);
        bootBox.setChecked(s.startOnBoot);
        overlayBox.setChecked(s.overlay);
        passThroughBox.setChecked(s.passThrough);
        corsBox.setChecked(s.cors);
    }

    private void resetDefaults() {
        AppSettings d = AppSettings.load(this);
        d.port = AppSettings.DEFAULT_PORT;
        d.baseUrl = AppSettings.DEFAULT_BASE_URL;
        d.defaultModel = AppSettings.DEFAULT_MODEL;
        d.userAgent = AppSettings.DEFAULT_USER_AGENT;
        d.timeoutSeconds = AppSettings.DEFAULT_TIMEOUT;
        d.cors = true;
        d.bindLan = true;
        d.save(this);
        loadIntoFields();
        toast(getString(R.string.saved));
    }

    private void save() {
        AppSettings s = AppSettings.load(this);

        Integer port = parseInt(portField.getText().toString());
        if (port == null || !AppSettings.isValidPort(port)) {
            toast(getString(R.string.error_bad_port));
            portField.requestFocus();
            return;
        }
        Integer timeout = parseInt(timeoutField.getText().toString());
        if (timeout == null || timeout < 5) {
            // A too-small timeout cuts off long model answers; clamp with a message.
            toast(getString(R.string.error_bad_timeout));
            timeoutField.requestFocus();
            return;
        }

        String baseUrl = baseUrlField.getText().toString().trim();
        if (baseUrl.isEmpty()) {
            toast(getString(R.string.error_bad_url));
            baseUrlField.requestFocus();
            return;
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "https://" + baseUrl;
        }

        s.port = port;
        s.baseUrl = baseUrl;
        s.apiKey = apiKeyField.getText().toString().trim();
        s.defaultModel = emptyTo(modelField.getText().toString(), AppSettings.DEFAULT_MODEL);
        s.userAgent = emptyTo(userAgentField.getText().toString(), AppSettings.DEFAULT_USER_AGENT);
        s.timeoutSeconds = timeout;
        s.accessKeys = accessKeysField.getText().toString().trim();
        s.modelAliases = aliasesField.getText().toString().trim();
        s.extraHeaders = headersField.getText().toString().trim();

        s.bindLan = bindLanBox.isChecked();
        s.autoStart = autoStartBox.isChecked();
        s.startOnBoot = bootBox.isChecked();
        s.overlay = overlayBox.isChecked();
        s.passThrough = passThroughBox.isChecked();
        s.cors = corsBox.isChecked();

        // Prefer restarting through the service so the new port is live right away.
        if (service != null) {
            service.applySettings(s);
        } else {
            s.save(this);
        }

        toast(getString(R.string.saved));
        finish();
    }

    private static String emptyTo(String value, String fallback) {
        String v = value == null ? "" : value.trim();
        return v.isEmpty() ? fallback : v;
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}