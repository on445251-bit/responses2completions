package io.r2c.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Thin wrapper around Jackson so the rest of the code stays terse. */
public final class Jsons {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Jsons() {}

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode arr() {
        return MAPPER.createArrayNode();
    }

    public static JsonNode parse(String text) throws JsonProcessingException {
        return MAPPER.readTree(text);
    }

    public static JsonNode parseOrNull(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize json", e);
        }
    }

    /** Reads a string field, returning {@code null} when absent or a JSON null. */
    public static String str(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.asText();
    }

    public static String str(JsonNode node, String field, String fallback) {
        String v = str(node, field);
        return v == null ? fallback : v;
    }

    /** Reads text from either a plain JSON string or a content-part array. */
    public static String textOf(JsonNode content) {
        if (content == null || content.isNull()) return null;
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part == null || part.isNull()) continue;
                String type = str(part, "type", "");
                if (type.endsWith("_text") || type.equals("text") || type.equals("input_text")
                        || type.equals("output_text") || type.isEmpty()) {
                    String t = str(part, "text");
                    if (t != null) sb.append(t);
                }
            }
            return sb.toString();
        }
        return null;
    }
}