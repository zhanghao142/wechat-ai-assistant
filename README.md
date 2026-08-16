# wechat-ai-assistant（小微）

可执行任务的微信 / 桌面 AI 智能体：不只是闲聊，而是 **规划 → 调工具 → 交付结果**。

微信 Bot（iLink）与桌面端「小微」共用同一套 Spring Boot 后端；对话由 **LangGraph ReAct** 编排，支持本地工具、多 Agent、MCP、多模态与文档 RAG。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen.svg)](https://spring.io/projects/spring-boot)

---

## 能做什么

| 能力 | 说明 |
| --- | --- |
| 双入口 | 微信收发文字 / 图片 / 语音 / 文件；桌面端 SSE 流式对话 |
| 工具调用 | 搜索、读网页、天气、翻译、股票、浏览器自动化、图表等 |
| 写操作确认 | 生成文档、发邮件、设提醒等会先中断确认，再执行 |
| 多 Agent / MCP | 出行（滴滴）、文件系统、猎聘、麦当劳等可按需开启 |
| 多模态 | Qwen-VL 识图；语音入站 / CosyVoice TTS 出站 |
| 文档 RAG | 上传解析 + Embedding + Qdrant 语义检索 |
| 过程可见 | 桌面「工作叙述」侧栏；路线结果可在高德地图上渲染 |

---

## 技术栈

- **后端**：Spring Boot 3.2 · JDK 21 · MyBatis · MySQL 8
- **智能体**：LangChain4j · LangGraph4j · Function Calling · MCP
- **模型 / 服务**：DeepSeek（可配置）· Qwen-VL · CosyVoice · Qdrant
- **微信**：iLink Bot
- **桌面**：React · Tauri · Vite
- **其它**：Playwright（浏览器工具）

---

## 架构一览

```
微信用户 (iLink)  ←→  Spring Boot (:8081)  ←→  桌面「小微」(Tauri / React)
                            │
               LangGraph ReAct · Tools · Agents · MCP · RAG
```

主图循环（简化）：

```
START → prepare_input → session_prepare → llm_think
                              ↑               │
                              │          need tool?
                              └─ tool_execute     END
```

- 中央 Orchestrator 负责选型（本地工具 / 哪个子 Agent）
- WRITE 类工具在子 Agent 图的 `tool_execute` 前 `interrupt`，需用户确认

---

## 仓库结构

| 路径 | 说明 |
| --- | --- |
| `wechat-ai-common` | 统一响应、枚举、常量、Skill 接口 |
| `wechat-ai-session` | 会话 / 消息持久化、提醒调度 |
| `wechat-ai-document` | 文档解析、Embedding、Qdrant |
| `wechat-ai-tool` | `@Tool` / `@WriteTool`、Skills、MCP |
| `wechat-ai-agent` | 子 Agent 注册与调用 |
| `wechat-ai-ai` | LangGraph、LLM / TTS / 多模态、Chat / 桌面 API |
| `wechat-ai-proactive` | 行为路径自学习（可选） |
| `wechat-ai-wechat` | iLink 登录、收发、分发、出站 |
| `wechat-ai-starter` | 启动入口与 `application.properties` |
| `desktop/` | 桌面客户端（产品名：小微） |
| `scripts/` | Node 辅助脚本（如联网搜索） |

启动类：`com.wechatai.WechatAiApplication`  
默认端口：`8081`

---

## 环境要求

| 依赖 | 版本 / 说明 |
| --- | --- |
| JDK | 21 |
| Maven | 3.9+（可用仓库自带 `mvnw` / `mvnw.cmd`） |
| MySQL | 8.0（需先建库，例如 `wechat`） |
| Redis | 可选 |
| Node.js | 桌面端、filesystem MCP、部分脚本需要 |
| 可选服务 | Qdrant、各 MCP 平台账号、高德 Web JS Key（路线地图） |

---

## 快速开始

### 1. 克隆

```bash
git clone https://github.com/qwer-car/wechat-ai-assistant.git
cd wechat-ai-assistant
```

### 2. 配置后端

编辑：

```text
wechat-ai-starter/src/main/resources/application.properties
```

**最少需要**：

- `spring.datasource.url` / `username` / `password`
- `wechat.ai.llm.api-key`、`base-url`、`model`

按需再填：`qwen-vl.*`、`tts.*`、`wechat.qdrant.*`、`wechat.ai.embedding.*`、各 `mcp.*` 等。

> 请把密钥留在本地配置里，不要提交到 Git。仓库中的配置项默认为空占位。

### 3. 启动后端

Windows（Git Bash / PowerShell）：

```bash
./mvnw clean package -DskipTests
cd wechat-ai-starter
../mvnw spring-boot:run
```

或打包后运行：

```bash
java -jar wechat-ai-starter/target/wechat-ai-assistant.jar
```

- 服务地址：`http://localhost:8081`
- 若启用微信模块，控制台可能出现登录二维码，用手机扫码即可

### 4. 启动桌面端（可选）

```bash
cd desktop
cp .env.example .env
# 如需路线地图，填写 VITE_AMAP_KEY（及可选 VITE_AMAP_SECURITY_CODE）

npm install
npm run tauri:dev
```

仅开 Web 调试：

```bash
npm run dev
```

Vite 默认将 `/api` 代理到 `http://localhost:8081`。

打包安装包：

```bash
npm run tauri:build
```

---

## 配置说明

配置文件：`wechat-ai-starter/src/main/resources/application.properties`

| 类别 | 配置项 | 说明 |
| --- | --- | --- |
| 服务 | `server.port` | 默认 `8081` |
| 数据库 | `spring.datasource.*` | MySQL |
| LLM | `wechat.ai.llm.*` | API Key / base-url / model |
| 识图 | `qwen-vl.*` | 多模态 |
| TTS | `tts.*` | CosyVoice 等 |
| 翻译 | `translate.*` | 如百度翻译 |
| 邮件 | `spring.mail.*` | SMTP（WRITE） |
| 存储 | `docgen.storage-path`、`wechat.storage.local-path` | 生成物 / 上传目录 |
| RAG | `wechat.qdrant.*`、`wechat.ai.embedding.*`、`wechat.rag.*` | 向量检索 |
| MCP | `mcp.didi.*`、`mcp.filesystem.*`、`mcp.mcdonalds.*`、`mcp.liepin.*` | 默认关闭，按需开启 |
| 自学习 | `wechat.proactive.*` | 行为路径 |
| 超时 | `wechat.watchdog.*` | 工具 / Agent 看门狗 |

桌面地图 Key 只放在 `desktop/.env`（已加入 `.gitignore`），参考 `desktop/.env.example`。

---

## 多 Agent 与 MCP

```
中央 Orchestrator
  → 路由到子 Agent（白名单工具）
  → 本地 Tools 或 MCP（滴滴 / 文件 / 猎聘 / 麦当劳 …）
```

这是 **编排器 + 专家分工**，不是多智能体互相辩论。

常见子 Agent：

| Agent | 用途 |
| --- | --- |
| `generalAgent` | 通用本地工具 |
| `taxiAgent` | 出行 / 路线（滴滴 MCP）；桌面可用高德渲染 |
| `filesystemAgent` | 沙箱目录内读写（需 Node / npx） |
| `jobAgent` | 猎聘 MCP（可选） |
| `mcdonaldsAgent` | 麦当劳 MCP（可选） |

---

## HTTP API（摘要）

前缀一般为 `/api/v1/...`：

| 前缀 | 用途 |
| --- | --- |
| `/api/v1/chat` | 对话、SSE、图片 / 文件、生成物访问 |
| `/api/v1/session` | 会话列表 / 标题 / 删除 |
| `/api/v1/document` | 文档上传与管理 |
| `/api/v1/tools` | 工具列表 / 开关 |
| `/api/v1/desktop` | 通知、用量、工作叙述 |
| `/api/v1/mail` | 桌面邮箱验证码登录 |
| `/api/v1/tts` | 语音合成 |

---

## 使用提示

- 「查天气 / 搜一下 / 翻译」→ 只读工具，通常直接执行
- 「生成一份文档 / 发邮件 / 提醒我」→ 先确认，再执行
- 「规划从 A 到 B 的驾车路线」→ 走出行 Agent；桌面打开「路线地图」查看
- 做调研时可要求：表格 + 图表 + 结构化 Markdown 简报

---

## 已知限制

- 微信侧「语音气泡」受 iLink 能力约束，当前出站多为 **MP3 文件**
- 路线地图依赖出行 MCP 的结构化结果 + 桌面高德 Key
- 文档语义检索依赖 Embedding + Qdrant；未配置时相关能力不可用或降级
- 行为路径自学习依赖向量库与反馈，默认可关闭
- 多实例部署时会话 / 图状态需自行设计共享方案
- 文档与实现冲突时，以可运行代码为准

---

## 安全建议

- 不要把真实 API Key、数据库密码、MCP Token 提交进仓库
- 写操作（改文件、发邮件、下单类 MCP 等）请在可信环境启用，并保留确认流程
- `mcp.filesystem.allowed-dir` 请限制在项目沙箱目录（默认 `./upload`）

---

## 参与贡献

欢迎 Issue 与 Pull Request。提交前请：

1. 确认未包含密钥或本地路径硬编码
2. 尽量保持改动范围小、说明清楚动机
3. 若涉及新依赖或新配置项，在 PR 里写明用途与默认值

---

## License

[MIT](LICENSE) © 2026 zhanghao

---

## 致谢

- [LangGraph4j](https://github.com/langgraph4j/langgraph4j) / [LangChain4j](https://github.com/langchain4j/langchain4j)
- DeepSeek、通义、高德、各 MCP 开放平台等

若本项目对你有帮助，欢迎 Star。
