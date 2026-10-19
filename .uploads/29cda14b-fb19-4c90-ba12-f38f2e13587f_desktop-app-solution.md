# 桌面端接入方案说明（现行实现）

> 更新：2026-08-04（v2.32）。本文描述仓库内 `desktop/` **实际落地**情况。

## 1. 方案总览

采用 **Tauri 2 + React 18 + Vite 5 + Spring Boot（8081）**。

| 层 | 职责 |
|----|------|
| `desktop/` | 桌面壳、白底聊天 UI、本地会话列表、邮箱登录、SSE（含进度）、统计折线、MD 预览、轮询通知 |
| Java 后端 | Agent 编排（含并行）、邮箱 / `app_user`、工具/MCP、文档/图片、提醒、用量、算完后 SSE + progress |

原则：**入站/出站按渠道分叉，AI 共用**。桌面走 REST/SSE，**不依赖微信 iLink**；微信仍走 iLink。

## 2. 目录结构

```
desktop/
├── index.html
├── package.json              # + react-markdown / remark-gfm
├── vite.config.js            # :3000，/api → 8081（SSE 关闭代理超时）
├── src/
│   ├── main.jsx
│   ├── App.jsx               # 侧栏 + 登录 + 流式/进度 + 统计折线 + MD 预览
│   ├── App.css
│   └── api.js                # mail / stream+progress / usage / news / notifications
└── src-tauri/
    ├── tauri.conf.json
    └── src/lib.rs

scripts/
└── web-search.js             # Node 联网搜索（后端 NodeScriptRunner 调用）
```

开发：`cd desktop && npx tauri dev`（后端需先起 8081）。  
打包：`npx tauri build`。

## 3. 通信

| 环境 | Base |
|------|------|
| Vite 开发 | `/api/v1` → `localhost:8081` |
| Tauri 窗口 | `http://localhost:8081/api/v1` |

### 已接接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/mail/send-code` | 发送登录验证码 |
| POST | `/mail/verify-code` | 校验 → `{ userId, email }` |
| POST | `/chat/send-stream` | **默认文本通路**；编排中可推 `progress` |
| POST | `/chat/image-stream` | 图片流式 |
| POST | `/chat/file-stream` | 文档流式 |
| POST | `/chat/send` 等 | JSON 兼容 |
| GET | `/chat/history` | 历史 |
| GET | `/chat/media/{fileName}` | 聊天图片 |
| GET | `/chat/generated/{date}/{fileName}` | generateDocument 产物（AItext） |
| GET | `/desktop/notifications` | 未读通知 |
| POST | `/desktop/notifications/{id}/read` | 标记已读 |
| GET | `/desktop/suggestions/news` | 欢迎页新闻胶囊 |
| GET | `/desktop/usage` | 用量统计 |

图状态注入 `channel=DESKTOP` + 客户端 `sessionId`；`ensureDesktopSession` 写 `session` 表。

### SSE 约定（算完再推正文 + 过程进度）

```
progress*（可选，编排/工具中）→ meta → delta* → done
```

| event | data |
|-------|------|
| `progress` | `{ "text": "正在搜索网页…" }` — 淡灰状态，不入历史 |
| `meta` | messageId / sessionId / finishReason / latencyMs / attachments… |
| `delta` | `{ "text": "片段" }` — 正式回复 |
| `done` | `{ "finishReason": "STOP"\|"NEED_CONFIRM"\|… }` |
| `error` | `{ "message": "…" }` |

速度：`STREAM_CHUNK_CHARS=2`，`STREAM_CHUNK_DELAY_MS=28`。  
落库：桌面由 Controller 统一写；`LlmService` 在 DESKTOP 跳过 DB。

## 4. 登录与 userId（v2.31）

| 状态 | userId | UI |
|------|--------|-----|
| 未登录 | `desktop-user` | 右上角「登录」 |
| 已登录 | `app_user` UUID | 侧栏左下「用户xxxxxxxx」 |

**免费轮次**：本会话用户消息满 3 条后再发 → 弹登录。详见 `桌面邮箱登录实现详解.md`。

## 5. UI 约定（现行）

- 浅色；用户浅灰气泡右对齐；助手左
- 等待：淡灰 **进度文案** + 三点左→右跳动；正式回复后清空进度
- WRITE：文字「确认/取消」；`finishReason=NEED_CONFIRM`；确认中 busy 不再开新轮
- `.md` 附件弹层 Markdown 渲染；`.txt` 纯文本
- 欢迎页：3 任务提示 + 7 新闻混排（中文过滤）
- 使用统计：汇总 + Token/耗时折线
- 提醒：约 3s 轮询；侧栏可折叠

## 6. 与微信的边界

| | 桌面 | 微信 |
|--|------|------|
| 入站 | REST / SSE | iLink |
| 出站 | SSE（progress+delta）/ 通知轮询 | iLink |
| 落库 | Controller + ensureSession | LlmService + getOrCreate |
| 提醒 | `DESKTOP` | `WECHAT` |
| 用户 | 邮箱 → UUID | openid |

## 7. 深研约定（v2.32）

- 研究报告：一次 `generalAgent` + `generateDocument`（目录 `AItext/`）
- 搜索：Node 脚本优先；只用结果真 URL
- 截图可选，失败可跳过
- 并行 Agent 仅用于互不依赖任务（如天气+打车），不拆同一份报告

## 8. 尚未做（规划）

- LLM 边生成边推 token（真流式）
- 附件历史跨会话完整持久化
- Content-Disposition 中文 RFC 5987
- Tauri 系统托盘通知
- 桌面 ↔ 微信账号绑定
- 用量落库

## 9. 环境

- Node 18+（运行后端 `node.scripts` + 桌面构建）、Rust、WebView2
- 后端端口 **8081**；`spring.mail.*`；`node.scripts.enabled=true`
- DDL：`V3__remind_channel.sql`、`V4__app_user.sql`
- 配置：`wechat.watchdog.agent-timeout-ms=240000`、`docgen.storage-path=./AItext`
