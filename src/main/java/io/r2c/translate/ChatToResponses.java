package io.r2c.translate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.r2c.Config;
import io.r2c.ProxyException;
import io.r2c.util.Jsons;
import io.r2c.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Converts an OpenAI <em>Chat Completions</em> request body into an OpenAI
 * <em>Responses</em> request body.
 *
 * <p>The two APIs differ in more than field names: chat messages are a flat list
 * where tool traffic lives inside {@code assistant.tool_calls} and {@code role=tool}
 * messages, while the Responses API models the same thing as a sequence of typed
 * input items ({@code function_call}, {@code function_call_output}). Tool
 * definitions are also flattened (no nested {@code function} object) and sampling
 * knobs such as {@code presence_penalty} simply do not exist upstream.
 */
public final class ChatToResponses {

    private final Config config;

    public ChatToResponses(Config config) {
        this.config = config;
    }

    public ObjectNode translate(JsonNode chat) {
        if (chat == null || !chat.isObject()) {
            throw new ProxyException(400, "request body must be a JSON object");
        }
        ObjectNode out = Jsons.obj();

        String model = config.mapModel(Jsons.str(chat, "model"));
        out.put("model", model);

        // ---- messages -> instructions + input items -----------------------
        List<String> instructions = new ArrayList<>();
        ArrayNode input = Jsons.arr();
        convertMessages(chat.get("messages"), instructions, input);
        if (!instructions.isEmpty()) {
            out.put("instructions", String.join("\n\n", instructions));
        }
        if (input.isEmpty()) {
            // Responses rejects an empty input; send a minimal placeholder so the
            // upstream returns a normal validation error instead of a confusing 500.
            ObjectNode item = Jsons.obj();
            item.put("role", "user");
            item.putArray("content").addObject().put("type", "input_text").put("text", "");
            input.add(item);
        }
        out.set("input", input);

        // ---- streaming -----------------------------------------------------
        boolean stream = chat.path("stream").asBoolean(false);
        if (stream) {
            out.put("stream", true);
            // Chat clients expect a final usage chunk only when they ask for it;
            // the translator tracks this itself, so never request it upstream.
        }

        // ---- limits --------------------------------------------------------
        Integer maxTokens = firstInt(chat, "max_completion_tokens", "max_tokens");
        if (maxTokens != null && maxTokens > 0) {
            out.put("max_output_tokens", maxTokens);
        }

        // ---- sampling ------------------------------------------------------
        copyNumber(chat, out, "temperature");
        copyNumber(chat, out, "top_p");
        copyBool(chat, out, "parallel_tool_calls");

        // ---- reasoning -----------------------------------------------------
        String effort = Jsons.str(chat, "reasoning_effort");
        if (effort != null && !effort.isBlank()) {
            out.putObject("reasoning").put("effort", normalizeEffort(effort));
        }
        JsonNode reasoning = chat.get("reasoning");
        if (reasoning != null && reasoning.isObject() && !out.has("reasoning")) {
            out.set("reasoning", reasoning.deepCopy());
        }

        // ---- structured output --------------------------------------------
        convertResponseFormat(chat.get("response_format"), out);

        // ---- tools ---------------------------------------------------------
        convertTools(chat.get("tools"), out);
        convertToolChoice(chat.get("tool_choice"), out);

        // ---- misc passthrough ---------------------------------------------
        copyText(chat, out, "user");
        copyText(chat, out, "prompt_cache_key");
        if (chat.path("store").isBoolean()) {
            out.put("store", chat.get("store").asBoolean());
        }
        JsonNode metadata = chat.get("metadata");
        if (metadata != null && metadata.isObject()) {
            out.set("metadata", metadata.deepCopy());
        }

        // ---- unsupported knobs --------------------------------------------
        // stop / presence_penalty / frequency_penalty / seed / n / logprobs have no
        // Responses equivalent. Dropping them is safe; failing would break clients
        // that send defaults.
        if (config.passStop && chat.hasNonNull("stop")) {
            JsonNode stop = chat.get("stop");
            ArrayNode stopSeq = Jsons.arr();
            if (stop.isArray()) {
                stop.forEach(s -> stopSeq.add(s.asText()));
            } else {
                stopSeq.add(stop.asText());
            }
            // Not part of the spec, but some compatible gateways honour it.
            out.set("stop", stopSeq);
        }
        warnUnsupported(chat);

        return out;
    }

