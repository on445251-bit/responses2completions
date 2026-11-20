# responses2completions

把**只支持 Responses API 的上游**，伪装成**标准 Chat Completions API** 给客户端用。

> 典型场景：你手上的 API 站点只开了 `/v1/responses`（Codex / GPT-5 / o 系列那一套），
> 但你的客户端、SDK、工具只会调 `/v1/chat/completions`。这个代理插在中间做双向协议翻译。

```
你的工具 ──Chat Completions──> 本代理 ──Responses──> 你的 API 站点
          <──Chat Completions──         <──Responses──
```

## 特性

- **双向翻译**：请求 `messages → input/instructions`，响应 `output → message/tool_calls`
- **完整 SSE 流式**：逐事件转成 `chat.completion.chunk`，含 `role` / `content` / `tool_calls` / `finish_reason` / `usage`
- **函数调用（工具）**：`tools` 扁平化、`function_call_arguments.delta` 拼装、历史 `tool_calls` 回填为 `function_call` 项
- **推理模型**：`reasoning_effort` → `reasoning.effort`，思维链以 `reasoning_content` 回传
- **结构化输出**：`response_format.json_schema` → `text.format`
- **多模态**：图片 / 文件部分转换
- **usage 映射**：`input_tokens/output_tokens` → `prompt_tokens/completion_tokens`，含 cached / reasoning 明细
- **零依赖运行时**：纯 JDK（`HttpClient` + `com.sun.net.httpserver`），只有一个 jar
- **密钥透传**：客户端自带的 key 可透传，也可以由服务端兜底

## 快速开始

### 直接跑 jar

```bash
R2C_UPSTREAM_BASE_URL=https://your-site.example.com/v1 \
R2C_UPSTREAM_API_KEY=sk-xxxx \
java -jar responses2completions.jar
```

然后客户端这样配置：

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer anything" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-5",
    "messages": [{"role": "user", "content": "你好"}]
  }'
```

### Docker

```bash
docker build -t responses2completions .
docker run --rm -p 8080:8080 \
  -e R2C_UPSTREAM_BASE_URL=https://your-site.example.com/v1 \
  -e R2C_UPSTREAM_API_KEY=sk-xxxx \
  responses2completions
