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
- **Android 版**：前台服务 + 悬浮窗，把手机本身变成中转站（见下）

## Android 版

`android/` 是一个独立 Gradle 工程，直接复用 `src/main/java` 里的核心代码，只把
JDK 专有的 `HttpClient` / `com.sun.net.httpserver` 换成 `HttpURLConnection` 和自带的
`net/NanoHttpServer`（纯 socket HTTP/1.1 实现，支持 chunked）。CI 会产出
`app-debug.apk`。

装好之后：

1. 打开 App，点"启动"（或直接启动前台服务）。
2. **必须授予悬浮窗权限**（`SYSTEM_ALERT_WINDOW`）。原因见下。
3. 在设置里填上游地址、key、模型，保存。

### 为什么悬浮窗是必需的

在国内 ROM（实测 ColorOS / OPPO）上，一个只挂了前台服务、没有可见窗口的进程，会在
启动约 5 秒后被 `OplusHansManager` 判定为 cached 进程并**整进程冻结**：

```
OplusBatteryStatsManager: updateStateTime: ... state to bg, isFg = false
OplusHansManager: freeze uid: 10553 ... pids: [26208] scene: LcdOn
IHansComunication: HANS sendMessageToKernel: ... pkgCmd = ADD_ONE_UID
```

冻结发生在内核层（cgroup freezer），进程里**所有线程**都会卡在 `do_freezer_trap`，
包括 accept 线程和看门狗线程。表现是：端口在启动几秒后从能连变成 connect 超时，
而进程 pid 不变、通知仍然写着"运行中"——非常像"服务静默死亡"，实际是整体被冻住。

持有 `TYPE_APPLICATION_OVERLAY` 窗口能把进程重要性抬到不会进 cached 冻结组：

```
mAttrs={... ty=APPLICATION_OVERLAY ...}
```

实测：授予悬浮窗权限后，把 App 切到后台连续轮询 90 秒，`/health` 0 次失败、pid 不变；
未授权时 5–6 秒即失效。电池优化白名单和 `cached_apps_freezer=disabled` 在这台机器上
都**无效**，只有悬浮窗有效。

### 从命令行写配置

`shared_prefs` 不能从 shell 直接改：App 进程有自己的内存副本，下次保存会覆盖文件，
而且加载时可能看不到外部写入的内容。要改配置，走 App 自己的写入路径：

```bash
adb shell am start -n io.r2c.android/.MainActivity \
  --es r2c_base_url 'https://api.example.com' \
  --es r2c_api_key 'sk-...' \
  --es r2c_model 'gpt-6.1-sol' \
  --es r2c_user_agent 'codex_cli_rs/0.42.0' \
  --ei r2c_port 8787 \
  --ei r2c_timeout 600
```

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

## 典型场景：只允许"编程客户端"的站点

不少中转站点会对 **User-Agent 做白名单**，只放行 Codex / Cursor / Cline 等编程客户端，
用 SDK 或脚本直连会被拒绝：

```json
{"error":{"code":"client_not_allowed",
  "message":"本站仅支持编程客户端使用：Codex、Cursor、Cline…","type":"permission_error"}}
```

本代理默认把上游 UA 设成 `codex_cli_rs/0.42.0`，因此**任意客户端都能通过**：

```bash
R2C_UPSTREAM_BASE_URL=https://api.example.com/v1 \
R2C_UPSTREAM_API_KEY=sk-xxxx \
R2C_UPSTREAM_USER_AGENT='codex_cli_rs/0.42.0' \
java -jar responses2completions.jar
```

> `R2C_UPSTREAM_USER_AGENT` 换成别的名字（如 `cursor/1.0`）也可用，
> 取决于站点放行哪一类客户端。

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