    // ------------------------------------------------------------------ messages

    private void convertMessages(JsonNode messages, List<String> instructions, ArrayNode input) {
        if (messages == null || !messages.isArray()) return;

        for (JsonNode msg : messages) {
            if (msg == null || !msg.isObject()) continue;
            String role = Jsons.str(msg, "role", "user").toLowerCase(Locale.ROOT);

            switch (role) {
                case "system":
                case "developer": {
                    String text = Jsons.textOf(msg.get("content"));
                    if (text != null && !text.isEmpty()) instructions.add(text);
                    break;
                }
                case "tool": {
                    String callId = Jsons.str(msg, "tool_call_id");
                    String text = Jsons.textOf(msg.get("content"));
                    if (callId == null || callId.isBlank()) {
                        Log.warn("tool message without tool_call_id, emitting as user text");
                        addUserText(input, text == null ? "" : text);
                        break;
                    }
                    ObjectNode item = Jsons.obj();
                    item.put("type", "function_call_output");
                    item.put("call_id", callId);
                    item.put("output", text == null ? "" : text);
                    input.add(item);
                    break;
                }
                case "assistant": {
                    convertAssistant(msg, input);
                    break;
                }
                case "function": {
                    // Legacy chat "function" role: map onto function_call_output.
                    String text = Jsons.textOf(msg.get("content"));
                    ObjectNode item = Jsons.obj();
                    item.put("type", "function_call_output");
                    item.put("call_id", Jsons.str(msg, "name", "call_legacy"));
                    item.put("output", text == null ? "" : text);
                    input.add(item);
                    break;
                }
                default: {
                    convertUserLike(msg, role, input);
                    break;
                }
            }
        }
    }

