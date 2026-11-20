package io.r2c;

import io.r2c.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime configuration.
 *
 * <p>Two construction paths share one immutable object: {@link #fromEnv()} for the
 * command line build, and {@link #builder()} for the Android app where values come
 * from persisted user settings instead of the environment.
 *
 * <p>Every field has a default so the proxy can boot with no configuration at all.
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
    /** Emit usage on the final stream chunk whenever usage is known. */
    public final boolean alwaysIncludeUsage;
    /** Extra headers added to every upstream request, as name=value pairs. */
    public final Map<String, String> extraUpstreamHeaders;
    /** Enable permissive CORS so browser based tools can call the proxy. */
    public final boolean cors;
    /**
     * User-Agent sent upstream. Some gateways only accept requests from
     * programming clients (Codex, Cursor, Cline...) and reject everything else,
     * so this is configurable rather than defaulting to the JVM's UA.
     */
    public final String upstreamUserAgent;

    private Config(Builder b) {
        this.port = b.port;
        this.bindAddress = b.bindAddress;
        this.upstreamBaseUrl = normalizeBase(b.upstreamBaseUrl);
        this.upstreamApiKey = b.upstreamApiKey == null ? "" : b.upstreamApiKey.trim();
        this.passThroughClientAuth = b.passThroughClientAuth;
        this.proxyApiKeys = List.copyOf(b.proxyApiKeys);
        this.upstreamTimeoutSeconds = b.upstreamTimeoutSeconds;
        this.upstreamConnectTimeoutSeconds = b.upstreamConnectTimeoutSeconds;
        this.modelAliases = Map.copyOf(b.modelAliases);
        this.defaultModel = b.defaultModel;
        this.passStop = b.passStop;
        this.alwaysIncludeUsage = b.alwaysIncludeUsage;
        this.extraUpstreamHeaders = Map.copyOf(b.extraUpstreamHeaders);
        this.cors = b.cors;
        this.upstreamUserAgent = b.upstreamUserAgent;
    }

    // ------------------------------------------------------------------- builder

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder so callers only set what differs from the defaults. */
    public static final class Builder {
        private int port = 8080;
        private String bindAddress = "0.0.0.0";
        private String upstreamBaseUrl = "https://api.openai.com/v1";
        private String upstreamApiKey = "";
        private boolean passThroughClientAuth = true;
        private List<String> proxyApiKeys = new ArrayList<>();
        private int upstreamTimeoutSeconds = 600;
        private int upstreamConnectTimeoutSeconds = 20;
        private Map<String, String> modelAliases = new LinkedHashMap<>();
        private String defaultModel = "gpt-4o-mini";
        private boolean passStop = false;
        private boolean alwaysIncludeUsage = true;
        private Map<String, String> extraUpstreamHeaders = new LinkedHashMap<>();
        private boolean cors = true;
        private String upstreamUserAgent = "codex_cli_rs/0.42.0";

        public Builder port(int v) {
            this.port = v;
            return this;
        }

        public Builder bindAddress(String v) {
            if (v != null && !v.isBlank()) this.bindAddress = v.trim();
            return this;
        }

        public Builder bindLan(boolean lan) {
            this.bindAddress = lan ? "0.0.0.0" : "127.0.0.1";
            return this;
        }

        public Builder upstreamBaseUrl(String v) {
            if (v != null && !v.isBlank()) this.upstreamBaseUrl = v.trim();
            return this;
        }

        public Builder upstreamApiKey(String v) {
            this.upstreamApiKey = v == null ? "" : v.trim();
            return this;
        }

        public Builder passThroughClientAuth(boolean v) {
            this.passThroughClientAuth = v;
            return this;
        }

        public Builder proxyApiKeys(List<String> v) {
            this.proxyApiKeys = v == null ? new ArrayList<>() : new ArrayList<>(v);
            return this;
        }

        public Builder upstreamTimeoutSeconds(int v) {
            if (v > 0) this.upstreamTimeoutSeconds = v;
            return this;
        }

        public Builder upstreamConnectTimeoutSeconds(int v) {
            if (v > 0) this.upstreamConnectTimeoutSeconds = v;
            return this;
        }

        public Builder modelAliases(Map<String, String> v) {
            this.modelAliases = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
            return this;
        }

        public Builder defaultModel(String v) {
            if (v != null && !v.isBlank()) this.defaultModel = v.trim();
            return this;
        }

        public Builder passStop(boolean v) {
            this.passStop = v;
            return this;
        }

        public Builder alwaysIncludeUsage(boolean v) {
            this.alwaysIncludeUsage = v;
            return this;
        }

        public Builder extraUpstreamHeaders(Map<String, String> v) {
            this.extraUpstreamHeaders = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
            return this;
        }

        public Builder cors(boolean v) {
            this.cors = v;
            return this;
        }

        public Builder upstreamUserAgent(String v) {
            if (v != null && !v.isBlank()) this.upstreamUserAgent = v.trim();
            return this;
        }

        public Config build() {
            return new Config(this);
        }
    }

    // --------------------------------------------------------------- env parsing

    public static Config fromEnv() {
        return fromMap(System.getenv());
    }

    /** Parses the CLI environment. Split out so it can be unit tested. */
    public static Config fromMap(Map<String, String> env) {
        Log.init(parseLevel(strOf(env, "R2C_LOG_LEVEL", "info")));
        return builder()
                .port(intOf(env, "R2C_PORT", intOf(env, "PORT", 8080)))
                .bindAddress(strOf(env, "R2C_BIND", "0.0.0.0"))
                .upstreamBaseUrl(strOf(env, "R2C_UPSTREAM_BASE_URL",
                        strOf(env, "UPSTREAM_BASE_URL", "https://api.openai.com/v1")))
                .upstreamApiKey(strOf(env, "R2C_UPSTREAM_API_KEY",
                        strOf(env, "UPSTREAM_API_KEY", strOf(env, "OPENAI_API_KEY", ""))))
                .passThroughClientAuth(boolOf(env, "R2C_PASS_THROUGH_CLIENT_AUTH", true))
                .proxyApiKeys(splitList(strOf(env, "R2C_API_KEYS", "")))
                .upstreamTimeoutSeconds(intOf(env, "R2C_UPSTREAM_TIMEOUT_SECONDS", 600))
                .upstreamConnectTimeoutSeconds(intOf(env, "R2C_UPSTREAM_CONNECT_TIMEOUT_SECONDS", 20))
                .modelAliases(parseModelAliases(strOf(env, "R2C_MODEL_ALIASES", "")))
                .defaultModel(strOf(env, "R2C_DEFAULT_MODEL", "gpt-4o-mini"))
                .passStop(boolOf(env, "R2C_PASS_STOP", false))
                .alwaysIncludeUsage(boolOf(env, "R2C_ALWAYS_INCLUDE_USAGE", true))
                .extraUpstreamHeaders(parseHeaderList(strOf(env, "R2C_UPSTREAM_HEADERS", "")))
                .cors(boolOf(env, "R2C_CORS", true))
                .upstreamUserAgent(strOf(env, "R2C_UPSTREAM_USER_AGENT", "codex_cli_rs/0.42.0"))
                .build();
    }

    // --------------------------------------------------------------------- utils

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

    /** Host portion of the upstream, for compact UI display. */
    public String upstreamHost() {
        try {
            java.net.URI uri = java.net.URI.create(upstreamBaseUrl);
            return uri.getHost() == null ? upstreamBaseUrl : uri.getHost();
        } catch (RuntimeException e) {
            return upstreamBaseUrl;
        }
    }

    public void logSummary() {
        Log.info("bind=" + bindAddress + ":" + port);
        Log.info("upstream=" + upstreamBaseUrl + " (responses=" + responsesUrl() + ")");
        Log.info("upstream_key=" + mask(upstreamApiKey) + " pass_through_client_auth=" + passThroughClientAuth);
        Log.info("client_auth=" + (proxyApiKeys.isEmpty() ? "disabled" : proxyApiKeys.size() + " key(s)"));
        Log.info("timeout=" + upstreamTimeoutSeconds + "s connect=" + upstreamConnectTimeoutSeconds + "s");
        if (!modelAliases.isEmpty()) Log.info("model_aliases=" + modelAliases);
        if (!extraUpstreamHeaders.isEmpty()) Log.info("extra_headers=" + extraUpstreamHeaders.keySet());
        Log.info("upstream_user_agent=" + upstreamUserAgent);
    }

    private static String mask(String key) {
        if (key == null || key.isEmpty()) return "<none>";
        if (key.length() <= 8) return "***";
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }

    static String normalizeBase(String url) {
        String v = url == null ? "" : url.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        // Accept a full endpoint URL by trimming the suffix people paste by accident.
        if (v.endsWith("/responses")) v = v.substring(0, v.length() - "/responses".length());
        if (v.endsWith("/chat/completions")) v = v.substring(0, v.length() - "/chat/completions".length());
        if (!v.contains("://") && !v.isEmpty()) v = "https://" + v;
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

    static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Parses {@code a=b,c=d} into an ordered map. */
    static Map<String, String> parseModelAliases(String raw) {
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
    static Map<String, String> parseHeaderList(String raw) {
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

    static Log.Level parseLevel(String raw) {
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "error": return Log.Level.ERROR;
            case "warn": case "warning": return Log.Level.WARN;
            case "debug": case "trace": return Log.Level.DEBUG;
            default: return Log.Level.INFO;
        }
    }
}