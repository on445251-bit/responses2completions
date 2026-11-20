package io.r2c;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.r2c.net.NanoHttpServer;
import io.r2c.translate.ChatToResponses;
import io.r2c.translate.ResponsesToChat;
import io.r2c.translate.StreamTranslator;
import io.r2c.util.Jsons;
import io.r2c.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The proxy's HTTP surface. Exposes the Chat Completions API and translates each
 * call onto the upstream Responses API.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code POST /v1/chat/completions} — main entry point</li>
 *   <li>{@code GET  /v1/models} — passthrough so clients can list models</li>
 *   <li>{@code GET  /health} — liveness probe, never touches the upstream</li>
 * </ul>
 *
 * <p>Built only on {@link NanoHttpServer} and {@link UpstreamClient}, so the same
 * class runs from a jar on a server and inside an Android app.
 */
public final class ProxyServer {

    /** Receives counters so the UI can display live activity. */
    public interface Stats {
        void onRequest(String model, boolean stream, boolean ok, long totalTokens);
    }

    private final Config config;
    private final UpstreamClient upstream;
    private final ChatToResponses chatToResponses;
    private final NanoHttpServer server;
    private final AtomicLong requestCounter = new AtomicLong();
    private volatile Stats stats;

    public ProxyServer(Config config) {
        this.config = config;
        this.upstream = new UpstreamClient(config);
        this.chatToResponses = new ChatToResponses(config);
        this.server = new NanoHttpServer(config.bindAddress, config.port, this::route);
    }

    public void setStats(Stats s) {
        this.stats = s;
    }

    public void start() throws IOException {
        server.start();
        Log.info("listening on http://" + config.bindAddress + ":" + server.boundPort());
    }

    public void stop() {
        server.stop();
        upstream.close();
    }

    public boolean isRunning() {
        return server.isRunning();
    }

    public int boundPort() {
        return server.boundPort();
    }

    public long servedRequests() {
        return requestCounter.get();
    }

    public long connectionCount() {
        return server.connectionCount();
    }

    public Config config() {
        return config;
    }

    // ------------------------------------------------------------------ routing

    private void route(NanoHttpServer.Exchange ex) {
        String path = ex.path;
        String method = ex.method;

        try {
            if (config.cors) {
                if ("OPTIONS".equals(method)) {
                    NanoHttpServer.Headers h = new NanoHttpServer.Headers();
                    addCors(h);
                    ex.send(204, h, new byte[0]);
                    return;
                }
            }

            switch (path) {
                case "/health":
                case "/healthz":
                    handleHealth(ex);
                    return;
                case "/v1/models":
                case "/models":
                    handleModels(ex, method);
                    return;
                case "/v1/chat/completions":
                case "/chat/completions":
                    handleChatCompletions(ex, method);
                    return;
                default:
                    if (path.length() > 1 && path.endsWith("/")
                            && path.substring(0, path.length() - 1).endsWith("/chat/completions")) {
                        handleChatCompletions(ex, method);
                        return;
                    }
                    writeJson(ex, 404, errorJson("unknown route: " + path, "invalid_request_error", null));
            }
        } catch (ProxyException pe) {
            Log.warn("request failed: " + pe.status() + " " + pe.getMessage());
            safeWriteJson(ex, pe.status(), pe.toErrorJson());
            notifyStats(null, false, false, 0);
        } catch (Exception e) {
            Log.error("unhandled error on " + method + " " + path, e);
            safeWriteJson(ex, 500, errorJson("internal proxy error: " + e.getMessage(), "server_error", null));
            notifyStats(null, false, false, 0);
        }
    }

    private void handleHealth(NanoHttpServer.Exchange ex) throws IOException {
        var root = Jsons.obj();
        root.put("status", "ok");
        root.put("upstream", config.responsesUrl());
        root.put("time", Instant.now().toString());
        root.put("served_requests", requestCounter.get());
        root.put("connections", server.connectionCount());
        writeJson(ex, 200, Jsons.write(root));
    }

    private void handleModels(NanoHttpServer.Exchange ex, String method) throws IOException {
        if (!"GET".equals(method)) {
            writeJson(ex, 405, errorJson("method not allowed", "invalid_request_error", null));
            return;
        }
        try {
            String body = upstream.getModels(authHeader(ex));
            writeJson(ex, 200, body);
        } catch (ProxyException pe) {
            // Some Responses-only gateways do not implement /models. Synthesize a
            // minimal list so client tooling does not hard-fail.
            Log.warn("upstream /models unavailable (" + pe.getMessage() + "), returning synthesized list");
            var root = Jsons.obj();
            root.put("object", "list");
            var data = root.putArray("data");
            String[] models = config.modelAliases.isEmpty()
                    ? new String[]{config.defaultModel}
                    : config.modelAliases.keySet().stream()
                            .filter(k -> k.equals(k.toLowerCase(Locale.ROOT)))
                            .toArray(String[]::new);
            long created = Instant.now().getEpochSecond();
            for (String m : models) {
                var o = data.addObject();
                o.put("id", m);
                o.put("object", "model");
                o.put("created", created);
                o.put("owned_by", "responses2completions");
            }
            writeJson(ex, 200, Jsons.write(root));
        }
    }

