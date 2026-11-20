package io.r2c.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.r2c.util.Jsons;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Converts a Responses API response object into a Chat Completions response
 * object.
 *
 * <p>A Responses payload exposes an {@code output} array of typed items
 * ({@code reasoning}, {@code message}, {@code function_call}, ...), while a chat
 * response exposes a single {@code choices[0].message} with
 * {@code content} + {@code tool_calls}. This class flattens the former into the
 * latter, including usage and finish-reason mapping.
 */
public final class ResponsesToChat {

    /** Accumulates the meaningful parts of a Responses result (streamed or not). */
    public static final class Accumulator {
        public final StringBuilder text = new StringBuilder();
        public final StringBuilder refusal = new StringBuilder();
        public final StringBuilder reasoning = new StringBuilder();
        /** Tool calls keyed by output_index, in call order. */
        public final java.util.TreeMap<Integer, ToolCallBuilder> toolCalls = new java.util.TreeMap<>();

        public String responseId;
        public String model;
        public long createdAt;
        public String status;
        public String incompleteReason;
        public JsonNode usage;
        public JsonNode error;

        /** True when the model produced a function call rather than plain text. */
        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }

        public String finishReason() {
            if (error != null) return null;
            // Tool calls win: a chat client must execute them before continuing.
            if (hasToolCalls()) return "tool_calls";
            if (incompleteReason != null) {
                switch (incompleteReason) {
                    case "max_output_tokens":
                        return "length";
                    case "content_filter":
                        return "content_filter";
                    default:
                        break;
                }
            }
            if (refusal.length() > 0 && text.length() == 0) return "content_filter";
            return "stop";
        }
    }

    /** Mutable holder for one function call being assembled. */
    public static final class ToolCallBuilder {
        public final int index;
        public String callId;
        public String name;
        public final StringBuilder arguments = new StringBuilder();

        public ToolCallBuilder(int index) {
            this.index = index;
        }
    }

    private ResponsesToChat() {}

    /** Unique id generator for chat-style {@code chatcmpl-} identifiers. */
    public static String newChatCompletionId() {
        return "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    /**
     * Builds a non-streaming chat completion response from a full Responses body.
     */
    public static ObjectNode toChatCompletion(JsonNode responses, String fallbackModel) {
        Accumulator acc = collect(responses);
        ObjectNode out = Jsons.obj();

        out.put("id", acc.responseId != null && acc.responseId.startsWith("chatcmpl-")
                ? acc.responseId
                : newChatCompletionId());
        out.put("object", "chat.completion");
        out.put("created", acc.createdAt > 0 ? acc.createdAt : Instant.now().getEpochSecond());
        out.put("model", acc.model != null ? acc.model : fallbackModel);

        ArrayNode choices = out.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");

        String text = acc.text.toString();
        // Chat clients expect null content when only tool calls are present.
        if (text.isEmpty() && acc.hasToolCalls()) {
            message.putNull("content");
        } else {
            message.put("content", text);
        }

        if (acc.refusal.length() > 0) {
            message.put("refusal", acc.refusal.toString());
        }
        if (acc.reasoning.length() > 0) {
            message.put("reasoning_content", acc.reasoning.toString());
        }

        if (acc.hasToolCalls()) {
            ArrayNode calls = message.putArray("tool_calls");
            for (ToolCallBuilder b : acc.toolCalls.values()) {
                ObjectNode call = calls.addObject();
                call.put("id", b.callId == null ? "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20) : b.callId);
                call.put("type", "function");
                ObjectNode fn = call.putObject("function");
                fn.put("name", b.name == null ? "" : b.name);
                fn.put("arguments", b.arguments.length() == 0 ? "{}" : b.arguments.toString());
            }
        }

        String finish = acc.finishReason();
        if (finish == null) finish = "stop";
        choice.put("finish_reason", finish);
        choice.putNull("logprobs");

        if (acc.usage != null && !acc.usage.isNull()) {
            out.set("usage", convertUsage(acc.usage));
        } else {
            out.set("usage", zeroUsage());
        }

        return out;
    }

    /**
     * Builds the {@code chat.completion.chunk} carrying the assistant role, which
     * chat clients expect as the very first streamed event.
     */
    public static ObjectNode roleChunk(String id, long created, String model) {
        ObjectNode out = Jsons.obj();
        out.put("id", id);
        out.put("object", "chat.completion.chunk");
        out.put("created", created);
        out.put("model", model);
        ArrayNode choices = out.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        delta.put("role", "assistant");
        delta.put("content", "");
        choice.putNull("finish_reason");
        choice.putNull("logprobs");
        return out;
    }

    /** Builds a chunk carrying a content delta. */
    public static ObjectNode contentChunk(String id, long created, String model, String text) {
        ObjectNode out = baseChunk(id, created, model);
        ObjectNode choice = out.withArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        delta.put("content", text);
        choice.putNull("finish_reason");
        choice.putNull("logprobs");
        return out;
    }

    /** Builds a chunk carrying a reasoning delta (served as {@code reasoning_content}). */
    public static ObjectNode reasoningChunk(String id, long created, String model, String text) {
        ObjectNode out = baseChunk(id, created, model);
        ObjectNode choice = out.withArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        delta.put("reasoning_content", text);
        choice.putNull("finish_reason");
        choice.putNull("logprobs");
        return out;
    }

    /** Builds a chunk carrying a partial tool call. */
    public static ObjectNode toolCallChunk(String id, long created, String model, int toolIndex,
                                           String callId, String name, String argumentsFragment) {
        ObjectNode out = baseChunk(id, created, model);
        ObjectNode choice = out.withArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        ArrayNode calls = delta.putArray("tool_calls");
        ObjectNode call = calls.addObject();
        call.put("index", toolIndex);
        if (callId != null) call.put("id", callId);
        if (name != null) {
            call.put("type", "function");
            call.putObject("function").put("name", name);
        }
        if (argumentsFragment != null) {
            ObjectNode fn = call.has("function")
                    ? (ObjectNode) call.get("function")
                    : call.putObject("function");
            fn.put("arguments", argumentsFragment);
        }
        choice.putNull("finish_reason");
        choice.putNull("logprobs");
        return out;
    }

    /** Builds the terminal chunk with {@code finish_reason} and optional usage. */
    public static ObjectNode finishChunk(String id, long created, String model, String finishReason, JsonNode usage) {
        ObjectNode out = baseChunk(id, created, model);
        ObjectNode choice = out.withArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("delta");
        choice.put("finish_reason", finishReason);
        choice.putNull("logprobs");
        if (usage != null && !usage.isNull()) {
            out.set("usage", convertUsage(usage));
        }
        return out;
    }

    private static ObjectNode baseChunk(String id, long created, String model) {
        ObjectNode out = Jsons.obj();
        out.put("id", id);
        out.put("object", "chat.completion.chunk");
        out.put("created", created);
        out.put("model", model);
        return out;
    }

    // ------------------------------------------------------------------- parsing

    /** Walks a full Responses body and fills an accumulator. */
    public static Accumulator collect(JsonNode responses) {
        Accumulator acc = new Accumulator();
        if (responses == null || !responses.isObject()) return acc;

        acc.responseId = Jsons.str(responses, "id");
        acc.model = Jsons.str(responses, "model");
        acc.createdAt = responses.path("created_at").asLong(0L);
        acc.status = Jsons.str(responses, "status");
        JsonNode incomplete = responses.get("incomplete_details");
        if (incomplete != null && incomplete.isObject()) {
            acc.incompleteReason = Jsons.str(incomplete, "reason");
        }
        acc.usage = responses.get("usage");
        acc.error = responses.get("error");

        // Text convenience field: present when the upstream already flattened output.
        String outputText = Jsons.str(responses, "output_text");

        JsonNode output = responses.get("output");
        if (output != null && output.isArray()) {
            int toolIndex = 0;
            for (JsonNode item : output) {
                if (item == null || !item.isObject()) continue;
                String type = Jsons.str(item, "type", "message");
                switch (type) {
                    case "message": {
                        JsonNode content = item.get("content");
                        if (content != null && content.isArray()) {
                            for (JsonNode part : content) {
                                String pt = Jsons.str(part, "type", "output_text");
                                if (pt.equals("output_text") || pt.equals("text")) {
                                    String t = Jsons.str(part, "text");
                                    if (t != null) acc.text.append(t);
                                } else if (pt.equals("refusal")) {
                                    String r = Jsons.str(part, "refusal");
                                    if (r != null) acc.refusal.append(r);
                                }
                            }
                        } else if (content != null && content.isTextual()) {
                            acc.text.append(content.asText());
                        }
                        break;
                    }
                    case "function_call": {
                        ToolCallBuilder b = new ToolCallBuilder(toolIndex++);
                        b.callId = Jsons.str(item, "call_id");
                        b.name = Jsons.str(item, "name");
                        String args = Jsons.str(item, "arguments");
                        if (args != null) b.arguments.append(args);
                        acc.toolCalls.put(b.index, b);
                        break;
                    }
                    case "reasoning": {
                        JsonNode summary = item.get("summary");
                        if (summary != null && summary.isArray()) {
                            for (JsonNode s : summary) {
                                String t = Jsons.str(s, "text");
                                if (t != null) acc.reasoning.append(t);
                            }
                        }
                        String c = Jsons.textOf(item.get("content"));
                        if (c != null && acc.reasoning.length() == 0) acc.reasoning.append(c);
                        break;
                    }
                    default:
                        // Hosted tool items (web_search_call, file_search_call, ...)
                        // have no chat equivalent; ignore their payloads.
                        break;
                }
            }
        } else if (outputText != null) {
            acc.text.append(outputText);
        }

        // Some gateways only fill output_text; prefer it when output was empty.
        if (acc.text.length() == 0 && outputText != null && !outputText.isEmpty()) {
            acc.text.append(outputText);
        }
        return acc;
    }

    /** Maps Responses usage onto chat usage field names. */
    public static ObjectNode convertUsage(JsonNode usage) {
        ObjectNode out = Jsons.obj();
        if (usage == null || usage.isNull()) return zeroUsage();

        int prompt = usage.path("input_tokens").asInt(0);
        int completion = usage.path("output_tokens").asInt(0);
        int total = usage.path("total_tokens").asInt(prompt + completion);

        out.put("prompt_tokens", prompt);
        out.put("completion_tokens", completion);
        out.put("total_tokens", total);

        ObjectNode promptDetails = out.putObject("prompt_tokens_details");
        int cached = usage.path("input_tokens_details").path("cached_tokens").asInt(0);
        promptDetails.put("cached_tokens", cached);
        promptDetails.put("audio_tokens", 0);

        ObjectNode completionDetails = out.putObject("completion_tokens_details");
        int reasoning = usage.path("output_tokens_details").path("reasoning_tokens").asInt(0);
        completionDetails.put("reasoning_tokens", reasoning);
        completionDetails.put("audio_tokens", 0);
        completionDetails.put("accepted_prediction_tokens", 0);
        completionDetails.put("rejected_prediction_tokens", 0);

        return out;
    }

    public static ObjectNode zeroUsage() {
        ObjectNode out = Jsons.obj();
        out.put("prompt_tokens", 0);
        out.put("completion_tokens", 0);
        out.put("total_tokens", 0);
        return out;
    }

    /**
     * Extracts the response id from a Responses stream event so chunks can keep a
     * stable id even if the client disconnects mid-stream.
     */
    public static String normalizeModelId(String model) {
        return model == null ? "" : model.toLowerCase(Locale.ROOT);
    }
}