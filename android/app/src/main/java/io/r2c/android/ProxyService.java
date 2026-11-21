package io.r2c.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service that owns the proxy for the whole app lifetime.
 *
 * <p>Keeping the server in a foreground service (rather than in an Activity) is what
 * makes the port stay open when the UI is backgrounded: Android will not quietly
 * kill a service that shows an ongoing notification, and the wake lock keeps the
 * CPU available while a stream is mid-flight.
 *
 * <p>The service is also the single source of truth for status, which the Activity
 * and the floating window read through {@link #snapshot()}.
 */
public class ProxyService extends Service {

    private static final String TAG = "R2C";

    public static final String ACTION_START = "io.r2c.android.START";
    public static final String ACTION_STOP = "io.r2c.android.STOP";
    public static final String ACTION_RESTART = "io.r2c.android.RESTART";

    private static final String CHANNEL_ID = "r2c_proxy";
    private static final int NOTIFICATION_ID = 0x52C0;

    /** Immutable status snapshot handed to UI layers. */
    public static final class Snapshot {
        public final boolean running;
        public final int port;
        public final boolean bindLan;
        public final String upstreamHost;
        public final String model;
        public final long requests;
        public final long tokens;
        public final String lastError;

        Snapshot(boolean running, int port, boolean bindLan, String upstreamHost,
                 String model, long requests, long tokens, String lastError) {
            this.running = running;
            this.port = port;
            this.bindLan = bindLan;
            this.upstreamHost = upstreamHost;
            this.model = model;
            this.requests = requests;
            this.tokens = tokens;
            this.lastError = lastError;
        }
    }

    /** Observers (Activity, overlay) get called on the main thread. */
    public interface Listener {
        void onSnapshot(Snapshot snapshot);
    }

    /** Local binder so the Activity can talk to a running service. */
    public final class LocalBinder extends Binder {
        public ProxyService service() {
            return ProxyService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final List<Listener> listeners = new ArrayList<>();

    private volatile io.r2c.ProxyServer server;
    private volatile Snapshot snapshot = new Snapshot(false, 0, true, "", "", 0, 0, null);
    private PowerManager.WakeLock wakeLock;
    private long totalTokens;
    private final Object lock = new Object();

    /** Periodic self-check: restarts the listener if it stopped accepting. */
    private final android.os.Handler watchdogHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            io.r2c.ProxyServer current = server;
            if (current != null) {
                if (!current.isAccepting()) {
                    // The listener died while the process lived on. This is invisible
                    // to the user otherwise: the notification keeps saying "running"
                    // while every request times out.
                    appendLogFile("WARN listener not accepting on port " + current.boundPort()
                            + ", restarting");
                    try {
                        current.restart();
                        appendLogFile("INFO listener restarted");
                    } catch (Throwable e) {
                        appendLogFile("ERROR restart failed: " + e);
                        updateNotification("端口异常", "正在重试…");
                    }
                }
                watchdogHandler.postDelayed(this, 10_000);
            }
        }
    };

    public static void start(Context context) {
        Intent i = new Intent(context, ProxyService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, ProxyService.class).setAction(ACTION_STOP));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        // Must post the notification immediately or the OS kills us for starting a
        // foreground service without one.
        startForeground(NOTIFICATION_ID, buildNotification("已停止", "点击开始"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopProxy();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            stopProxy();
            startProxy();
            return START_STICKY;
        }
        // START (and any implicit restart) starts the proxy if it is not up yet.
        if (server == null) {
            startProxy();
        } else {
            publish();
        }
        return START_STICKY;
    }

    // ----------------------------------------------------------------- lifecycle

    private void startProxy() {
        AppSettings settings = AppSettings.load(this);
        io.r2c.Config config = settings.toConfig();

        // Mirror the core logger into logcat and a small file. The file matters on
        // devices where logcat is filtered: without it a failed bind leaves no
        // trace of why.
        io.r2c.util.Log.setSink(line -> {
            Log.d(TAG, line);
            appendLogFile(line);
        });
        io.r2c.util.Log.init(io.r2c.util.Log.Level.INFO);
        config.logSummary();

        io.r2c.ProxyServer created = new io.r2c.ProxyServer(config);
        created.setStats((model, stream, ok, tokens) -> {
            synchronized (lock) {
                if (tokens > 0) totalTokens += tokens;
            }
            publish();
        });

        try {
            created.start();
        } catch (Throwable e) {
            Log.e(TAG, "cannot start proxy", e);
            appendLogFile("ERROR cannot start proxy: " + e);
            updateNotification("启动失败", "端口 " + settings.port + " 不可用");
            publishError(e.getMessage() == null ? e.toString() : e.getMessage());
            return;
        }

        server = created;
        acquireWakeLock();
        // Self-heal: if the listener stops accepting, restart it instead of leaving
        // the user with a "running" badge and dead port.
        watchdogHandler.removeCallbacks(watchdog);
        watchdogHandler.postDelayed(watchdog, 10_000);
        updateNotification("运行中 · 端口 " + created.boundPort(),
                describeEndpoint(settings, created.boundPort()));
        publish();
    }

    /** Appends one line to files/proxy.log, keeping the file small. */
    private void appendLogFile(String line) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "proxy.log");
            if (f.length() > 256 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(line);
                w.write('\n');
            }
        } catch (Exception ignored) {
            // Logging must never break the service.
        }
    }

    private void stopProxy() {
        watchdogHandler.removeCallbacks(watchdog);
        io.r2c.ProxyServer current = server;
        server = null;
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "error stopping proxy", e);
            }
        }
        releaseWakeLock();
        updateNotification("已停止", "点击开始");
        publish();
    }

    @Override
    public void onDestroy() {
        stopProxy();
        io.r2c.util.Log.setSink(null);
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiping the app away should not close the port: the point of the service is
        // to keep it available. The user stops it explicitly from the UI or the
        // floating window.
        super.onTaskRemoved(rootIntent);
    }

    // ------------------------------------------------------------------- status

    public Snapshot snapshot() {
        return snapshot;
    }

    public void addListener(Listener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
        l.onSnapshot(snapshot);
    }

    public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    private void publish() {
        io.r2c.ProxyServer current = server;
        AppSettings settings = AppSettings.load(this);
        boolean running = current != null && current.isRunning();
        long tokens;
        synchronized (lock) {
            tokens = totalTokens;
        }
        snapshot = new Snapshot(running,
                running ? current.boundPort() : settings.port,
                settings.bindLan,
                settings.toConfig().upstreamHost(),
                settings.defaultModel,
                running ? current.servedRequests() : 0,
                tokens,
                null);

        List<Listener> copy;
        synchronized (listeners) {
            copy = new ArrayList<>(listeners);
        }
        final Snapshot s = snapshot;
        // Listeners touch views, so marshal to the main thread.
        new android.os.Handler(getMainLooper()).post(() -> {
            for (Listener l : copy) {
                try {
                    l.onSnapshot(s);
                } catch (RuntimeException e) {
                    Log.w(TAG, "listener failed", e);
                }
            }
        });
    }

    private void publishError(String message) {
        AppSettings settings = AppSettings.load(this);
        snapshot = new Snapshot(false, settings.port, settings.bindLan,
                settings.toConfig().upstreamHost(), settings.defaultModel, 0, totalTokens, message);
        List<Listener> copy;
        synchronized (listeners) {
            copy = new ArrayList<>(listeners);
        }
        final Snapshot s = snapshot;
        new android.os.Handler(getMainLooper()).post(() -> {
            for (Listener l : copy) {
                l.onSnapshot(s);
            }
        });
    }

    /** Applies new settings by restarting the server (bind address/port may change). */
    public void applySettings(AppSettings settings) {
        settings.save(this);
        boolean wasRunning = server != null;
        stopProxy();
        if (wasRunning) {
            startProxy();
        } else {
            publish();
        }
    }

    public void setRunning(boolean run) {
        if (run && server == null) {
            startProxy();
        } else if (!run && server != null) {
            stopProxy();
        }
    }

    private static String describeEndpoint(AppSettings s, int port) {
        if (s.bindLan) {
            String ip = NetUtils.localIpv4();
            return ip == null
                    ? "http://127.0.0.1:" + port + "/v1"
                    : "http://" + ip + ":" + port + "/v1";
        }
        return "http://127.0.0.1:" + port + "/v1";
    }

    // -------------------------------------------------------------- wakelock

    private void acquireWakeLock() {
        if (wakeLock != null) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "r2c:proxy");
        wakeLock.setReferenceCounted(false);
        try {
            wakeLock.acquire();
        } catch (RuntimeException e) {
            Log.w(TAG, "wake lock unavailable", e);
            wakeLock = null;
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) wakeLock.release();
        } catch (RuntimeException ignored) {
            // already released
        }
        wakeLock = null;
    }

    // ------------------------------------------------------------ notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_desc));
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 1, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle(getString(R.string.app_name) + " · " + title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(contentIntent);

        Intent toggle = new Intent(this, ProxyService.class)
                .setAction(ACTION_STOP);
        PendingIntent toggleIntent = PendingIntent.getService(this, 2, toggle, flags);
        b.addAction(new Notification.Action.Builder(
                null, getString(R.string.notification_stop), toggleIntent).build());

        return b.build();
    }

    private void updateNotification(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(title, text));
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot update notification", e);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }
}