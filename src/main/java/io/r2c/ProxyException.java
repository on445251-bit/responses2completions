package io.r2c;

import com.fasterxml.jackson.databind.JsonNode;
import io.r2c.util.Jsons;

/** HTTP-level failure that should be rendered back to the caller in OpenAI error shape. */
public final class ProxyException extends RuntimeException {

    private final int status;
    private final String type;
    private final String code;

    public ProxyException(int status, String type, String code, String message) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
    }

    public ProxyException(int status, String message) {
        this(status, status >= 500 ? "server_error" : "invalid_request_error", null, message);
    }

    public int status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String code() {
        return code;
    }

    /** Builds the OpenAI-style {@code {"error": {...}}} body. */
    public String toErrorJson() {
        var root = Jsons.obj();
        var err = root.putObject("error");
        err.put("message", getMessage());
        err.put("type", type);
        if (code != null) err.put("code", code);
        else err.putNull("code");
        return Jsons.write(root);
    }

    /** Translates an upstream error response into a client-facing exception. */
    public static ProxyException fromUpstream(int status, String body) {
        String message = body;
        String type = "upstream_error";
        String code = null;

        JsonNode parsed = Jsons.parseOrNull(body);
        if (parsed != null && parsed.has("error")) {
            JsonNode err = parsed.get("error");
            if (err.isTextual()) {
                message = err.asText();
            } else {
                message = Jsons.str(err, "message", body);
                type = Jsons.str(err, "type", type);
                code = Jsons.str(err, "code");
            }
        } else if (parsed == null || body == null || body.isBlank()) {
            message = "upstream returned HTTP " + status + " with an empty body";
        }
        if (message == null || message.isBlank()) {
            message = "upstream returned HTTP " + status;
        }
        // Keep the upstream status so clients can react correctly (401/429/400...).
        return new ProxyException(status, type, code, message);
    }
}