    private void handleChatCompletions(NanoHttpServer.Exchange ex, String method) throws IOException {
        if (!"POST".equals(method)) {
            writeJson(ex, 405, errorJson("method not allowed, use POST", "invalid_request_error", null));
            return;
        }
        long seq = requestCounter.incrementAndGet();
        requireAuth(ex);

        String raw = ex.readBody();
        JsonNode chatReq = Jsons.parseOrNull(raw);
        if (chatReq == null) {
            throw new ProxyException(400, "invalid_request_error", "invalid_json",
                    "request body is not valid JSON");
        }

        boolean stream = chatReq.path("stream").asBoolean(false);
        String model = config.mapModel(Jsons.str(chatReq, "model"));
        ObjectNode responsesReq = chatToResponses.translate(chatReq);
        String upstreamBody = Jsons.write(responsesReq);

        Log.info("#" + seq + " -> " + model + (stream ? " [stream]" : "")
                + " messages=" + chatReq.path("messages").size()
                + " tools=" + responsesReq.path("tools").size());

        if (Log.debugEnabled()) {
            Log.debug("#" + seq + " request: " + truncate(upstreamBody, 4000));
        }

        String clientAuth = authHeader(ex);

        if (stream) {
            handleStreaming(ex, seq, upstreamBody, clientAuth, model);
        } else {
            handleNonStreaming(ex, seq, upstreamBody, clientAuth, model);
        }
    }

    // ----------------------------------------------------------------- streaming

    private void handleStreaming(NanoHttpServer.Exchange ex, long seq, String upstreamBody,
                                 String clientAuth, String model) throws IOException {
        // Open the upstream first: if it fails we can still return a proper HTTP
        // error instead of a half-started SSE stream.
        UpstreamClient.StreamHandle upstreamHandle = upstream.streamResponses(upstreamBody, clientAuth);

        NanoHttpServer.Headers headers = new NanoHttpServer.Headers();
        if (config.cors) addCors(headers);
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache, no-transform");
        headers.set("X-Accel-Buffering", "no");

        String chatId = ResponsesToChat.newChatCompletionId();
        long created = Instant.now().getEpochSecond();
        StreamTranslator translator = new StreamTranslator(chatId, created, model, config.alwaysIncludeUsage());

        boolean sawTerminal = false;
        boolean failed = false;
        try (upstreamHandle;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(upstreamHandle.body, StandardCharsets.UTF_8))) {

            ex.beginChunked(200, headers);

            StringBuilder dataBuffer = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (dataBuffer.length() > 0) {
                        String payload = dataBuffer.toString();
                        dataBuffer.setLength(0);

                        if (payload.equals("[DONE]")) {
                            sawTerminal = true;
                            break;
                        }
                        JsonNode event = Jsons.parseOrNull(payload);
                        if (event == null) {
                            Log.warn("#" + seq + " unparseable upstream event: " + truncate(payload, 300));
                            continue;
                        }
                        if (Log.debugEnabled()) {
                            Log.debug("#" + seq + " evt " + Jsons.str(event, "type", "?"));
                        }

                        for (ObjectNode chunk : translator.handle(event)) {
                            writeSse(ex, Jsons.write(chunk));
                        }

                        String type = Jsons.str(event, "type", "");
                        if (type.equals("response.completed") || type.equals("response.incomplete")
                                || type.equals("response.failed") || type.equals("error")) {
                            sawTerminal = true;
                            if (type.equals("error") || type.equals("response.failed")) {
                                failed = true;
                                writeErrorEvent(ex, event);
                            }
                            break;
                        }
                    }
                    continue;
                }

