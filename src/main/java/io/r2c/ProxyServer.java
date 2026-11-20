package io.r2c;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.r2c.translate.ChatToResponses;
import io.r2c.translate.ResponsesToChat;
import io.r2c.translate.StreamTranslator;
import io.r2c.util.Jsons;
import io.r2c.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 */
public final class ProxyServer {

    private final Config config;
    private final UpstreamClient upstream;
    private final ChatToResponses chatToResponses;
    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicLong requestCounter = new AtomicLong();

    public ProxyServer(Config config) throws IOException {
        this.config = config;
        this.upstream = new UpstreamClient(config);
        this.chatToResponses = new ChatToResponses(config);

        this.server = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 0);
        // Each SSE stream holds a thread for its whole lifetime, so use a cached
        // pool: threads are reused across short requests and new ones are created
        // only when many streams run concurrently.
        this.executor = Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
            private final AtomicLong counter = new AtomicLong();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "r2c-http-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        server.setExecutor(executor);

        server.createContext("/", this::route);
    }

    public void start() {
        server.start();
        Log.info("listening on http://" + config.bindAddress + ":" + config.port);
    }

    public void stop() {
        server.stop(1);
        executor.shutdownNow();
        upstream.close();
    }

    // ------------------------------------------------------------------ routing

    private void route(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);

        try {
            if (config.cors) {
                addCors(ex.getResponseHeaders());
                if ("OPTIONS".equals(method)) {
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
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
                    // Normalize away a trailing slash, then retry once.
                    if (path.length() > 1 && path.endsWith("/")) {
                        String trimmed = path.substring(0, path.length() - 1);
                        if (trimmed.endsWith("/chat/completions")) {
                            handleChatCompletions(ex, method);
                            return;
                        }
                    }
                    writeJson(ex, 404, errorJson("unknown route: " + path, "invalid_request_error", null));
            }
        } catch (ProxyException pe) {
            Log.warn("request failed: " + pe.status() + " " + pe.getMessage());
            safeWriteJson(ex, pe.status(), pe.toErrorJson());
        } catch (Exception e) {
            Log.error("unhandled error on " + method + " " + path, e);
            safeWriteJson(ex, 500, errorJson("internal proxy error: " + e.getMessage(), "server_error", null));
        } finally {
            ex.close();
        }
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        var root = Jsons.obj();
        root.put("status", "ok");
        root.put("upstream", config.responsesUrl());
        root.put("time", Instant.now().toString());
        root.put("served_requests", requestCounter.get());
        writeJson(ex, 200, Jsons.write(root));
    }

    private void handleModels(HttpExchange ex, String method) throws IOException {
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
                    : config.modelAliases.keySet().stream().filter(k -> k.equals(k.toLowerCase(Locale.ROOT)))
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

    private void handleChatCompletions(HttpExchange ex, String method) throws IOException {
        if (!"POST".equals(method)) {
            writeJson(ex, 405, errorJson("method not allowed, use POST", "invalid_request_error", null));
            return;
        }
        long seq = requestCounter.incrementAndGet();
        requireAuth(ex);

        String raw = readBody(ex);
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
            handleStreaming(ex, seq, responsesReq, upstreamBody, clientAuth, model);
        } else {
            handleNonStreaming(ex, seq, upstreamBody, clientAuth, model);
        }
    }

    // ----------------------------------------------------------------- streaming

    private void handleStreaming(HttpExchange ex, long seq, ObjectNode responsesReq,
                                 String upstreamBody, String clientAuth, String model) throws IOException {
        var upstreamResp = upstream.streamResponses(upstreamBody, clientAuth);

        Headers respHeaders = ex.getResponseHeaders();
        if (config.cors) addCors(respHeaders);
        respHeaders.set("Content-Type", "text/event-stream; charset=utf-8");
        respHeaders.set("Cache-Control", "no-cache, no-transform");
        respHeaders.set("Connection", "keep-alive");
        respHeaders.set("X-Accel-Buffering", "no");

        // 0 means "chunked, length unknown" — required for streaming.
        ex.sendResponseHeaders(200, 0);

        String chatId = ResponsesToChat.newChatCompletionId();
        long created = Instant.now().getEpochSecond();
        StreamTranslator translator = new StreamTranslator(chatId, created, model, config.alwaysIncludeUsage);

        OutputStream out = ex.getResponseBody();
        boolean sawTerminal = false;

        try (var reader = new BufferedReader(
                new InputStreamReader(upstreamResp.body(), StandardCharsets.UTF_8))) {

            StringBuilder dataBuffer = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    // Blank line terminates one SSE event.
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

                        List<ObjectNode> chunks = translator.handle(event);
                        for (ObjectNode chunk : chunks) {
                            writeSse(out, Jsons.write(chunk));
                        }

                        String type = Jsons.str(event, "type", "");
                        if (type.equals("response.completed") || type.equals("response.incomplete")
                                || type.equals("response.failed") || type.equals("error")) {
                            sawTerminal = true;
                            if (type.equals("error") || type.equals("response.failed")) {
                                writeErrorEvent(out, event);
                            }
                            break;
                        }
                    }
                    continue;
                }

                if (line.startsWith(":")) {
                    // Comment/heartbeat; forward as SSE comment so proxies keep alive.
                    writeSseComment(out, line.substring(1).trim());
                    continue;
                }
                if (line.startsWith("data:")) {
                    String value = line.substring(5);
                    if (value.startsWith(" ")) value = value.substring(1);
                    if (dataBuffer.length() > 0) dataBuffer.append('\n');
                    dataBuffer.append(value);
                }
                // event:/id:/retry: lines are irrelevant because we re-derive types
                // from the JSON payload itself.
            }

            // Flush a trailing event if the upstream omitted the final blank line.
            if (dataBuffer.length() > 0) {
                String payload = dataBuffer.toString();
                if (!payload.equals("[DONE]")) {
                    JsonNode event = Jsons.parseOrNull(payload);
                    if (event != null) {
                        for (ObjectNode chunk : translator.handle(event)) {
                            writeSse(out, Jsons.write(chunk));
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
                writeSse(out, Jsons.write(terminal));
            }
            writeSseDone(out);
            out.flush();

            Log.info("#" + seq + " <- stream done"
                    + (sawTerminal ? "" : " (upstream ended without terminal event)"));
        } catch (IOException e) {
            // A client disconnect surfaces here; that is normal for SSE.
            Log.warn("#" + seq + " stream interrupted: " + e.getMessage());
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
                // already closed by the container
            }
        }
    }

    // ------------------------------------------------------------- non-streaming

    private void handleNonStreaming(HttpExchange ex, long seq, String upstreamBody,
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
        Log.info("#" + seq + " <- " + chat.path("choices").path(0).path("finish_reason").asText("?")
                + " tokens=" + usage.path("total_tokens").asInt(0));

        writeJson(ex, 200, out);
    }

    // ------------------------------------------------------------------- helpers

    private void requireAuth(HttpExchange ex) {
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

    private static String authHeader(HttpExchange ex) {
        return ex.getRequestHeaders().getFirst("Authorization");
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (var in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeSse(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void writeSseComment(OutputStream out, String comment) throws IOException {
        out.write((": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void writeSseDone(OutputStream out) throws IOException {
        out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Emits an in-band error so a chat client sees the failure mid-stream. */
    private static void writeErrorEvent(OutputStream out, JsonNode serverEvent) throws IOException {
        var root = Jsons.obj();
        var err = root.putObject("error");
        JsonNode src = serverEvent.has("error") ? serverEvent.get("error") : serverEvent;
        err.put("message", Jsons.str(src, "message", "upstream stream failed"));
        err.put("type", Jsons.str(src, "type", "upstream_error"));
        String code = Jsons.str(src, "code");
        if (code != null) err.put("code", code);
        writeSse(out, Jsons.write(root));
    }

    private static void writeJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Best-effort write used in error handlers, where the stream may already be closed. */
    private static void safeWriteJson(HttpExchange ex, int status, String body) {
        try {
            writeJson(ex, status, body);
        } catch (IOException | IllegalStateException ignored) {
            // Response already committed; nothing more we can do.
        }
    }

    private static void addCors(Headers h) {
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Authorization, Content-Type, Accept, X-Requested-With");
        h.set("Access-Control-Max-Age", "86400");
    }

    private static String errorJson(String message, String type, String code) {
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