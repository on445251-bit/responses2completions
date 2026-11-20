package io.r2c;

import io.r2c.util.Log;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Thin wrapper over {@link HttpClient} for upstream Responses calls.
 *
 * <p>One client instance is shared for the lifetime of the process: it owns the
 * connection pool, so building it per request would defeat keep-alive. Requests
 * are built per call because headers differ (client token passthrough).
 */
public final class UpstreamClient {

    private final Config config;
    private final HttpClient http;

    public UpstreamClient(Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.upstreamConnectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
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
            // Accept both "Bearer sk-..." and a raw key.
            if (v.regionMatches(true, 0, "Bearer ", 0, 7)) return v;
            if (v.regionMatches(true, 0, "Basic ", 0, 6)) return v;
            return "Bearer " + v;
        }
        if (config.upstreamApiKey == null || config.upstreamApiKey.isBlank()) return null;
        return "Bearer " + config.upstreamApiKey;
    }

    private HttpRequest.Builder baseRequest(String url, String clientAuthHeader) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.upstreamTimeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        String auth = resolveApiKey(clientAuthHeader);
        if (auth != null) b.header("Authorization", auth);
        for (Map.Entry<String, String> e : config.extraUpstreamHeaders.entrySet()) {
            b.header(e.getKey(), e.getValue());
        }
        return b;
    }

    /** Non-streaming call: returns the raw body, or throws on a non-2xx status. */
    public String postResponses(String body, String clientAuthHeader) {
        HttpRequest req = baseRequest(config.responsesUrl(), clientAuthHeader)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProxyException(502, "upstream_error", "connection_failed",
                    "cannot reach upstream " + config.responsesUrl() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProxyException(499, "upstream_error", "interrupted", "request interrupted");
        }

        if (resp.statusCode() / 100 != 2) {
            Log.warn("upstream HTTP " + resp.statusCode() + ": " + summarize(resp.body()));
            throw ProxyException.fromUpstream(resp.statusCode(), resp.body());
        }
        return resp.body();
    }

    /** Streaming call: returns the input stream of the SSE body for line reading. */
    public HttpResponse<java.io.InputStream> streamResponses(String body, String clientAuthHeader) {
        HttpRequest req = baseRequest(config.responsesUrl(), clientAuthHeader)
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<java.io.InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ProxyException(502, "upstream_error", "connection_failed",
                    "cannot reach upstream " + config.responsesUrl() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProxyException(499, "upstream_error", "interrupted", "request interrupted");
        }

        if (resp.statusCode() / 100 != 2) {
            String errBody;
            try (var in = resp.body()) {
                errBody = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                errBody = "";
            }
            Log.warn("upstream HTTP " + resp.statusCode() + ": " + summarize(errBody));
            throw ProxyException.fromUpstream(resp.statusCode(), errBody);
        }
        return resp;
    }

    /** Fetches the upstream model list, used by {@code GET /v1/models}. */
    public String getModels(String clientAuthHeader) {
        HttpRequest req = baseRequest(config.modelsUrl(), clientAuthHeader).GET().build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw ProxyException.fromUpstream(resp.statusCode(), resp.body());
            }
            return resp.body();
        } catch (IOException e) {
            throw new ProxyException(502, "upstream_error", "connection_failed",
                    "cannot reach upstream: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProxyException(499, "upstream_error", "interrupted", "request interrupted");
        }
    }

    private static String summarize(String body) {
        if (body == null) return "";
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }

    public void close() {
        // HttpClient is not AutoCloseable before Java 21, so shut it down
        // reflectively: on 21+ this releases the selector and executor, on 17 it is
        // a no-op and the daemon threads die with the JVM.
        try {
            HttpClient.class.getMethod("close").invoke(http);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Older runtime: nothing to release explicitly.
        }
    }
}