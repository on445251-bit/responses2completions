package io.r2c.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted user settings, mapped onto {@link io.r2c.Config}.
 *
 * <p>Kept deliberately flat: the numbers and strings the UI exposes are exactly the
 * ones the proxy reads, so there is no separate translation layer to drift.
 */
public final class AppSettings {

    private static final String PREFS = "r2c_settings";

    // Network
    private static final String KEY_PORT = "port";
    private static final String KEY_BIND_LAN = "bind_lan";
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_START_ON_BOOT = "start_on_boot";
    private static final String KEY_OVERLAY = "overlay";

    // Upstream
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_PASS_THROUGH = "pass_through";
    private static final String KEY_MODEL = "default_model";
    private static final String KEY_USER_AGENT = "user_agent";
    private static final String KEY_TIMEOUT = "timeout";

    // Advanced
    private static final String KEY_ACCESS_KEYS = "access_keys";
    private static final String KEY_ALIASES = "model_aliases";
    private static final String KEY_EXTRA_HEADERS = "extra_headers";
    private static final String KEY_CORS = "cors";

    public static final int DEFAULT_PORT = 8787;
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";
    public static final String DEFAULT_USER_AGENT = "codex_cli_rs/0.42.0";
    public static final int DEFAULT_TIMEOUT = 600;

    public int port = DEFAULT_PORT;
    public boolean bindLan = true;
    public boolean autoStart = false;
    public boolean startOnBoot = false;
    public boolean overlay = true;

    public String baseUrl = DEFAULT_BASE_URL;
    public String apiKey = "";
    public boolean passThrough = false;
    public String defaultModel = DEFAULT_MODEL;
    public String userAgent = DEFAULT_USER_AGENT;
    public int timeoutSeconds = DEFAULT_TIMEOUT;

    public String accessKeys = "";
    public String modelAliases = "";
    public String extraHeaders = "";
    public boolean cors = true;

    private AppSettings() {}

    // ------------------------------------------------------------------ loading

    public static AppSettings load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AppSettings s = new AppSettings();
        s.port = p.getInt(KEY_PORT, DEFAULT_PORT);
        s.bindLan = p.getBoolean(KEY_BIND_LAN, true);
        s.autoStart = p.getBoolean(KEY_AUTO_START, false);
        s.startOnBoot = p.getBoolean(KEY_START_ON_BOOT, false);
        s.overlay = p.getBoolean(KEY_OVERLAY, true);
        s.baseUrl = p.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
        s.apiKey = p.getString(KEY_API_KEY, "");
        s.passThrough = p.getBoolean(KEY_PASS_THROUGH, false);
        s.defaultModel = p.getString(KEY_MODEL, DEFAULT_MODEL);
        s.userAgent = p.getString(KEY_USER_AGENT, DEFAULT_USER_AGENT);
        s.timeoutSeconds = p.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT);
        s.accessKeys = p.getString(KEY_ACCESS_KEYS, "");
        s.modelAliases = p.getString(KEY_ALIASES, "");
        s.extraHeaders = p.getString(KEY_EXTRA_HEADERS, "");
        s.cors = p.getBoolean(KEY_CORS, true);
        return s;
    }

    /** Validates the port range so the UI can reject bad input before saving. */
    public static boolean isValidPort(int p) {
        return p > 0 && p < 65536;
    }

    public void save(Context context) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putInt(KEY_PORT, port);
        e.putBoolean(KEY_BIND_LAN, bindLan);
        e.putBoolean(KEY_AUTO_START, autoStart);
        e.putBoolean(KEY_START_ON_BOOT, startOnBoot);
        e.putBoolean(KEY_OVERLAY, overlay);
        e.putString(KEY_BASE_URL, baseUrl == null ? "" : baseUrl.trim());
        e.putString(KEY_API_KEY, apiKey == null ? "" : apiKey.trim());
        e.putBoolean(KEY_PASS_THROUGH, passThrough);
        e.putString(KEY_MODEL, defaultModel);
        e.putString(KEY_USER_AGENT, userAgent);
        e.putInt(KEY_TIMEOUT, timeoutSeconds);
        e.putString(KEY_ACCESS_KEYS, accessKeys);
        e.putString(KEY_ALIASES, modelAliases);
        e.putString(KEY_EXTRA_HEADERS, extraHeaders);
        e.putBoolean(KEY_CORS, cors);
        e.apply();
    }

    // ------------------------------------------------------- config conversion

    /** Builds the immutable proxy configuration this settings object describes. */
    public io.r2c.Config toConfig() {
        List<String> keys = new ArrayList<>();
        if (accessKeys != null) {
            for (String part : accessKeys.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) keys.add(t);
            }
        }
        return io.r2c.Config.builder()
                .port(port)
                .bindLan(bindLan)
                .upstreamBaseUrl(baseUrl)
                .upstreamApiKey(apiKey)
                .passThroughClientAuth(passThrough)
                .proxyApiKeys(keys)
                .upstreamTimeoutSeconds(timeoutSeconds)
                .defaultModel(defaultModel)
                .upstreamUserAgent(userAgent)
                .modelAliases(parsePairs(modelAliases))
                .extraUpstreamHeaders(parseHeaders(extraHeaders))
                .cors(cors)
                .build();
    }

    private static Map<String, String> parsePairs(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String line : raw.split("[,\\n]")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq <= 0 || eq == t.length() - 1) continue;
            out.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
        }
        return out;
    }

    private static Map<String, String> parseHeaders(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String line : raw.split("[;\\n]")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq <= 0 || eq == t.length() - 1) continue;
            out.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
        }
        return out;
    }

    /** Compact description of where clients should connect. */
    public String localBaseUrl() {
        String host = bindLan ? "127.0.0.1" : "127.0.0.1";
        return "http://" + host + ":" + port + "/v1";
    }
}