```

### 自己构建（无需本地环境）

仓库自带 GitHub Actions，推送即构建：

1. Actions 跑完在 **Artifacts** 下载 `responses2completions-jar`
2. 打 `v*` 标签（如 `v1.0.0`）会自动附到 Release

## 配置项

全部通过环境变量，都有默认值：

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `R2C_PORT` | `8080` | 监听端口 |
| `R2C_BIND` | `0.0.0.0` | 绑定地址 |
| `R2C_UPSTREAM_BASE_URL` | `https://api.openai.com/v1` | 上游 base，也可直接填 `/responses` 全路径 |
| `R2C_UPSTREAM_API_KEY` | 空 | 服务端兜底的 key |
| `R2C_PASS_THROUGH_CLIENT_AUTH` | `true` | 优先用客户端带来的 key |
| `R2C_API_KEYS` | 空 | 代理自身准入 key，逗号分隔；空=不校验 |
| `R2C_UPSTREAM_TIMEOUT_SECONDS` | `600` | 上游读超时（长回答留足） |
| `R2C_UPSTREAM_CONNECT_TIMEOUT_SECONDS` | `20` | 上游连接超时 |
| `R2C_MODEL_ALIASES` | 空 | 模型别名，如 `gpt-4o=gpt-4.1` |
| `R2C_DEFAULT_MODEL` | `gpt-4o-mini` | 客户端未传 model 时使用 |
| `R2C_ALWAYS_INCLUDE_USAGE` | `true` | 末块是否总带 usage |
| `R2C_UPSTREAM_HEADERS` | 空 | 附加头，`name=value;name=value` |
| `R2C_CORS` | `true` | 允许浏览器直连 |
| `R2C_UPSTREAM_USER_AGENT` | `codex_cli_rs/0.42.0` | 上游 User-Agent。**很多站点只允许编程客户端**，默认伪装成 Codex CLI |
| `R2C_LOG_LEVEL` | `info` | `error`/`warn`/`info`/`debug` |

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/v1/chat/completions` | 主入口，支持 `stream: true` |
| `GET` | `/v1/models` | 透传上游；不支持时返回合成列表 |
| `GET` | `/health` | 存活探针，不访问上游 |

## 协议映射

### 请求：Chat Completions → Responses

| Chat | Responses |
|---|---|
| `messages[role=system]` | `instructions`（合并） |
| `messages[role=user]` + 数组内容 | `input[].content[]`（`input_text` / `input_image` / `input_file`） |
| `messages[role=assistant].tool_calls` | `input[]` 的 `function_call` 项 |
| `messages[role=tool]` | `input[]` 的 `function_call_output` 项 |
| `max_tokens` / `max_completion_tokens` | `max_output_tokens` |
| `tools[].function{name,parameters}` | `tools[]{type,name,parameters}`（扁平） |
| `tool_choice.function` | `tool_choice{type,name}` |
| `response_format.json_schema` | `text.format{type,name,schema,strict}` |
| `reasoning_effort` | `reasoning.effort` |
| `user` / `metadata` / `store` | 同名透传 |
| `stop` / `presence_penalty` / `frequency_penalty` / `seed` / `logprobs` | 上游无对应，丢弃 |

### 响应：Responses → Chat Completions

| Responses | Chat |
|---|---|
| `output[type=message].content[output_text]` | `choices[0].message.content` |
| `output[type=function_call]` | `choices[0].message.tool_calls[]` |
| `output[type=reasoning]` | `choices[0].message.reasoning_content` |
| `incomplete_details.reason=max_output_tokens` | `finish_reason=length` |
| 有 tool call | `finish_reason=tool_calls` |
| `usage.input_tokens` | `usage.prompt_tokens` |
| `usage.output_tokens` | `usage.completion_tokens` |

### 流式事件

| Responses event | 产出 chunk |
|---|---|
| `response.created` | `{delta:{role:"assistant"}}` |
| `response.output_text.delta` | `{delta:{content:"..."}}` |
| `response.reasoning_summary_text.delta` | `{delta:{reasoning_content:"..."}}` |
| `response.output_item.added`（function_call） | `{delta:{tool_calls:[{id,name,index}]}}` |
| `response.function_call_arguments.delta` | `{delta:{tool_calls:[{arguments:"..."}]}}` |
| `response.completed` | `{finish_reason, usage}` 然后 `data: [DONE]` |
| `response.failed` / `error` | `{error:{...}}` 然后收尾 |

## 自测

`scripts/selftest.sh` 会拉起一个假上游，验证请求翻译、响应翻译、流式文本、流式工具调用、路由与错误处理：

```bash
mvn -B clean package
./scripts/selftest.sh target/responses2completions.jar
```

CI 里也跑同一套。

## 设计要点

- **流式收尾必须稳**：上游可能不发 `[DONE]`、可能中途断、可能只把文本放在终止事件里。代理无论如何都会补一个 `finish_reason` 块 + `[DONE]`，否则客户端会一直挂着。
- **工具调用去重**：`function_call_arguments.done` 会带全量参数，若已按 delta 累积过，只补差值，避免参数重复拼接成非法 JSON。
- **role 块懒发送**：只在第一个真实 delta 时发，避免空回复时客户端先收到一个空 chunk。
- **上游错误状态透传**：401/429/400 原样返回，让客户端能正确重试而不是当成 500。

## 目录

```
src/main/java/io/r2c/
├── Main.java                    启动与参数
├── Config.java                  环境变量配置
├── ProxyServer.java             HTTP 路由、SSE 转发
├── UpstreamClient.java          上游 HTTP 调用
├── ProxyException.java          OpenAI 风格错误
├── translate/
│   ├── ChatToResponses.java     请求方向翻译
│   ├── ResponsesToChat.java     响应方向翻译
│   └── StreamTranslator.java    流式状态机
└── util/{Jsons,Log}.java        工具
```

## License

MIT