    private void convertAssistant(JsonNode msg, ArrayNode input) {
        // Assistant text becomes an output_text message item.
        String text = Jsons.textOf(msg.get("content"));
        if (text != null && !text.isEmpty()) {
            ObjectNode item = Jsons.obj();
            item.put("type", "message");
            item.put("role", "assistant");
            ArrayNode content = item.putArray("content");
            content.addObject().put("type", "output_text").put("text", text);
            input.add(item);
        }

        // Historical tool calls become function_call items so the model can see
        // what it already asked for and what came back.
        JsonNode toolCalls = msg.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                JsonNode fn = call.path("function");
                String name = Jsons.str(fn, "name");
                if (name == null || name.isBlank()) continue;

                ObjectNode item = Jsons.obj();
                item.put("type", "function_call");
                item.put("call_id", Jsons.str(call, "id", "call_" + Math.abs(name.hashCode())));
                item.put("name", name);
                String args = Jsons.str(fn, "arguments");
                item.put("arguments", args == null || args.isBlank() ? "{}" : args);
                input.add(item);
            }
        }
    }

    private void convertUserLike(JsonNode msg, String role, ArrayNode input) {
        ObjectNode item = Jsons.obj();
        String effectiveRole = role.equals("assistant") ? "assistant" : "user";
        item.put("role", effectiveRole);

        JsonNode content = msg.get("content");
        ArrayNode parts = item.putArray("content");

        if (content == null || content.isNull()) {
            parts.addObject().put("type", "input_text").put("text", "");
            input.add(item);
            return;
        }

        if (content.isTextual()) {
            parts.addObject().put("type", "input_text").put("text", content.asText());
            input.add(item);
            return;
        }

        if (content.isArray()) {
            for (JsonNode part : content) {
                String type = Jsons.str(part, "type", "text");
                switch (type) {
                    case "text":
                    case "input_text":
                        parts.addObject()
                                .put("type", "input_text")
                                .put("text", Jsons.str(part, "text", ""));
                        break;
                    case "image_url":
                    case "input_image": {
                        String url = extractImageUrl(part);
                        if (url == null) break;
                        ObjectNode img = Jsons.obj();
                        img.put("type", "input_image");
                        img.put("image_url", url);
                        String detail = extractImageDetail(part);
                        if (detail != null) img.put("detail", detail);
                        parts.add(img);
                        break;
                    }
                    case "input_file":
                    case "file": {
                        ObjectNode file = Jsons.obj();
                        file.put("type", "input_file");
                        String fileId = Jsons.str(part, "file_id");
                        String fileData = Jsons.str(part, "file_data");
                        String filename = Jsons.str(part, "filename");
                        if (fileId != null) file.put("file_id", fileId);
                        if (fileData != null) file.put("file_data", fileData);
                        if (filename != null) file.put("filename", filename);
                        if (file.size() > 0) parts.add(file);
                        break;
                    }
                    case "refusal":
                        parts.addObject()
                                .put("type", "input_text")
                                .put("text", Jsons.str(part, "refusal", ""));
                        break;
                    default:
                        // Unknown part: keep any text payload rather than losing it.
                        String t = Jsons.str(part, "text");
                        if (t != null) parts.addObject().put("type", "input_text").put("text", t);
                        break;
                }
            }
            if (parts.isEmpty()) {
                parts.addObject().put("type", "input_text").put("text", Jsons.textOf(content) == null
                        ? "" : Jsons.textOf(content));
            }
            input.add(item);
            return;
        }

        parts.addObject().put("type", "input_text").put("text", content.asText());
        input.add(item);
    }

    private void addUserText(ArrayNode input, String text) {
        ObjectNode item = Jsons.obj();
        item.put("role", "user");
        item.putArray("content").addObject().put("type", "input_text").put("text", text);
        input.add(item);
    }

    private static String extractImageUrl(JsonNode part) {
        JsonNode imageUrl = part.get("image_url");
        if (imageUrl == null || imageUrl.isNull()) return null;
        if (imageUrl.isTextual()) return imageUrl.asText();
        return Jsons.str(imageUrl, "url");
    }

    private static String extractImageDetail(JsonNode part) {
        JsonNode imageUrl = part.get("image_url");
        if (imageUrl != null && imageUrl.isObject()) {
            String detail = Jsons.str(imageUrl, "detail");
            if (detail != null) return detail;
        }
        return Jsons.str(part, "detail");
    }

    // --------------------------------------------------------------------- tools

    private void convertTools(JsonNode tools, ObjectNode out) {
        if (tools == null || !tools.isArray() || tools.isEmpty()) return;

        ArrayNode converted = Jsons.arr();
        for (JsonNode tool : tools) {
            if (tool == null || !tool.isObject()) continue;
            String type = Jsons.str(tool, "type", "function");

            if ("function".equals(type)) {
                JsonNode fn = tool.get("function");
                if (fn == null || !fn.isObject()) continue;

                ObjectNode t = Jsons.obj();
                t.put("type", "function");
                t.put("name", Jsons.str(fn, "name", ""));
                String desc = Jsons.str(fn, "description");
                if (desc != null) t.put("description", desc);
                JsonNode params = fn.get("parameters");
                if (params != null && params.isObject()) {
                    t.set("parameters", params.deepCopy());
                }
                JsonNode strict = fn.get("strict");
                if (strict != null && strict.isBoolean()) t.put("strict", strict.asBoolean());
                converted.add(t);
            } else {
                // Built-in hosted tools (web_search, file_search, code_interpreter,
                // mcp...) already use the Responses shape, so pass them through.
                converted.add(tool.deepCopy());
            }
        }
        if (!converted.isEmpty()) out.set("tools", converted);
    }

    private void convertToolChoice(JsonNode toolChoice, ObjectNode out) {
        if (toolChoice == null || toolChoice.isNull()) return;

        if (toolChoice.isTextual()) {
            String v = toolChoice.asText();
            // auto / none / required are identical in both APIs.
            if (v.equals("auto") || v.equals("none") || v.equals("required")) {
                out.put("tool_choice", v);
            } else {
                // A bare function name means "call this one".
                ObjectNode choice = Jsons.obj();
                choice.put("type", "function");
                choice.put("name", v);
                out.set("tool_choice", choice);
            }
            return;
        }

        if (toolChoice.isObject()) {
            JsonNode fn = toolChoice.get("function");
            if (fn != null && fn.isObject()) {
                ObjectNode choice = Jsons.obj();
                choice.put("type", "function");
                choice.put("name", Jsons.str(fn, "name", ""));
                out.set("tool_choice", choice);
            } else if (toolChoice.has("type")) {
                out.set("tool_choice", toolChoice.deepCopy());
            }
        }
    }

    // ----------------------------------------------------------- response format

    private void convertResponseFormat(JsonNode format, ObjectNode out) {
        if (format == null || !format.isObject()) return;
        String type = Jsons.str(format, "type");
        if (type == null) return;

        ObjectNode text = out.putObject("text");
        ObjectNode fmt = text.putObject("format");

        switch (type) {
            case "text":
                fmt.put("type", "text");
                break;
            case "json_object":
                fmt.put("type", "json_object");
                break;
            case "json_schema": {
                JsonNode schema = format.get("json_schema");
                if (schema == null || !schema.isObject()) {
                    fmt.put("type", "json_object");
                    break;
                }
                fmt.put("type", "json_schema");
                String name = Jsons.str(schema, "name", "response");
                fmt.put("name", name);
                JsonNode s = schema.get("schema");
                if (s != null) fmt.set("schema", s.deepCopy());
                JsonNode strict = schema.get("strict");
                if (strict != null && strict.isBoolean()) fmt.put("strict", strict.asBoolean());
                String desc = Jsons.str(schema, "description");
                if (desc != null) fmt.put("description", desc);
                break;
            }
            default:
                fmt.put("type", type);
                break;
        }
    }

    // ------------------------------------------------------------------- helpers

    private static String normalizeEffort(String effort) {
        String v = effort.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "minimal":
            case "low":
            case "medium":
            case "high":
                return v;
            default:
                return "medium";
        }
    }

    private static void copyNumber(JsonNode from, ObjectNode to, String field) {
        JsonNode v = from.get(field);
        if (v != null && v.isNumber()) to.set(field, v.deepCopy());
    }

    private static void copyBool(JsonNode from, ObjectNode to, String field) {
        JsonNode v = from.get(field);
        if (v != null && v.isBoolean()) to.set(field, v.deepCopy());
    }

    private static void copyText(JsonNode from, ObjectNode to, String field) {
        String v = Jsons.str(from, field);
        if (v != null && !v.isBlank()) to.put(field, v);
    }

    private static Integer firstInt(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && v.canConvertToInt()) return v.asInt();
        }
        return null;
    }

    private static void warnUnsupported(JsonNode chat) {
        if (!Log.debugEnabled()) return;
        String[] dropped = {"presence_penalty", "frequency_penalty", "seed", "n",
                "logprobs", "top_logprobs", "logit_bias", "stream_options"};
        for (String f : dropped) {
            JsonNode v = chat.get(f);
            if (v == null || v.isNull()) continue;
            if (v.isNumber() && v.asDouble() == 0d) continue;
            Log.debug("dropping chat-only parameter: " + f + "=" + v);
        }
    }
}