                if (line.startsWith(":")) {
                    writeSseComment(ex, line.substring(1).trim());
                    continue;
                }
                if (line.startsWith("data:")) {
                    String value = line.substring(5);
                    if (value.startsWith(" ")) value = value.substring(1);
                    if (dataBuffer.length() > 0) dataBuffer.append('\n');
                    dataBuffer.append(value);
                }
            }

            // Flush a trailing event if the upstream omitted the final blank line.
            if (dataBuffer.length() > 0) {
                String payload = dataBuffer.toString();
                if (!payload.equals("[DONE]")) {
                    JsonNode event = Jsons.parseOrNull(payload);
                    if (event != null) {
                        for (ObjectNode chunk : translator.handle(event)) {
                            writeSse(ex, Jsons.write(chunk));
                        }
                    }
                }
            }

            // Always close the chat stream properly, even if the upstream died.
            ObjectNode terminal = translator.finish();
            if (terminal != null) {
                if (config.alwaysIncludeUsage && translator.usage() == null) {
                    terminal.set("usage", ResponsesToChat.zeroUsage());
                }
                writeSse(ex, Jsons.write(terminal));
            }
            writeSseDone(ex);

            Log.info("#" + seq + " <- stream done"
                    + (sawTerminal ? "" : " (upstream ended without terminal event)"));
        } catch (IOException e) {
            // A client disconnect surfaces here; that is normal for SSE.
            Log.warn("#" + seq + " stream interrupted: " + e.getMessage());
        } finally {
            ex.endChunked();
            notifyStats(model, true, !failed, translator.usage() == null
                    ? 0 : translator.usage().path("total_tokens").asInt(0));
        }
    }

    // ------------------------------------------------------------- non-streaming

    private void handleNonStreaming(NanoHttpServer.Exchange ex, long seq, String upstreamBody,
                                    String clientAuth, String model) throws IOException {
        String body = upstream.postResponses(upstreamBody, clientAuth);
        JsonNode responses = Jsons.parseOrNull(body);
        if (responses == null) {
            throw new ProxyException(502, "upstream_error", "invalid_upstream_json",
                    "upstream returned non-JSON body: " + truncate(body, 300));
        }

        // Surface upstream-side failures carried inside a 200 body.
        JsonNode error = responses.get("error");
        if (error != null && !error.isNull()) {
            throw ProxyException.fromUpstream(502, body);
        }

        ObjectNode chat = ResponsesToChat.toChatCompletion(responses, model);
        String out = Jsons.write(chat);

        JsonNode usage = chat.get("usage");
        int tokens = usage.path("total_tokens").asInt(0);
        Log.info("#" + seq + " <- " + chat.path("choices").path(0).path("finish_reason").asText("?")
                + " tokens=" + tokens);

        writeJson(ex, 200, out);
        notifyStats(model, false, true, tokens);
    }

    // ------------------------------------------------------------------- helpers

    private void notifyStats(String model, boolean stream, boolean ok, long tokens) {
        Stats s = stats;
        if (s == null) return;
        try {
            s.onRequest(model, stream, ok, tokens);
        } catch (RuntimeException ignored) {
            // Stats must never break a request.
        }
    }

    private void requireAuth(NanoHttpServer.Exchange ex) {
        if (config.proxyApiKeys.isEmpty()) return;
        String header = authHeader(ex);
        if (header == null || header.isBlank()) {
            throw new ProxyException(401, "invalid_request_error", "missing_api_key",
                    "missing Authorization header");
        }
        String token = header.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) token = token.substring(7).trim();
        if (!config.proxyApiKeys.contains(token)) {
            throw new ProxyException(401, "invalid_request_error", "invalid_api_key",
                    "invalid API key");
        }
    }

    private static String authHeader(NanoHttpServer.Exchange ex) {
        return ex.requestHeaders.get("Authorization");
    }

    private static void writeSse(NanoHttpServer.Exchange ex, String json) throws IOException {
        ex.writeChunk("data: " + json + "\n\n");
    }

    private static void writeSseComment(NanoHttpServer.Exchange ex, String comment) throws IOException {
        ex.writeChunk(": " + comment + "\n\n");
    }

    private static void writeSseDone(NanoHttpServer.Exchange ex) throws IOException {
        ex.writeChunk("data: [DONE]\n\n");
    }

    /** Emits an in-band error so a chat client sees the failure mid-stream. */
    private static void writeErrorEvent(NanoHttpServer.Exchange ex, JsonNode serverEvent) throws IOException {
        var root = Jsons.obj();
        var err = root.putObject("error");
        JsonNode src = serverEvent.has("error") ? serverEvent.get("error") : serverEvent;
        err.put("message", Jsons.str(src, "message", "upstream stream failed"));
        err.put("type", Jsons.str(src, "type", "upstream_error"));
        String code = Jsons.str(src, "code");
        if (code != null) err.put("code", code);
        writeSse(ex, Jsons.write(root));
    }

    private void writeJson(NanoHttpServer.Exchange ex, int status, String body) throws IOException {
        if (config.cors) {
            NanoHttpServer.Headers h = new NanoHttpServer.Headers();
            addCors(h);
            h.set("Content-Type", "application/json; charset=utf-8");
            ex.send(status, h, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
            return;
        }
        ex.sendJson(status, body);
    }

    /** Best-effort write used in error handlers, where the stream may already be closed. */
    private void safeWriteJson(NanoHttpServer.Exchange ex, int status, String body) {
        if (ex.responded()) return;
        try {
            writeJson(ex, status, body);
        } catch (IOException | IllegalStateException ignored) {
            // Response already committed; nothing more we can do.
        }
    }

    static void addCors(NanoHttpServer.Headers h) {
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Authorization, Content-Type, Accept, X-Requested-With");
        h.set("Access-Control-Max-Age", "86400");
    }

    static String errorJson(String message, String type, String code) {
        var root = Jsons.obj();
        var err = root.putObject("error");
        err.put("message", message);
        err.put("type", type);
        if (code != null) err.put("code", code);
        return Jsons.write(root);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(" + s.length() + " chars)";
    }
}