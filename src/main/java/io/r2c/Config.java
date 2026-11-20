package io.r2c;

import io.r2c.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime configuration, sourced from environment variables so the proxy stays
 * container friendly. Every option has a sane default so {@code java -jar} with
 * no env still boots.
 */
public final class Config {

    /** TCP port to bind. */
    public final int port;
    /** Bind address; 0.0.0.0 exposes the proxy on all interfaces. */
    public final String bindAddress;
    /** Base URL of the upstream that speaks the Responses API, e.g. https://api.openai.com/v1 */
    public final String upstreamBaseUrl;
    /** API key used for upstream calls when the client does not supply one. */
    public final String upstreamApiKey;
    /** When true, a client-supplied Authorization header wins over {@link #upstreamApiKey}. */
    public final boolean passThroughClientAuth;
    /** Keys accepted from clients. Empty means "no client auth required". */
    public final List<String> proxyApiKeys;
    /** Read timeout for the upstream connection, in seconds. */
    public final int upstreamTimeoutSeconds;
    /** Connect timeout for the upstream connection, in seconds. */
    public final int upstreamConnectTimeoutSeconds;
    /** Per-model alias map applied before the upstream call. */
    public final Map<String, String> modelAliases;
    /** Model reported back when the upstream does not tell us. */
    public final String defaultModel;
    /** Forward the chat-only {@code stop} parameter upstream (dropped by default). */
    public final boolean passStop;
    /** Emit {@code stream_options.include_usage} style usage chunk whenever usage is known. */
    public final boolean alwaysIncludeUsage;
    /** Extra headers added to every upstream request, as name=value pairs. */
    public final Map<String, String> extraUpstreamHeaders;
    /** Enable permissive CORS so browser based tools can call the proxy. */
    public final boolean cors;

    private Config(Map<String, String> env) {
        this.port = intOf(env, "R2C_PORT", intOf(env, "PORT", 8080));
        this.bindAddress = strOf(env, "R2C_BIND", "0.0.0.0");
        this.upstreamBaseUrl = normalizeBase(strOf(env, "R2C_UPSTREAM_BASE_URL",
                strOf(env, "UPSTREAM_BASE_URL", "https://api.openai.com/v1")));
        this.upstreamApiKey = strOf(env, "R2C_UPSTREAM_API_KEY",
                strOf(env, "UPSTREAM_API_KEY", strOf(env, "OPENAI_API_KEY", "")));
        this.passThroughClientAuth = boolOf(env, "R2C_PASS_THROUGH_CLIENT_AUTH", true);
        this.proxyApiKeys = splitList(strOf(env, "R2C_API_KEYS", ""));
        this.upstreamTimeoutSeconds = intOf(env, "R2C_UPSTREAM_TIMEOUT_SECONDS", 600);
        this.upstreamConnectTimeoutSeconds = intOf(env, "R2C_UPSTREAM_CONNECT_TIMEOUT_SECONDS", 20);
        this.modelAliases = parseModelAliases(strOf(env, "R2C_MODEL_ALIASES", ""));
        this.defaultModel = strOf(env, "R2C_DEFAULT_MODEL", "gpt-4o-mini");
        this.passStop = boolOf(env, "R2C_PASS_STOP", false);
        this.alwaysIncludeUsage = boolOf(env, "R2C_ALWAYS_INCLUDE_USAGE", true);
        this.extraUpstreamHeaders = parseHeaderList(strOf(env, "R2C_UPSTREAM_HEADERS", ""));
        this.cors = boolOf(env, "R2C_CORS", true);

        Log.init(parseLevel(strOf(env, "R2C_LOG_LEVEL", "info")));
    }

    public static Config fromEnv() {
        return new Config(System.getenv());
    }

    /** Applies {@link #modelAliases} to an incoming model name. */
    public String mapModel(String model) {
        if (model == null || model.isBlank()) return defaultModel;
        String mapped = modelAliases.get(model);
        if (mapped != null) return mapped;
        mapped = modelAliases.get(model.toLowerCase(Locale.ROOT));
        return mapped != null ? mapped : model;
    }

    /** Full upstream URL for the Responses endpoint. */
    public String responsesUrl() {
        return upstreamBaseUrl + "/responses";
    }

    /** Full upstream URL for the models endpoint. */
    public String modelsUrl() {
        return upstreamBaseUrl + "/models";
    }

    public void logSummary() {
        Log.info("bind=" + bindAddress + ":" + port);
        Log.info("upstream=" + upstreamBaseUrl + " (responses=" + responsesUrl() + ")");
        Log.info("upstream_key=" + mask(upstreamApiKey) + " pass_through_client_auth=" + passThroughClientAuth);
        Log.info("client_auth=" + (proxyApiKeys.isEmpty() ? "disabled" : proxyApiKeys.size() + " key(s)"));
        Log.info("timeout=" + upstreamTimeoutSeconds + "s connect=" + upstreamConnectTimeoutSeconds + "s");
        if (!modelAliases.isEmpty()) Log.info("model_aliases=" + modelAliases);
        if (!extraUpstreamHeaders.isEmpty()) Log.info("extra_headers=" + extraUpstreamHeaders.keySet());
    }

    private static String mask(String key) {
        if (key == null || key.isEmpty()) return "<none>";
        if (key.length() <= 8) return "***";
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }

    private static String normalizeBase(String url) {
        String v = url == null ? "" : url.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        // Accept a full endpoint URL by trimming the suffix people paste by accident.
        if (v.endsWith("/responses")) v = v.substring(0, v.length() - "/responses".length());
        if (v.endsWith("/chat/completions")) v = v.substring(0, v.length() - "/chat/completions".length());
        return v;
    }

    private static String strOf(Map<String, String> env, String key, String fallback) {
        String v = env.get(key);
        if (v == null || v.isBlank()) return fallback;
        return v.trim();
    }

    private static int intOf(Map<String, String> env, String key, int fallback) {
        String v = env.get(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            Log.warn("invalid int for " + key + "=" + v + ", using " + fallback);
            return fallback;
        }
    }

    private static boolean boolOf(Map<String, String> env, String key, boolean fallback) {
        String v = env.get(key);
        if (v == null || v.isBlank()) return fallback;
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "1": case "true": case "yes": case "on": return true;
            case "0": case "false": case "no": case "off": return false;
            default: return fallback;
        }
    }

    private static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Parses {@code a=b,c=d} into an ordered map. */
    private static Map<String, String> parseModelAliases(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : splitList(raw)) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                Log.warn("ignoring malformed model alias: " + pair);
                continue;
            }
            String from = pair.substring(0, eq).trim();
            String to = pair.substring(eq + 1).trim();
            out.put(from, to);
            out.putIfAbsent(from.toLowerCase(Locale.ROOT), to);
        }
        return out;
    }

    /** Parses {@code header=value} pairs separated by newlines or semicolons. */
    private static Map<String, String> parseHeaderList(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String pair : raw.split("[;\\n]")) {
            String t = pair.trim();
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq <= 0 || eq == t.length() - 1) {
                Log.warn("ignoring malformed upstream header: " + t);
                continue;
            }
            out.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
        }
        return out;
    }

    private static Log.Level parseLevel(String raw) {
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "error": return Log.Level.ERROR;
            case "warn": case "warning": return Log.Level.WARN;
            case "debug": case "trace": return Log.Level.DEBUG;
            default: return Log.Level.INFO;
        }
    }
}