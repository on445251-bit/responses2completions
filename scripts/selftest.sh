#!/usr/bin/env bash
# Self test: boots the proxy against a stub upstream and checks that both the
# request direction (chat -> responses) and the response direction
# (responses -> chat, streaming and not) are translated correctly.
#
# Usage: ./scripts/selftest.sh [path-to-jar]
set -uo pipefail

JAR="${1:-target/responses2completions.jar}"
PROXY_PORT="${PROXY_PORT:-18080}"
STUB_PORT="${STUB_PORT:-18081}"
WORKDIR="$(mktemp -d)"
PASS=0
FAIL=0

cleanup() {
  [ -n "${PROXY_PID:-}" ] && kill "$PROXY_PID" 2>/dev/null
  [ -n "${STUB_PID:-}" ] && kill "$STUB_PID" 2>/dev/null
  wait 2>/dev/null
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

ok()   { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; [ -n "${2:-}" ] && printf '       %s\n' "$2"; }
check() { # check <description> <haystack> <needle>
  if printf '%s' "$2" | grep -qF -- "$3"; then ok "$1"; else bad "$1" "missing: $3"; fi
}
check_not() {
  if printf '%s' "$2" | grep -qF -- "$3"; then bad "$1" "unexpected: $3"; else ok "$1"; fi
}

if [ ! -f "$JAR" ]; then
  echo "jar not found: $JAR" >&2
  exit 1
fi

# ----------------------------------------------------------------- stub upstream
# A tiny Python server that speaks the Responses API. It also records the last
# request body so the test can assert the request-side translation.
cat > "$WORKDIR/stub.py" <<'PY'
import json, sys, threading
from http.server import BaseHTTPRequestHandler, HTTPServer

LAST = {"body": None}

NON_STREAM = {
    "id": "resp_abc123", "object": "response", "created_at": 1700000000,
    "status": "completed", "model": "gpt-4.1-test",
    "output": [
        {"type": "reasoning", "id": "rs_1",
         "summary": [{"type": "summary_text", "text": "thinking..."}]},
        {"type": "message", "id": "msg_1", "role": "assistant",
         "content": [{"type": "output_text", "text": "Hello from Responses!"}]},
    ],
    "output_text": "Hello from Responses!",
    "usage": {"input_tokens": 11, "output_tokens": 7, "total_tokens": 18,
              "input_tokens_details": {"cached_tokens": 3},
              "output_tokens_details": {"reasoning_tokens": 2}},
}

TOOL_STREAM = [
    {"type": "response.created", "response": {"id": "resp_tool", "status": "in_progress",
                                              "model": "gpt-4.1-test", "created_at": 1700000001}},
    {"type": "response.output_item.added", "output_index": 0,
     "item": {"type": "function_call", "id": "fc_1", "call_id": "call_xyz",
              "name": "get_weather", "arguments": ""}},
    {"type": "response.function_call_arguments.delta", "output_index": 0,
     "delta": "{\"city\":"},
    {"type": "response.function_call_arguments.delta", "output_index": 0,
     "delta": "\"Paris\"}"},
    {"type": "response.function_call_arguments.done", "output_index": 0,
     "arguments": "{\"city\":\"Paris\"}"},
    {"type": "response.completed",
     "response": {"id": "resp_tool", "status": "completed", "model": "gpt-4.1-test",
                  "created_at": 1700000001,
                  "usage": {"input_tokens": 5, "output_tokens": 9, "total_tokens": 14}}},
]

TEXT_STREAM = [
    {"type": "response.created", "response": {"id": "resp_txt", "status": "in_progress",
                                              "model": "gpt-4.1-test", "created_at": 1700000002}},
    {"type": "response.content_part.added", "output_index": 0},
    {"type": "response.output_text.delta", "output_index": 0, "delta": "Hel"},
    {"type": "response.output_text.delta", "output_index": 0, "delta": "lo"},
    {"type": "response.output_text.done", "output_index": 0, "text": "Hello"},
    {"type": "response.completed",
     "response": {"id": "resp_txt", "status": "completed", "model": "gpt-4.1-test",
                  "created_at": 1700000002,
                  "usage": {"input_tokens": 4, "output_tokens": 2, "total_tokens": 6}}},
]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.endswith("/__last"):
            # Compact separators: the proxy serializes without spaces, so the
            # assertions can match on exact substrings like "key":"value".
            body = json.dumps(LAST["body"] or {}, separators=(",", ":")).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path.endswith("/models"):
            body = json.dumps({"object": "list",
                               "data": [{"id": "gpt-4.1-test", "object": "model"}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_error(404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8")
        try:
            LAST["body"] = json.loads(raw)
        except Exception:
            LAST["body"] = {"__raw__": raw}

        if self.path.endswith("/__last"):
            body = json.dumps(LAST["body"]).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        req = LAST["body"] or {}
        if req.get("stream"):
            text = raw
            if "TOOLTEST" in text:
                events = TOOL_STREAM
            else:
                events = TEXT_STREAM
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            for ev in events:
                self.wfile.write(("event: %s\ndata: %s\n\n" % (ev["type"], json.dumps(ev))).encode())
                self.wfile.flush()
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            return

        body = json.dumps(NON_STREAM).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


HTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
PY

python3 "$WORKDIR/stub.py" "$STUB_PORT" &
STUB_PID=$!

# ------------------------------------------------------------------------ proxy
R2C_PORT="$PROXY_PORT" \
R2C_BIND=127.0.0.1 \
R2C_UPSTREAM_BASE_URL="http://127.0.0.1:$STUB_PORT/v1" \
R2C_UPSTREAM_API_KEY=test-key \
R2C_PASS_THROUGH_CLIENT_AUTH=false \
R2C_LOG_LEVEL=info \
  java -jar "$JAR" > "$WORKDIR/proxy.log" 2>&1 &
PROXY_PID=$!

for _ in $(seq 1 50); do
  if curl -sf "http://127.0.0.1:$PROXY_PORT/health" >/dev/null 2>&1; then break; fi
  sleep 0.2
done

if ! curl -sf "http://127.0.0.1:$PROXY_PORT/health" >/dev/null 2>&1; then
  echo "proxy failed to start; log:" >&2
  cat "$WORKDIR/proxy.log" >&2
  exit 1
fi

BASE="http://127.0.0.1:$PROXY_PORT"
echo "== health =="
check "health returns ok" "$(curl -s "$BASE/health")" '"status":"ok"'

# ------------------------------------------------------- non-streaming roundtrip
echo "== non-streaming: chat -> responses -> chat =="
REQ='{"model":"my-model","messages":[{"role":"system","content":"Be brief."},{"role":"user","content":"Hi there"}],"max_tokens":64,"temperature":0.3,"response_format":{"type":"json_schema","json_schema":{"name":"out","schema":{"type":"object"}}}}'
RESP=$(curl -s -X POST "$BASE/v1/chat/completions" -H 'Content-Type: application/json' -d "$REQ")

check "object is chat.completion" "$RESP" '"object":"chat.completion"'
check "content translated" "$RESP" 'Hello from Responses!'
check "finish_reason stop" "$RESP" '"finish_reason":"stop"'
check "usage remapped to prompt_tokens" "$RESP" '"prompt_tokens":11'
check "usage remapped to completion_tokens" "$RESP" '"completion_tokens":7'
check "reasoning surfaced" "$RESP" 'thinking...'
check "cached tokens carried" "$RESP" '"cached_tokens":3'

# Verify what the proxy actually sent upstream. The stub records the previous
# request, so query it with GET (a POST to /__last would overwrite the record).
UP=$(curl -s "http://127.0.0.1:$STUB_PORT/v1/__last")
echo "== request translation (chat -> responses) =="
check "system became instructions" "$UP" '"instructions":"Be brief."'
check "max_tokens became max_output_tokens" "$UP" '"max_output_tokens":64'
check "temperature forwarded" "$UP" '"temperature":0.3'
check "json_schema nested into text.format" "$UP" '"name":"out"'
check "schema object preserved" "$UP" '"schema":{"type":"object"}'
check_not "chat-only stop not forwarded" "$UP" '"stop":'

# ------------------------------------------------------------ streaming: text
echo "== streaming: text deltas =="
STREAM=$(curl -s -N -X POST "$BASE/v1/chat/completions" -H 'Content-Type: application/json' \
  -d '{"model":"m","stream":true,"messages":[{"role":"user","content":"Hi"}]}')

check "first chunk carries role" "$STREAM" '"role":"assistant"'
check "text delta Hel" "$STREAM" '"content":"Hel"'
check "text delta lo" "$STREAM" '"content":"lo"'
check "finish_reason stop" "$STREAM" '"finish_reason":"stop"'
check "usage on final chunk" "$STREAM" '"total_tokens":6'
check "stream terminated with [DONE]" "$STREAM" 'data: [DONE]'
check_not "no duplicate role chunk" "$(printf '%s' "$STREAM" | grep -c '"role":"assistant"')" '2'

# ------------------------------------------------------ streaming: tool calling
echo "== streaming: function call =="
TOOL=$(curl -s -N -X POST "$BASE/v1/chat/completions" -H 'Content-Type: application/json' \
  -d '{"model":"m","stream":true,"messages":[{"role":"user","content":"TOOLTEST please"}]}')

check "tool call id" "$TOOL" '"id":"call_xyz"'
check "tool call name" "$TOOL" '"name":"get_weather"'
check "tool args fragment 1" "$TOOL" '"arguments":"{\"city\":"'
check "tool args fragment 2" "$TOOL" '"arguments":"\"Paris\"}"'
check "finish_reason tool_calls" "$TOOL" '"finish_reason":"tool_calls"'
check_not "tool arguments not duplicated" "$TOOL" '{\"city\":\"Paris\"}{\"city\":\"Paris\"}'

# ------------------------------------------------------ error handling / routes
echo "== routing and auth =="
check "models passthrough" "$(curl -s "$BASE/v1/models")" 'gpt-4.1-test'
check "unknown route is 404" "$(curl -s "$BASE/nope")" 'unknown route'
check "GET on completions is 405" "$(curl -s "$BASE/v1/chat/completions")" 'method not allowed'
check "invalid json rejected" \
  "$(curl -s -X POST "$BASE/v1/chat/completions" -H 'Content-Type: application/json' -d 'not json')" \
  'not valid JSON'

echo
echo "---- summary: $PASS passed, $FAIL failed ----"
if [ "$FAIL" -ne 0 ]; then
  echo "proxy log:" >&2
  tail -40 "$WORKDIR/proxy.log" >&2
  exit 1
fi