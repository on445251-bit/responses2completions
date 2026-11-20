package io.r2c;

import io.r2c.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Upstream HTTP calls built on {@link HttpURLConnection}.
 *
 * <p>{@code java.net.http.HttpClient} is not available on Android, so this uses the
 * classic connection API that exists everywhere. Each call opens its own
 * connection; the proxy's traffic is low volume (one connection per request, with
 * streaming held open for the life of the answer), so a pool would buy little and
 * complicates lifecycle management on mobile.
 */
public final class UpstreamClient {

    private final Config config;

    public UpstreamClient(Config config) {
        this.config = config;
    }

    /**
     * Builds the Authorization header value for an upstream call.
     *
     * <p>When {@code passThroughClientAuth} is enabled and the caller supplied a
     * key, that key wins; otherwise the configured upstream key is used. This lets
     * a single proxy serve both "bring your own key" and "server-side key" setups.
     */
    private String resolveApiKey(String clientAuthHeader) {
        if (config.passThroughClientAuth && clientAuthHeader != null && !clientAuthHeader.isBlank()) {
            String v = clientAuthHeader.trim();
            if (v.regionMatches(true, 0, "Bearer ", 0, 7)) return v;
            if (v.regionMatches(true, 0, "Basic ", 0, 6)) return v;
            return "Bearer " + v;
        }
        if (config.upstreamApiKey == null || config.upstreamApiKey.isBlank()) return null;
        return "Bearer " + config.upstreamApiKey;
    }

    private HttpURLConnection open(String url, String clientAuthHeader, boolean streaming) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(config.upstreamConnectTimeoutSeconds * 1000);
        // For streaming the read timeout must be generous: the model may think for a
        // while before the first byte. Non-streaming uses the same budget.
        conn.setReadTimeout(config.upstreamTimeoutSeconds * 1000);
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", streaming ? "text/event-stream" : "application/json");

        String auth = resolveApiKey(clientAuthHeader);
        if (auth != null) conn.setRequestProperty("Authorization", auth);
        if (config.upstreamUserAgent != null && !config.upstreamUserAgent.isBlank()) {
            conn.setRequestProperty("User-Agent", config.upstreamUserAgent);
        }
        for (Map.Entry<String, String> e : config.extraUpstreamHeaders.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
        return conn;
    }

    /** Non-streaming call: returns the raw body, or throws on a non-2xx status. */
    public String postResponses(String body, String clientAuthHeader) {
        HttpURLConnection conn = null;
        try {
            conn = open(config.responsesUrl(), clientAuthHeader, false);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.getOutputStream().write(payload);

            int status = conn.getResponseCode();
            if (status / 100 != 2) {
                String errBody = readAll(conn.getErrorStream());
                Log.warn("upstream HTTP " + status + ": " + summarize(errBody));
                throw ProxyException.fromUpstream(status, errBody);
            }
            return readAll(conn.getInputStream());
        } catch (IOException e) {
            throw connectionFailure(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Streaming call: returns the connected stream so the caller can read SSE lines.
     *
     * <p>The caller owns the returned handle and must close it.
     */
    public StreamHandle streamResponses(String body, String clientAuthHeader) {
        HttpURLConnection conn = null;
        try {
            conn = open(config.responsesUrl(), clientAuthHeader, true);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.getOutputStream().write(payload);

            int status = conn.getResponseCode();
            if (status / 100 != 2) {
                String errBody = readAll(conn.getErrorStream());
                Log.warn("upstream HTTP " + status + ": " + summarize(errBody));
                conn.disconnect();
                throw ProxyException.fromUpstream(status, errBody);
            }
            return new StreamHandle(conn, conn.getInputStream());
        } catch (IOException e) {
            if (conn != null) conn.disconnect();
            throw connectionFailure(e);
        }
    }

    /** Fetches the upstream model list, used by {@code GET /v1/models}. */
    public String getModels(String clientAuthHeader) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(config.modelsUrl()).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(config.upstreamConnectTimeoutSeconds * 1000);
            conn.setReadTimeout(Math.min(config.upstreamTimeoutSeconds, 60) * 1000);
            conn.setRequestProperty("Accept", "application/json");
            String auth = resolveApiKey(clientAuthHeader);
            if (auth != null) conn.setRequestProperty("Authorization", auth);
            if (config.upstreamUserAgent != null && !config.upstreamUserAgent.isBlank()) {
                conn.setRequestProperty("User-Agent", config.upstreamUserAgent);
            }
            for (Map.Entry<String, String> e : config.extraUpstreamHeaders.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }

            int status = conn.getResponseCode();
            if (status / 100 != 2) {
                throw ProxyException.fromUpstream(status, readAll(conn.getErrorStream()));
            }
            return readAll(conn.getInputStream());
        } catch (IOException e) {
            throw connectionFailure(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Handle over a live SSE connection. */
    public static final class StreamHandle implements AutoCloseable {
        private final HttpURLConnection connection;
        public final InputStream body;

        StreamHandle(HttpURLConnection connection, InputStream body) {
            this.connection = connection;
            this.body = body;
        }

        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
                // stream already closed
            }
            connection.disconnect();
        }
    }

    private ProxyException connectionFailure(IOException e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new ProxyException(502, "upstream_error", "connection_failed",
                "cannot reach upstream " + config.responsesUrl() + ": " + msg);
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int n;
            while ((n = s.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    static String summarize(String body) {
        if (body == null) return "";
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }

    /**
     * Nothing to release: connections are disconnected per call. Kept so callers do
     * not need to know that.
     */
    public void close() {
        // no-op
    }
}