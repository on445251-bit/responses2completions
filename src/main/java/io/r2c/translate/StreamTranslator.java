package io.r2c.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.r2c.util.Jsons;
import io.r2c.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stateful translator that turns the Responses API SSE event stream into
 * {@code chat.completion.chunk} objects.
 *
 * <p>The Responses stream is finer grained than the chat stream: it announces
 * output items and content parts, streams text / reasoning / function-call
 * argument deltas separately, and reports usage on the terminal event. A chat
 * client only understands {@code delta.content}, {@code delta.tool_calls} and a
 * final {@code finish_reason}, so this class tracks item indices, decides when a
 * new tool call starts, and flushes the closing chunk exactly once.
 *
 * <p>Usage: feed each raw event's data through {@link #handle(JsonNode)}, which
 * returns zero or more chunk objects in order. Call {@link #finish()} at the end
 * of the stream to obtain the terminal chunk (and to recover any text that the
 * upstream only sent on the terminating event).
 */
public final class StreamTranslator {

    private final String chatId;
    private final long created;
    private final String model;
    private final boolean includeUsage;

    // Item tracking. Responses use output_index for items and content_index inside them.
    private final Map<Integer, String> itemTypes = new LinkedHashMap<>();
    private final Map<Integer, Integer> toolIndexByOutputIndex = new LinkedHashMap<>();
    private final Map<Integer, String> callIdByOutputIndex = new LinkedHashMap<>();
    private final Map<Integer, Boolean> announcedToolCalls = new LinkedHashMap<>();
    private final Map<Integer, StringBuilder> argsByOutputIndex = new LinkedHashMap<>();

    private int nextToolIndex = 0;
    private boolean roleSent = false;
    private boolean finishSent = false;
    private boolean reasoningRoleSent = false;

    private String status;
    private String incompleteReason;
    private JsonNode usage;
    private JsonNode error;
    private String finalText;

    public StreamTranslator(String chatId, long created, String model, boolean includeUsage) {
        this.chatId = chatId;
        this.created = created;
        this.model = model == null ? "" : model;
        this.includeUsage = includeUsage;
    }

    public boolean finishSent() {
        return finishSent;
    }

    public JsonNode usage() {
        return usage;
    }

    public JsonNode error() {
        return error;
    }

    /**
     * Consumes one decoded SSE event and returns the chunks to emit, in order.
     * The returned array may be empty.
     */
    public java.util.List<ObjectNode> handle(JsonNode event) {
        java.util.List<ObjectNode> out = new java.util.ArrayList<>(2);
        if (event == null || !event.isObject()) return out;

        String type = Jsons.str(event, "type", "");
        JsonNode payload = event.has("response") ? event.get("response") : event;

        switch (type) {
            case "response.created":
            case "response.in_progress": {
                captureMeta(payload);
                out.add(roleChunkIfNeeded());
                break;
            }

            case "response.output_item.added": {
                int idx = event.path("output_index").asInt(0);
                JsonNode item = event.get("item");
                String itemType = Jsons.str(item, "type", "message");
                itemTypes.put(idx, itemType);

                if ("function_call".equals(itemType)) {
                    String callId = Jsons.str(item, "call_id");
                    String name = Jsons.str(item, "name");
                    int toolIndex = nextToolIndex++;
                    toolIndexByOutputIndex.put(idx, toolIndex);
                    callIdByOutputIndex.put(idx, callId);
                    announcedToolCalls.put(idx, Boolean.TRUE);
                    argsByOutputIndex.put(idx, new StringBuilder());

                    out.add(roleChunkIfNeeded());
                    // Announce the call with its id/name; arguments arrive later.
                    out.add(ResponsesToChat.toolCallChunk(chatId, created, model,
                            toolIndex, callId, name, ""));
                }
                break;
            }

            case "response.output_item.done": {
                int idx = event.path("output_index").asInt(0);
                JsonNode item = event.get("item");
                String itemType = Jsons.str(item, "type", null);
                if (itemType != null) itemTypes.put(idx, itemType);

                if ("function_call".equals(itemTypes.get(idx))) {
                    // Some gateways send the whole argument blob only here.
                    String full = Jsons.str(item, "arguments");
                    StringBuilder sb = argsByOutputIndex.computeIfAbsent(idx, k -> new StringBuilder());
                    if (full != null && full.length() > sb.length()) {
                        String remainder = full.substring(sb.length());
                        sb.append(remainder);
                        out.add(roleChunkIfNeeded());
                        out.add(ResponsesToChat.toolCallChunk(chatId, created, model,
                                toolIndexOf(idx), null, null, remainder));
                    }
                }
                break;
            }

            case "response.content_part.added": {
                out.add(roleChunkIfNeeded());
                break;
            }

            case "response.output_text.delta": {
                String delta = Jsons.str(event, "delta");
                if (delta != null && !delta.isEmpty()) {
                    out.add(roleChunkIfNeeded());
                    out.add(ResponsesToChat.contentChunk(chatId, created, model, delta));
                }
                break;
            }

            case "response.output_text.done": {
                String text = Jsons.str(event, "text");
                if (text != null) finalText = text;
                break;
            }

            case "response.refusal.delta": {
                String delta = Jsons.str(event, "delta");
                if (delta != null && !delta.isEmpty()) {
                    out.add(roleChunkIfNeeded());
                    out.add(ResponsesToChat.contentChunk(chatId, created, model, delta));
                }
                break;
            }

            case "response.reasoning_summary_text.delta":
            case "response.reasoning_text.delta": {
                String delta = Jsons.str(event, "delta");
                if (delta != null && !delta.isEmpty()) {
                    out.add(roleChunkIfNeeded());
                    out.add(ResponsesToChat.reasoningChunk(chatId, created, model, delta));
                }
                break;
            }

            case "response.reasoning_summary_part.added":
            case "response.reasoning_summary_part.done":
            case "response.reasoning_text.done":
            case "response.reasoning_summary_text.done":
            case "response.content_part.done": {
                // Summaries only; no chat-level effect beyond the deltas above.
                break;
            }

            case "response.function_call_arguments.delta": {
                int idx = event.path("output_index").asInt(0);
                String delta = Jsons.str(event, "delta");
                if (delta == null || delta.isEmpty()) break;

                // Track state even if the added event was missed by the upstream.
                argsByOutputIndex.computeIfAbsent(idx, k -> new StringBuilder()).append(delta);
                if (!announcedToolCalls.containsKey(idx)) {
                    String callId = Jsons.str(event, "call_id");
                    if (callId == null) callId = Jsons.str(event, "item_id");
                    int toolIndex = nextToolIndex++;
                    toolIndexByOutputIndex.put(idx, toolIndex);
                    callIdByOutputIndex.put(idx, callId);
                    announcedToolCalls.put(idx, Boolean.TRUE);
                    out.add(roleChunkIfNeeded());
                    out.add(ResponsesToChat.toolCallChunk(chatId, created, model,
                            toolIndex, callId, Jsons.str(event, "name"), delta));
                } else {
                    out.add(ResponsesToChat.toolCallChunk(chatId, created, model,
                            toolIndexOf(idx), null, null, delta));
                }
                break;
            }

            case "response.function_call_arguments.done": {
                int idx = event.path("output_index").asInt(0);
                String full = Jsons.str(event, "arguments");
                StringBuilder sb = argsByOutputIndex.computeIfAbsent(idx, k -> new StringBuilder());
                if (full != null && full.length() > sb.length()) {
                    String remainder = full.substring(sb.length());
                    sb.append(remainder);
                    out.add(ResponsesToChat.toolCallChunk(chatId, created, model,
                            toolIndexOf(idx), null, null, remainder));
                }
                break;
            }

            case "response.completed":
            case "response.incomplete": {
                captureMeta(payload);
                status = Jsons.str(payload, "status", type.equals("response.incomplete") ? "incomplete" : "completed");
                JsonNode details = payload.get("incomplete_details");
                if (details != null && details.isObject()) {
                    incompleteReason = Jsons.str(details, "reason");
                }
                usage = payload.get("usage");
                String text = Jsons.str(payload, "output_text");
                if (text != null && finalText == null) finalText = text;
                break;
            }

            case "response.failed": {
                captureMeta(payload);
                status = "failed";
                error = payload.get("error");
                if (error == null && payload.has("incomplete_details")) {
                    error = payload.get("incomplete_details");
                }
                break;
            }

            case "error": {
                // Stream-level failure: surface it and let the caller emit it.
                status = "failed";
                error = event;
                break;
            }

            default:
                // Unknown/newer events are ignored so the proxy keeps working as the
                // upstream API grows.
                if (Log.debugEnabled()) Log.debug("ignoring responses event: " + type);
                break;
        }
        // roleChunkIfNeeded() returns null once the role chunk has been sent, so
        // strip nulls before handing the list back.
        out.removeIf(java.util.Objects::isNull);
        return out;
    }

    /**
     * Produces the terminal chat chunk. Idempotent: repeated calls after the first
     * return null.
     */
    public ObjectNode finish() {
        if (finishSent) return null;
        finishSent = true;

        String reason;
        if (error != null) {
            reason = "stop";
        } else if (!toolIndexByOutputIndex.isEmpty()) {
            reason = "tool_calls";
        } else if ("incomplete".equals(status)) {
            reason = "max_output_tokens".equals(incompleteReason) ? "length" : "stop";
        } else {
            reason = "stop";
        }

        // If nothing was ever emitted but the final event carries text, send it now
        // so the client is not left with an empty answer.
        JsonNode usageForChunk = includeUsage ? usage : null;
        return ResponsesToChat.finishChunk(chatId, created, model, reason, usageForChunk);
    }

    /** Text the upstream reported only on its terminal event, if any. */
    public String finalText() {
        return finalText;
    }

    public String status() {
        return status;
    }

    public boolean hasAnnouncedToolCalls() {
        return !toolIndexByOutputIndex.isEmpty();
    }

    // ------------------------------------------------------------------ internals

    private void captureMeta(JsonNode payload) {
        if (payload == null || !payload.isObject()) return;
        JsonNode u = payload.get("usage");
        if (u != null && !u.isNull()) usage = u;
        String s = Jsons.str(payload, "status");
        if (s != null) status = s;
        JsonNode e = payload.get("error");
        if (e != null && !e.isNull()) error = e;
    }

    private int toolIndexOf(int outputIndex) {
        Integer v = toolIndexByOutputIndex.get(outputIndex);
        return v == null ? 0 : v;
    }

    /** Emits the role chunk lazily, only when the first real delta shows up. */
    private ObjectNode roleChunkIfNeeded() {
        if (roleSent) return null;
        roleSent = true;
        return ResponsesToChat.roleChunk(chatId, created, model);
    }
}