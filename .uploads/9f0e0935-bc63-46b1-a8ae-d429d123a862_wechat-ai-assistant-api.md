# 微信智能体助手 — 全局接口文档

> **版本**: v2.32  
> **最后更新**: 2026-08-04  
> **技术栈**: Spring Boot 3.2.5 | MyBatis 3.0.3 | MySQL 8.0 | Redis 7.x | LangChain4j **1.7.1** | LangGraph4j 1.8.18 | MCP SDK 0.8.1 | Jsoup 1.18.1 | DashScope SDK 2.17.0 | Caffeine 3.x | Node（可选，联网搜索脚本）  
> **微信接入**: 纯 Java iLink SDK（`io.github.lith0924:wechat-ilink-sdk:2.3.3`），支持多 Bot 多账号  
> **桌面接入**: Tauri 2 + React（`desktop/`），REST/SSE 直连 `:8081`，不依赖 iLink；**算完再 SSE 分片** + 过程 **`progress`**；**邮箱验证码登录**（`/api/v1/mail` + `app_user`）；并行 Agent / 用量统计 / 深研文档  

> **MCP 接入**: RestClient 直调 JSON-RPC 2.0（零额外依赖，McpSource 接口可扩展）  
> **多 Agent**: wechat-ai-agent 模块 + AgentGraphRunner 子 Agent LangGraph 图（支持同轮并行）  
> **多账号**: WechatClientRegistry 多 Bot 管理 + @ConditionalOnProperty 条件装配 + 向后兼容单账号

---

> 📋 **历史变更记录已移至 `更新日志.md`**，本文件仅保留当前接口契约。

---

## 目录

1. [项目总架构](#1-项目总架构)
2. [通用规范](#2-通用规范)
3. [会话管理模块](#3-会话管理模块)
4. [文档管理模块](#4-文档管理模块)
5. [AI 对话模块](#5-ai-对话模块)
6. [工具系统](#6-工具系统核心设计)
7. [微信接入模块](#7-微信接入模块)
8. [桌面通知 / 资讯 / 用量](#8-桌面通知模块v229)
9. [桌面邮箱登录](#9-桌面邮箱登录模块v231)

**附录**
- A. [Redis Key 规范](#附录-a-redis-key-规范)
- B. [阿里云部署注意事项](#附录-b-阿里云部署注意事项)
- C. [开发协作规范](#附录-c-开发协作规范)
- D. [POJO 类定义大全](#附录-d-pojo-类定义大全)
- E. [MCP 配置附录](#附录-f-mcp-配置附录)

---

## 1. 项目总架构

### 1.1 系统分层架构

从顶层到底层共分 5 层，**每一层只能依赖它的下一层**：

```
┌──────────────────────────────────────────────────────────────────┐
│  ██  接入层 (Gateway)           微信 / API 入口                    │
│  └─ 7.微信接入模块 — 消息收发、Token 管理                            │
├──────────────────────────────────────────────────────────────────┤
│  ██  应用层 (Application)       业务编排入口                        │
│  ├─ 3.会话管理模块 — 会话 CRUD                                      │
│  ├─ 4.文档管理模块 — 上传、解析、存储                                │
│  └─ 5.AI 对话模块 — 消息接收、AI 编排                               │
├──────────────────────────────────────────────────────────────────┤
│  ██  编排层 (Orchestration)     核心智能引擎                        │
│  ├─ LangGraph4j 图编排 — 对话流、工具调用链                          │
│  ├─ LangChain4j LLM 对接 — 大模型调用、Prompt 管理                  │
│  └─ 6.工具系统 — 工具注册、调度、执行、错误处理                        │
├──────────────────────────────────────────────────────────────────┤
│  ██  服务层 (Service)           通用业务服务                        │
│  ├─ 文档解析服务 (PDF/Word/TXT)                                    │
│  ├─ 文件存储服务 (本地 / OSS)                                      │
│  └─ 微信 API 服务 (客服消息、素材管理)                               │
├──────────────────────────────────────────────────────────────────┤
│  ██  数据层 (Data)              持久化与缓存                        │
│  ├─ MySQL 8.0 — 会话、消息、文档、工具调用历史                        │
│  └─ Redis 7.x — 缓存、任务队列、限流                                │
└──────────────────────────────────────────────────────────────────┘
```

---

### 1.2 多模块 Maven 工程结构

采用 **Maven 多模块** 拆分，按业务边界划分，**模块间单向依赖**：

```
wechat-ai-assistant                           # 父 POM (聚合工程)
│
├── wechat-ai-common                          # 公共模块（不依赖任何业务模块）
│   ├── model/                                #   统一 VO: Result
│   ├── enums/                                #   全局枚举: ErrorCode, FileType, SessionStatus
│   ├── constant/                             #   常量: RedisKey, ApiPrefix
│   ├── skill/                                #   Skill 接口定义
│   └── util/                                 #   工具: TraceIdUtil, JsonUtil
│
├── wechat-ai-session                         # 会话模块
│   ├── controller/                           #   REST 接口
│   ├── service/                              #   业务逻辑
│   ├── mapper/                               #   MyBatis Mapper
│   └── entity/                               #   数据实体 (PO)
│
├── wechat-ai-document                        # 文档模块
│   ├── controller/
│   ├── service/
│   │   └── parser/                           #   文档解析器 (PDFBox, POI)
│   ├── mapper/
│   └── entity/
│
├── wechat-ai-agent                            # 多 Agent 框架模块（新增 v2.20）
│   ├── LlmCaller.java                         #   LLM 调用契约接口
│   ├── AgentResult.java                       #   子 Agent 返回体
│   ├── AgentInvoker.java                      #   子 Agent 小 ReAct 循环
│   └── AgentToolRegistration.java             #   Agent 工具注册器
│
├── wechat-ai-ai                              # AI 引擎模块（你 + Skill 系统）
│   ├── ai/                                   #   LlmService, TTS, QwenVL
│   ├── config/                               #   ChatGraphConfig 图编排
│   ├── node/                                 #   图节点 (LlmThinkNode, ToolExecuteNode...)
│   ├── skill/                                #   SkillManager 动态加载 Skill
│   └── state/                                #   ChatGraphState 图状态
│
├── wechat-ai-wechat                          # 微信接入模块
│   ├── WechatConnectionManager.java          #   iLink SDK 登录/恢复 + 消息拉取
│   ├── WechatLoginStore.java                 #   登录状态持久化（重启免扫码）
│   ├── WechatMessageDispatcher.java          #   消息分发（按 type 路由）
│   ├── WechatInboundHandler.java             #   文本/图片/语音/文件处理
│   ├── WechatChatBridge.java                 #   AI 图调用 + 多轮上下文 + 线程池 + 截图附件发送
│   ├── WechatOutboundSender.java             #   sendText/sendImage/sendFile/TTS 统一出口（4 种音色标记）
│   └── service/
│       └── WechatServiceImpl.java            #   只做组件组装入口
│
├── wechat-ai-tool                            # 工具系统模块（你 + 协助）
│   ├── mcp/                                  #   MCP 外部工具接入（v2.17，v2.18 重构为多源框架）
│   │   ├── McpSource.java                    #   MCP 源抽象接口（可扩展核心，每个源实现此接口）
│   │   ├── McpToolDefinition.java            #   MCP 工具定义 POJO
│   │   ├── McpConfigProperties.java          #   滴滴 MCP 配置映射 @ConfigurationProperties
│   │   ├── DidiMcpSource.java                #   滴滴出行 MCP 实现（JSON-RPC 2.0 直调）
│   │   ├── McpClientManager.java             #   多源管理器：自动收集所有 McpSource Bean
│   │   └── McpToolBridge.java                #   ApplicationReadyEvent 遍历注册到 ToolRegistry
│   ├── registry/                             #   工具注册中心（WRITE_TOOLS 分类 + 外部工具支持）
│   ├── impl/                                 #   具体工具实现（26 个 @Tool）
│   │   ├── SearchDocumentTool.java
│   │   ├── GetDocumentSummaryTool.java
│   │   ├── QueryWeatherTool.java
│   │   ├── ReadUrlTool.java（含 searchInternet）
│   │   ├── ConversationSearchTool.java
│   │   ├── BrowserTool.java（navigate/click/fill/screenshot/getText/goBack/goForward）
│   │   ├── BrowserSessionManager.java（Playwright 生命周期管理）
│   │   ├── TransformContentTool.java（内容风格转换）
│   │   ├── WeChatArticleDownloaderTool.java（公众号转 Markdown）
│   │   ├── QueryStockTool.java（股票查询）
│   │   ├── DiceTool.java / DailyWaifuTool.java / OnThisDayTool.java / RandomChoiceTool.java
│   │   └── ...（原有 6 个）
│   ├── config/                               #   浏览器配置
│   │   └── BrowserProperties.java
│   ├── dto/                                  #   工具输入/输出 DTO
│   │   ├── ActionResult.java（click/fill 通用）
│   │   ├── PageInfo.java（navigate 返回）
│   │   ├── TextResult.java（getText 返回）
│   │   ├── ScreenshotResult.java（截图返回）
│   │   └── ScriptResult.java（脚本执行返回）
│   ├── skill/                                #   Skill 实现（按工具加载的行为规则）
│   │   ├── WeatherSkill.java
│   │   ├── DocumentSkill.java
│   │   ├── DocumentGenSkill.java
│   │   ├── WebSearchSkill.java
│   │   ├── ConversationSearchSkill.java
│   │   ├── TranslateSkill.java
│   │   ├── RemindSkill.java
│   │   └── EmailSkill.java
│   ├── dto/                                  #   工具输入/输出 DTO
│   └── model/                                #   VO 定义
│
└── wechat-ai-starter                         # 启动入口 + 全局配置
    ├── WechatAiApplication.java              #   @SpringBootApplication
    ├── config/                               #   全局配置类
    │   ├── RedisConfig.java
    │   ├── ThreadPoolConfig.java
    │   └── WebMvcConfig.java
    └── application.properties               #   主配置文件
```

**编译期依赖关系（Maven pom.xml 层面，非远程调用）**:

```
starter
  ├── session ─── common        # session 模块依赖 common
  ├── document ── common        # document 模块依赖 common
  ├── agent ───── tool ── common  # agent 依赖 tool（工具过滤）+ common
  ├── ai ──────── session       # ai 依赖 session 的接口（非实现）
  │             ├── document    # ai 依赖 document 的接口（非实现）
  │             ├── agent       # ai 依赖 agent（AgentInvoker, LlmCaller）
  │             └── tool ── common
  ├── wechat ──── session       # wechat 依赖 session 的接口
  │             ├── document    # wechat 依赖 document（文件处理）
  │             ├── ai          # wechat 依赖 ai（图引擎）
  │             └── tool        # wechat 依赖 tool（发错误消息）
```

> **⚠️ 重要说明**: 这是 **单体多模块 Maven 工程**，打包后是一个 JAR 部署运行，**不是微服务**。
> - 模块间是 **JVM 内方法调用**，不是 HTTP/gRPC 远程调用
> - 不需要注册中心、网关、服务发现
> - 最终产物是单个 `wechat-ai-assistant.jar`
> - 多模块的目的仅仅是代码隔离、减少 Git 冲突、每个人各改各的模块
>
> **核心规则**: `common` 不依赖任何模块。业务模块之间只依赖对方的接口（interface），不依赖实现类，通过 Spring DI 注入。

---

### 1.3 核心请求链路

**文档上传 & 异步解析链路**:

```
微信用户
  │  发文件消息
  ▼
7.2 微信消息接收 ───→ 保存到本地
  │                    │
  │  返回 "" (占住5s)    │
  │                    ▼
  │               4.1 上传文档
  │                    │
  │               存入 MySQL（状态: UPLOADED）
  │                    │
  │               4.2 发起解析 → Redis List 入队
  │                    │
  └── 等待... ──────────┤
                        │
                    ┌───┴───┐
                    │  Worker 出队解析
                    │   (PDFBox/Apache POI)
                    │
                    ├─ 成功 → MySQL 更新状态 PARSED
                    │          存储解析文本
                    │
                    └─ 失败 → MySQL 更新状态 FAILED
                               记录错误信息
```

> **PDFBox / Apache POI 说明**：文档解析用到的两个 Java 工具库。**PDFBox** 把 PDF 文件读成纯文本，**Apache POI** 把 Word 文件（.doc/.docx）读成纯文本。解析后的文本存入 MySQL，供 AI 检索使用。
>
> > 💡 若使用微信机器人 SDK，无需立即返回 `""` 占位，消息可直接进入处理流程，无 5 秒超时限制。

**对话 & 工具调用链路**:

```
微信用户
  │  发文本消息
  ▼
7.2 微信消息接收 ──→ 立即返回 ""
  │
  ▼
5.1 发送消息 (/chat/send)
  │
  ▼
AI 引擎 (LangGraph 图编排)
  │
  ├─ 判断 → 只需要 LLM 回复
  │     │
  │     ▼
  │  调用 LLM → 生成回复
  │     │
  │     ▼
  │  7.3 主动发送客服消息
  │     │
  │     ▼
  │  用户看到回复
  │
  └─ 判断 → 需要调用工具
        │
        ▼
     LLM 输出 tool_calls
        │
        ▼
  ┌─────────────────────────────┐
  │  LangChain4j @Tool 自动执行  │  ← 同 JVM 内方法调用，不走 HTTP
  │  (wechat-ai-tool 模块 Bean) │
  └──────┬──────────────────────┘
         │
    ┌────┴────┐
    │         │
  成功      失败（retryable=true 则 AI 引擎自动重试）
    │         │
    ▼         ▼
结果结构化  错误信息包装
    │         │
    └────┬────┘
         │
         ▼
   LLM 继续推理（工具结果已自动回填）
         │
    ┌────┴────┐
    │         │
 又调工具  直接回复 → 7.3 发送客服消息
```

---

### 1.4 模块及负责人

| 模块 | Maven 模块 | 覆盖章节 | 建议负责人 | 涉及技能 |
|------|-----------|---------|-----------|---------|
| 公共模块 | `wechat-ai-common` | 2.通用规范 | 全体 | Java 基础 |
| 会话管理 | `wechat-ai-session` | 3.会话管理 | 传统 Java | CRUD、MyBatis |
| 文档管理 | `wechat-ai-document` | 4.文档管理 | 传统 Java | 文件IO、异步任务、Redis |
| AI 引擎 | `wechat-ai-ai` | 5.AI 对话 | 你 | LangChain4j、LangGraph4j |
| 多 Agent | `wechat-ai-agent` | 6.10 多 Agent 架构 | 你 | AgentInvoker、LlmCaller |
| 工具系统 | `wechat-ai-tool` | 6.工具系统 | 你 + 协助 | 工具注册、回调 |
| 微信接入 | `wechat-ai-wechat` | 7.微信接入 | 传统 Java | 消息加解密、HTTP回调 |
| 启动入口 | `wechat-ai-starter` | - | 你（初始搭建） | Spring Boot 配置 |

> ⚠ **关键约定**: 各模块之间**只通过接口契约通信**，不跨模块依赖具体实现类。  
> 每个模块的负责人只需关注自己模块的接口实现及返回格式，其他模块调你时保证按文档返回即可。

---

## 2. 通用规范

### 2.1 统一响应格式

所有接口统一返回如下 JSON 结构：

```json
{
  "code": 200,
  "message": "success",
  "data": { },
  "traceId": "a1b2c3d4e5f6",
  "timestamp": 1711234567000
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | int | 业务码，200 成功，详见错误码表 |
| `message` | string | 提示信息 |
| `data` | object/null | 业务数据体 |
| `traceId` | string | 链路追踪 ID，用于排查问题 |
| `timestamp` | long | 服务端时间戳 (ms) |

### 2.2 错误码表

| code | message | 说明 |
|------|---------|------|
| 200 | success | 成功 |
| 400 | bad request | 请求参数错误 |
| 401 | unauthorized | 未授权 |
| 403 | forbidden | 无权限 |
| 404 | not found | 资源不存在 |
| 500 | internal error | 服务器内部错误 |
| 1001 | session not found | 会话不存在 |
| 1002 | session expired | 会话已过期/超时 |
| 2001 | file too large | 文件超出大小限制 |
| 2002 | unsupported file type | 不支持的文档格式 |
| 2003 | file parse failed | 文档解析失败 |
| 2004 | file not found | 文档不存在 |
| 3001 | ai service error | AI 服务异常 |
| 3002 | tool execution failed | 工具调用执行失败 |
| 3003 | context overflow | 上下文超出限制 |
| 4001 | wechat message error | 微信消息处理异常 |

### 2.3 请求头规范

| Header | 值 | 说明 |
|--------|----|------|
| `Content-Type` | `application/json` | POST 请求必须（文件上传用 `multipart/form-data`） |
| `X-User-Id` | `openid` | 微信用户 openid（内部传递） |
| `X-Session-Id` | `uuid` | 当前会话 ID（可选） |
| `Trace-Id` | `uuid` | 链路追踪 ID（可选，不传则服务端生成） |

### 2.4 API 路径前缀

```
所有接口以 /api/v1 开头
示例: POST /api/v1/session/create
```

### 2.5 日期格式

所有时间字段统一使用 `yyyy-MM-dd HH:mm:ss` 格式，时区 `Asia/Shanghai`。

---

## 3. 会话管理模块

### 3.1 会话（Session）说明

#### 会话是什么、为什么需要它

AI 大模型本身是没有记忆的，它每次收到消息都是"第一次见到你"。为了让 AI 能记住上下文，需要把用户的历史消息收集起来，每次请求时一起发给 LLM。

**会话（Session）就是一组消息的容器**：

```
用户发第1条 → session 存消息①
用户发第2条 → session 存消息②
用户发第3条 → AI 引擎从 session 取出 ①+②+③ 一起发给 LLM → LLM 知道前后文
```

每个 session 有 24 小时有效期，过期后自动失效，相当于 AI 忘记了之前的对话。

---

#### 对用户（如微信用户）完全自动管理

用户**不需要手动创建会话**。流程由后端自动处理：

| 场景 | 谁触发的 | 后端做什么 |
|------|---------|-----------|
| 用户第一次发消息 | **系统自动** | 查不到该用户的活跃 session → 自动 `INSERT` 一条 → 返回新 sessionId |
| 用户继续对话 | **系统自动** | 查到该用户有活跃 session → 沿用，把新消息追加进去 |
| 超过 24 小时没说话 | **系统自动** | session 过期 → 下次发消息时自动创建新 session（旧消息已无法访问） |
| 用户说"重新开始" | AI 识别意图 | 后端调用清空接口（详见 5.3），**保留 session 但清空消息历史** |

> 💡 **为什么不是直接创建新 session 而是清空？**
> 因为用户不需要管理多个会话。如果每次都创建新 session，用户会看到一堆"空白对话"，反而困惑。清空上下文相当于"翻篇了"，但对话仍然在同一个 session 下，对用户透明。

#### 桌面会话（v2.31）

桌面端 `sessionId` 由客户端生成（UUID），`SessionPrepareNode` **不会**用微信的 `getOrCreateActiveSession` 覆盖该 ID，但会调用：

```
SessionService.ensureDesktopSession(userId, sessionId)
```

| 情况 | 行为 |
|------|------|
| `session` 表无此行 | `INSERT`（`source=API`，续期 24h） |
| 已存在 | 续期 24h |
| 原 `user_id=desktop-user`，当前为正式登录 UUID | **改绑**到正式 `userId` |

因此登录后再发一条消息，即可在库中按正式 `user_id` 查到该桌面会话。侧栏会话列表仍在浏览器 localStorage。

---

#### 活跃会话的判断逻辑（实现细节）

**什么是"活跃"？** 会话的 `expire_at` 字段还没到当前时间，就算活跃。

判断方式：**每次用户发消息时，后端自动查数据库，不需要定时器或计时器。**

```
用户发消息 → 5.1 chat/send 被调用
  │
  ├─ ① SessionService.getOrCreateActiveSession(userId)
  │      │
  │      ├─ 执行 SQL：SELECT * FROM session
  │      │    WHERE user_id = ?
  │      │      AND status = 'ACTIVE'
  │      │      AND expire_at > NOW()    ← 数据库自动比较，不需要自己算时间戳
  │      │    ORDER BY created_at DESC
  │      │    LIMIT 1
  │      │
  │      ├─ 查到了？
  │      │    ├─ 是 → 沿用该 session
  │      │    │      └─ 执行 SQL：UPDATE session
  │      │    │           SET expire_at = NOW() + 24h    ← 续期24小时
  │      │    │           WHERE session_id = ?
  │      │    │
  │      │    └─ 否 → 创建新 session
  │      │           └─ 执行 SQL：INSERT INTO session
  │      │                (expire_at) VALUES (NOW() + 24h)  ← 初始24h
  │      │
  │      └─ 返回 sessionId
  │
  └─ ② 拿着 sessionId 继续走 AI 处理流程
```

**核心要点**：

| 疑问 | 答案 |
|------|------|
| 续期需要定时器/计时器吗？ | **不需要**。续期是每次发消息时顺带做的 UPDATE |
| 谁来比较时间？ | **MySQL 自己**。`expire_at > NOW()` 是数据库在 SQL 执行时实时比较的 |
| 续期的 SQL 写在哪？ | **wechat-ai-session 模块**的 SessionService.getOrCreateActiveSession() 方法里 |
| AI 模块怎么调？ | 调 SessionService 接口，不关心实现细节 |

**对应的 Java 代码示例**：

```java
// wechat-ai-session/service/SessionServiceImpl.java
@Service
public class SessionServiceImpl implements SessionService {

    @Autowired
    private SessionMapper sessionMapper;

    /**
     * 获取或创建用户的活跃会话
     * - 有活跃会话 → 续期 24h 后返回
     * - 没有 → 创建新会话返回
     */
    public SessionEntity getOrCreateActiveSession(String userId) {
        // 1. 查活跃会话（数据库比较 expire_at > NOW()）
        SessionEntity session = sessionMapper.findActiveByUserId(userId);
        
        if (session != null) {
            // 2. 有活跃会话 → 续期 24 小时
            sessionMapper.refreshExpireTime(
                session.getSessionId(),
                LocalDateTime.now().plusHours(24)
            );
            return session;
        }
        
        // 3. 没有活跃会话 → 创建新的
        return sessionMapper.createSession(userId, SessionSource.WECHAT);
    }
}
```

```java
// wechat-ai-session/mapper/SessionMapper.java
@Mapper
public interface SessionMapper {

    @Select("""
        SELECT * FROM session
        WHERE user_id = #{userId}
          AND status = 'ACTIVE'
          AND expire_at > NOW()
        ORDER BY created_at DESC
        LIMIT 1
    """)
    SessionEntity findActiveByUserId(String userId);

    @Update("""
        UPDATE session
        SET expire_at = #{newExpireAt}
        WHERE session_id = #{sessionId}
    """)
    void refreshExpireTime(String sessionId, LocalDateTime newExpireAt);
}
```

```java
// wechat-ai-ai/service/ChatService.java（调用方）
@Service
public class ChatService {

    @Autowired
    private SessionService sessionService;  // 只依赖接口

    public ChatResponseVO sendMessage(ChatSendReq req) {
        // 1. 获取/续期会话（一行搞定，不关心内部实现）
        SessionEntity session = sessionService.getOrCreateActiveSession(req.getUserId());
        
        // 2. 后续 AI 处理...
        String sessionId = session.getSessionId();
        // ...
    }
}
```

---

#### 3.1.1 创建会话

对外不暴露此接口。会话创建完全由 `SessionService.getOrCreateActiveSession()` 内部自动处理，外部调用方无需关心。

> ⚠ 第一期不对外提供 `POST /api/v1/session/create` 接口。以后有外部系统通过 API 调用 AI 能力的需求时再加。

---

> ⚠ **以下 3.2 ~ 3.5 的四个会话管理接口，第一期不对外暴露**。
>
> 在微信场景下，用户不会看到"会话列表"也不需要手动管理对话。这些接口是为后续管理后台预留的（比如运营人员查用户聊天记录、手动删对话）。
>
> **第一期用不到**，但接口定义先留着方便以后对接。

### 3.2 获取会话详情

```
GET /api/v1/session/{sessionId}
```

**响应 data**:

```json
{
  "sessionId": "6b3e8f20-...",
  "userId": "oWx_xxxxx",
  "title": "关于合同条款的咨询",
  "status": "ACTIVE",
  "messageCount": 23,
  "lastMessageAt": "2026-07-15 14:30:00",
  "createdAt": "2026-07-15 10:30:00",
  "expireAt": "2026-07-16 10:30:00"
}
```

---

### 3.3 获取用户会话列表

```
GET /api/v1/session/list?userId=oWx_xxxxx
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `userId` | string | 是 | 微信用户 openid |

**响应 data**:

```json
[
  {
    "sessionId": "6b3e8f20-...",
    "title": "关于合同条款的咨询",
    "status": "ACTIVE",
    "messageCount": 23,
    "lastMessageAt": "2026-07-15 14:30:00",
    "createdAt": "2026-07-15 10:30:00"
  }
]
```

---

### 3.4 更新会话标题

```
POST /api/v1/session/{sessionId}/title
```

**请求体**:

```json
{
  "title": "新的会话标题"
}
```

**响应 data**: `null`

---

### 3.5 删除会话

```
POST /api/v1/session/{sessionId}/delete
```

**响应 data**: `null`

> ⚠ 逻辑删除，设置 `status = DELETED`，30 天后物理清理。

---

## 4. 文档管理模块

### 4.1 上传文档

用户通过微信发送文件 → 服务端接收并存储。

> **负责人**: 传统 Java 端  
> **流程**: 微信接收文件 → 保存到本地/OSS → 返回文件 ID → 异步入队解析

```
POST /api/v1/document/upload
```

**请求体**: `multipart/form-data`

> `multipart/form-data` 是文件上传专用的请求格式。与普通接口传 JSON 不同，它可以把**文件二进制数据**和普通字段（sessionId、userId）打包在一起发送。因为 JSON 不支持直接放二进制文件内容，所以上传文件时必须用这种格式。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | File | 是 | 文档文件（二进制） |
| `sessionId` | string | 是 | 所属会话 ID |
| `userId` | string | 是 | 用户 openid |

**限制**:

| 项目 | 限制 |
|------|:-----|
| 支持格式 | `pdf`, `doc`, `docx`, `txt` |
| 单文件大小上限 | 50 MB |
| 单会话文档数上限 | 50 个 |
| 单用户总文档数 | 500 个 |

**响应 data**:

```json
{
  "fileId": "f_a1b2c3d4",
  "fileName": "合同模板.pdf",
  "fileSize": 2048576,
  "fileType": "pdf",
  "status": "UPLOADED",
  "uploadedAt": "2026-07-15 10:30:00"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `fileId` | string | 文件唯一 ID |
| `status` | string | `UPLOADED` / `PARSING` / `PARSED` / `FAILED` |
| `fileSize` | long | 文件大小，单位字节 |

---

### 4.2 发起文档解析

上传完成后调用此接口，将文档加入解析队列（Redis List）。

```
POST /api/v1/document/parse
```

**请求体**:

```json
{
  "fileId": "f_a1b2c3d4"
}
```

**响应 data**:

```json
{
  "taskId": "task_x1y2z3",
  "fileId": "f_a1b2c3d4",
  "status": "QUEUED",
  "estimatedSeconds": 15
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `taskId` | string | 异步任务 ID |
| `status` | string | `QUEUED` / `PARSING` / `DONE` / `FAILED` |
| `estimatedSeconds` | int | 预估解析时长（秒） |

> ⚠ 解析由后台 Worker（独立线程）完成，完成后：
> 1. 将解析出的纯文本存入 MySQL **document 表的 `content` 字段**（LONGTEXT 类型，最多存 10 万字）
> 2. 更新文档状态为 `PARSED`
> 3. AI 引擎通过 4.6 接口拉取此内容进行检索
>
> 期间前端/微信侧无需轮询等待，用户可继续发消息。AI 在处理问题时自动检查文档状态（详见上文"不需要轮询"说明）。

---

### 4.3 查询文档解析状态

```
GET /api/v1/document/{fileId}/status
```

**响应 data**:

```json
{
  "fileId": "f_a1b2c3d4",
  "fileName": "合同模板.pdf",
  "fileType": "pdf",
  "status": "PARSED",
  "parsedAt": "2026-07-15 10:30:30"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `status` | string | 见 4.1 |

---

### 4.4 获取文档列表（按会话）

```
GET /api/v1/document/list?sessionId=xxx
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | string | 是 | 会话 ID |
| `status` | string | 否 | 按状态筛选，如 `PARSED` |

**响应 data**:

```json
[
  {
    "fileId": "f_a1b2c3d4",
    "fileName": "合同模板.pdf",
    "fileSize": 2048576,
    "fileType": "pdf",
    "status": "PARSED",
    "uploadedAt": "2026-07-15 10:30:00",
    "parsedAt": "2026-07-15 10:30:30"
  }
]
```
```

---

### 4.5 删除文档

```
POST /api/v1/document/{fileId}/delete
```

**响应 data**: `null`

> ⚠ **不可恢复**。此操作同时删除两样东西：
> 1. 文件存储中的原始文件（OSS/本地磁盘）
> 2. MySQL 中的文档记录（含解析后的文本内容）

---

### 4.6 获取文档内容

获取已解析文档的纯文本内容。主要供 AI 引擎内部检索使用，也可用于管理后台查看文档原文。

```
GET /api/v1/document/{fileId}/content
```

**响应 data**:

```json
{
  "fileId": "f_a1b2c3d4",
  "fileName": "合同模板.pdf",
  "content": "（全文文本内容，约数万字符）...",
  "contentType": "PLAIN_TEXT"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | string | 纯文本内容（PDF/Word 转出的文本） |
| `contentType` | string | `PLAIN_TEXT` / `MARKDOWN` |

> ⚠ **安全限制**: 单次最多返回 10 万字符，超出部分截断。
> 
> 💡 **AI 引擎内部调用**：AI 模块不通过此 HTTP 接口获取内容，而是直接调 `DocumentService.getContent(fileId)` 的同 JVM 方法。

---

## 5. AI 对话模块

### 5.1 发送消息（核心接口）

用户发送一条消息，由 AI 引擎处理并返回回复。工具调用在 AI 引擎内部自动完成（LangChain4j @Tool 同 JVM 调用），**对外部调用方透明**。

> **负责人**: 你 (LangChain4j + LangGraph4j)  
> 这是全系统最核心的接口，其余模块的输出最终汇入此处。  
> **重要变更**: v1.1 起工具调用改为 AI 引擎内部自动执行，不再通过 HTTP 回调。调用方只需发送一次请求，等待最终回复即可。

```
POST /api/v1/chat/send
```

**请求体**:

```json
{
  "sessionId": "6b3e8f20-...",
  "userId": "oWx_xxxxx",
  "message": "这份合同里违约条款是怎么写的？",
  "messageType": "TEXT",
  "fileIds": ["f_a1b2c3d4"],
  "extra": {}
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | string | 是 | 会话 ID |
| `userId` | string | 是 | 用户 openid |
| `message` | string | 是 | 用户消息内容 |
| `messageType` | string | 否 | `TEXT` / `IMAGE` / `FILE`，默认 `TEXT` |
| `fileIds` | string[] | 否 | 本次问答相关的文档 ID 列表 |
| `extra` | object | 否 | 扩展字段（前端额外参数） |

**响应 data**:

```json
{
  "replyMessage": "根据合同第七章第三条，违约方需按合同总额的 20% 支付违约金。具体来说...",
  "messageId": "msg_xxxxx",
  "sessionId": "6b3e8f20-...",
  "finishReason": "STOP",
  "latencyMs": 2340
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `replyMessage` | string | AI 最终回复内容（工具调用已在内部完成） |
| `messageId` | string | 消息 ID |
| `sessionId` | string | 会话 ID |
| `finishReason` | string | `STOP` / `ERROR` / `NEED_CONFIRM`（WRITE 工具待用户确认） |
| `latencyMs` | int | 处理总耗时（含内部工具调用链，毫秒） |

> ✅ **调用方只需调用一次**：AI 引擎内部自动处理 LLM ↔ 工具的多次交互，最终返回完整的回复内容。无需关心中间的工具调用过程。
>
> **桌面 WRITE 确认（v2.29）**：当 `finishReason=NEED_CONFIRM` 时，`replyMessage` 为确认提示文案；用户下一条消息回复「确认」或「取消」即续跑 `confirmResume`，无需额外按钮接口。图状态注入 `channel=DESKTOP`，会话 ID 以请求中的 `sessionId` 为准（不被 `getOrCreateActiveSession` 覆盖）。
>
> **桌面 session 落库（v2.31）**：Prepare 阶段对客户端 `sessionId` 调用 `ensureDesktopSession`，保证 `session` 表有记录并可挂到当前 `userId`（含从 `desktop-user` 改绑）。

**内部编排流程（实现概览）**:

```
ChatController.sendMessage(req)
  │
  ├─ ① 注入 channel=DESKTOP + 请求 sessionId（桌面）
  │
  └─ ② ChatGraph / AgentGraph 编排
         │
         ├─ needsConfirm → finishReason=NEED_CONFIRM
         └─ 直接回复 / 工具完成 → STOP
```

| 包 | 职责 |
|------|------|
| `service/graph/` | LangGraph 状态池 (ChatGraphState) |
| `service/agent/` | ReAct 节点 (LLM 思考 + 工具调度) |
| `config/` | 图定义配置 (ChatGraphConfig) |

---

### 5.1b 发送图片（桌面）

```
POST /api/v1/chat/image
Content-Type: multipart/form-data
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | file | 是 | 图片，≤10MB，`image/*` |
| `sessionId` | string | 是 | 会话 ID |
| `userId` | string | 是 | 用户 ID |
| `message` | string | 否 | 附加说明 |

流程：落盘 `upload/chat-images/` → Qwen-VL 识别 → 拼入用户消息 → 同 `send` 编排。响应同 `ChatResponseVO`，额外可带 `fileId`（媒体文件名）。

---

### 5.1c 发送文档（桌面）

```
POST /api/v1/chat/file
Content-Type: multipart/form-data
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | file | 是 | pdf/doc/docx/txt 等，≤50MB |
| `sessionId` | string | 是 | 会话 ID |
| `userId` | string | 是 | 用户 ID |
| `message` | string | 否 | 附加说明 |

流程：`DocumentService.upload` 同步解析 → 拼入用户消息 → 同编排。响应可带 `fileId` / `fileName` / `fileStatus`。

---

### 5.1d 读取聊天图片

```
GET /api/v1/chat/media/{fileName}
```

返回图片二进制（`Cache-Control: public, max-age=86400`）。`fileName` 仅允许安全文件名（防路径穿越）。

---

### 5.1e 桌面 SSE 流式（v2.30 / v2.32）

编排与 JSON 接口相同；**完整最终汇总落库后**再分片推送正文（非边生成边推 token）。v2.32 起，编排与工具执行过程中可额外推送 **`progress`**（不入消息历史）。

```
POST /api/v1/chat/send-stream     Content-Type: application/json
POST /api/v1/chat/image-stream    Content-Type: multipart/form-data
POST /api/v1/chat/file-stream     Content-Type: multipart/form-data
Accept: text/event-stream
```

请求体/表单字段分别与 `/send`、`/image`、`/file` 相同。

**SSE 事件**：

| event | data |
|-------|------|
| `progress` | `{ "text": "正在搜索网页…" }`（可选，可多条；桌面淡灰展示） |
| `meta` | `messageId` / `sessionId` / `finishReason` / `latencyMs`（及可选 confirmThreadId、fileId、attachments…） |
| `delta` | `{ "text": "片段" }` |
| `done` | `{ "finishReason": "STOP" \| "NEED_CONFIRM" \| "ERROR" }` |
| `error` | `{ "message": "…" }` |

典型顺序：`progress*` → `meta` → `delta*` → `done`。

分片速度（`ChatController` 常量）：约 2 字 / 片，间隔 28ms。

**落库**：桌面渠道由本 Controller 统一写入；`LlmService` 在 `channel=DESKTOP` 时跳过 DB，避免历史双份。微信路径仍由 `LlmService` 落库。

**确认**：`finishReason=NEED_CONFIRM` 时用户回复确认/取消续跑；确认执行中会话 busy，重复「确认」不会新开一轮编排。

桌面客户端默认调用 `*-stream`；JSON 接口保留兼容。

---

### 5.1f 生成文档下载（v2.32）

`generateDocument` 产物落在 `docgen.storage-path`（默认 `./AItext/yyyy-MM-dd/`），桌面附件通过：

```
GET /api/v1/chat/generated/{date}/{fileName}
```

`date` 形如 `2026-08-04`；`fileName` 需 URL 编码（可含中文）。返回文件正文；预览由客户端按扩展名处理（`.md` Markdown 渲染）。

---

### 5.2 获取历史消息

```
GET /api/v1/chat/history?sessionId=...
```

**响应 data**：消息列表。附件相关增强字段：

| 字段 | 说明 |
|------|------|
| `mediaUrl` | 图片历史还原路径，形如 `/api/v1/chat/media/{fileName}` |
| `fileName` | 文档原始名 |
| `displayText` | 用户附加说明（去掉系统前缀后） |
| `messageType` | `TEXT` / `IMAGE` / `FILE` |
| `fileIds` | 关联媒体或文档 ID |

> 仅本功能上线后新发的图片会有落盘与 `mediaUrl`；更早的纯文本历史不受影响。

---

### 5.3 清空会话上下文

```
POST /api/v1/chat/{sessionId}/clear
```

保留会话本身，清空消息历史。

---

## 8. 桌面通知模块（v2.29）

> 提醒到期时，微信走 iLink；桌面写入 `desktop_notification`，由客户端轮询。渠道由运行时 `Channel.DESKTOP|WECHAT` 注入，**不硬编码 userId**。

### 8.1 列出未读通知

```
GET /api/v1/desktop/notifications?userId=...&limit=20
```

**响应 data**（数组）:

| 字段 | 说明 |
|------|------|
| `notificationId` | 通知业务 ID |
| `sessionId` | 目标桌面会话 |
| `remindId` | 关联提醒 ID（可空） |
| `content` | 通知正文（如「🔔 提醒：…」） |
| `createdAt` | `yyyy-MM-dd HH:mm:ss` |

### 8.2 标记已读

```
POST /api/v1/desktop/notifications/{notificationId}/read?userId=...
POST /api/v1/desktop/notifications/read-all?userId=...
```

### 8.3 相关 DDL（需手动执行）

见仓库 `wechat-ai-session/src/main/resources/db/migration/V3__remind_channel.sql`：

- `remind.channel` / `remind.session_id`
- 表 `desktop_notification`

桌面方案细节见 `desktop-app-solution.md`。

### 8.4 欢迎页资讯（v2.32）

```
GET /api/v1/desktop/suggestions/news?limit=7
```

**响应 data**（数组）:

| 字段 | 说明 |
|------|------|
| `title` | 新闻标题（发给模型用） |
| `content` | 胶囊展示摘要；无效时可能等于 title |

服务端抓取国内中文热点，过滤外文与 JSON 脏摘要；无缓存，每次实时拉。失败可返回空数组。

### 8.5 用量统计（v2.32）

```
GET /api/v1/desktop/usage?userId=...&limit=30
```

**响应 data**:

| 字段 | 说明 |
|------|------|
| `summary` | 汇总：轮次、LLM/工具次数、Token、平均耗时等 |
| `recentTurns` | 最近若干轮明细（含 `at` / `latencyMs` / `totalTokens` / llmCalls / toolCalls） |
| `note` | 如「进程内统计，服务重启后清空」 |

进程内内存采集，**不落库**；仅统计本进程启动后的桌面对话。

---

## 9. 桌面邮箱登录模块（v2.31）

> **前缀**: `/api/v1/mail`（`ApiPrefix.MAIL`）  
> **实现**: `MailController` + `EmailCodeService` + `AppUserService`  
> **详解**: `桌面邮箱登录实现详解.md`

### 9.1 用户表 `app_user`

| 列 | 说明 |
|----|------|
| `user_id` | UUID，桌面聊天请求中的 `userId` |
| `email` | 登录账号（唯一，小写） |
| `created_at` / `updated_at` | 时间戳 |

迁移：`wechat-ai-session/.../V4__app_user.sql`。验证码仅内存存储（5 分钟 TTL，同邮箱 60 秒频控）。

微信 openid 与桌面 UUID **互不混用**；未登录桌面占位 `desktop-user`。

### 9.2 发送验证码

```
POST /api/v1/mail/send-code
Content-Type: application/json

{ "email": "xxx@qq.com" }
```

成功：`code=200`。失败：`400` + 文案（格式错误 / 发送过频 / SMTP 失败）。依赖 `spring.mail.*`（QQ SMTP）。

### 9.3 校验验证码（登录 / 注册）

```
POST /api/v1/mail/verify-code
Content-Type: application/json

{ "email": "xxx@qq.com", "code": "123456" }
```

成功 `data`：

```json
{ "userId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx", "email": "xxx@qq.com" }
```

逻辑：校验码 → 按 email 查找 `app_user`，无则创建 UUID 用户 → 返回。桌面将 `userId` 写入 `localStorage`，后续 chat / notification 均带此值。

### 9.4 前端配套行为（摘要）

- 未登录当前会话用户消息 ≥ 3 条后再发送 → 弹登录，输入框保留；登录成功自动发送
- 侧栏展示名：`用户` + 8 位数字（userId 派生，不落库）；可退出登录

---

## 6. 工具系统（核心设计）

### 6.0 工具体系总览

> **负责人**: 你（框架 + 注册机制）+ 传统 Java 组员（各工具实现）  
> **核心原则**: AI 是大脑，工具是手脚。所有对外部世界的操作都必须通过工具完成。

**为什么工具层是整个项目的核心？**

```
用户说："帮我看看这份合同的违约条款，然后发邮件给法务部确认"

AI 思考过程（LangGraph 图编排）：
  ① 调用 search_document → 检索合同中的违约条款
  ② LLM 理解条款内容 → 生成摘要
  ③ 调用 send_email → 将摘要发给法务部
  ④ 回复用户："已发送给法务部，内容如下..."

每一步对外部系统的操作都是一个工具调用。
没有工具层，AI 只能"说"不能"做"。
```

### 6.0.1 工具分类体系

工具有三类，**开发时就应该决定好分类，不同分类走不同的行为逻辑**：

| 分类 | 说明 | 示例 | 特点 |
|------|------|------|------|
| **🚹 查询工具 (READ)** | 不产生副作用，只读取数据 | 查文档、查天气、查数据库 | 可并发、可重试、幂等 |
| **✏️ 写入工具 (WRITE)** | 产生副作用，修改外部状态 | 发邮件、写数据库、创建日程 | 需确认、不可自动重试 |
| **🔧 系统工具 (SYSTEM)** | 控制 AI 自身行为 | 切换上下文、结束对话、请求人工 | 不对外部系统操作 |

分类影响行为：
- **READ 工具**：AI 可以自主决定调用，多次调用也不怕
- **WRITE 工具**：需要弹确认/风险提示（对于敏感操作），执行失败不能盲目重试
- **SYSTEM 工具**：改变 AI 自身状态，执行逻辑特殊

---

### 6.0.2 工具调用完整链路

> **v1.1 变更**: 工具执行由 AI 引擎内部通过 LangChain4j @Tool 自动完成，不再走 HTTP 端点调用。

```
用户消息 ──→  AI 引擎（LangGraph 图）
                  │
                  ├─ 无需工具 → LLM 直接回复 ──→ 返回给用户
                  │
                  └─ 需要工具 → LLM 输出 tool_calls
                                  │
                                  ▼
                          解析工具调用参数
                                  │
                                  ▼
                    ┌──── 判断工具分类 ────┐
                    │                      │
                   READ                 WRITE
                    │                      │
              LangChain4j @Tool      ⚠ 安全检查 / 确认
              自动执行（同 JVM）         │
                    │                  LangChain4j @Tool
                    │                  自动执行（同 JVM）
                    └──────┬───────────────┘
                           │
                    ┌──────┴──────┐
                    │             │
                 成功           失败
                    │             │
              结果结构化      ├─ retryable=true → AI 引擎自动重试
                    │         └─ retryable=false → 错误信息包装
                    │                   
                    └──────┬───────────┘
                           ▼
                   结果自动回填给 LLM
                           │
                    LLM 继续推理
                           │
                    ┌──────┴──────┐
                    │             │
                 又调工具     直接回复用户
```

---

### 6.0.3 工具开发流程（团队协作模式）

```
[你 - AI 引擎侧]                    [传统 Java 组员 - 工具实现侧]
                                    
1. 定义工具契约                           |
  └─ 确定工具名、功能描述                   |
  └─ 设计参数 JSON Schema                |
  └─ 确定返回结果格式                     |
         │                                |
         └──────────── 同步 ────────────→   |
                                         2. 实现工具业务逻辑
                                            └─ 写 MyBatis DAO
                                            └─ 调用外部 API
                                            └─ 处理异常
                                         3. 注册工具 Bean
                                            └─ @Component + @Tool
         │                                |
         ←───── 单元测试联调 ──────────────┘
         │
4. 在 AI 图编排中引用工具                   
5. 端到端测试                              
```

---

### 6.1 获取可用工具列表

返回当前会话下 AI 可用的全部工具定义（含分类信息）。

> **负责人**: 你（框架层）+ 传统 Java（各自工具的实现）

```
GET /api/v1/tools/list?sessionId=xxx&category=READ
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | string | 是 | 会话 ID |
| `category` | string | 否 | 筛选分类：`READ` / `WRITE` / `SYSTEM`，不传返回全部 |

**响应 data**:

```json
{
  "tools": [
    {
      "name": "search_document",
      "description": "在当前会话的已解析文档中搜索指定关键词或语义相关内容，返回匹配的文本段落",
      "category": "READ",
      "enabled": true,
      "parameters": {
        "type": "object",
        "properties": {
          "keywords": {
            "type": "string",
            "description": "搜索关键词，支持语义匹配"
          },
          "fileIds": {
            "type": "array",
            "items": { "type": "string" },
            "description": "限定搜索范围，不传则搜索会话下所有文档"
          },
          "maxResults": {
            "type": "integer",
            "description": "最大返回结果数",
            "default": 5
          }
        },
        "required": ["keywords"]
      }
    },
    {
      "name": "query_weather",
      "description": "查询指定城市当前天气情况",
      "category": "READ",
      "enabled": true,
      "parameters": {
        "type": "object",
        "properties": {
          "city": {
            "type": "string",
            "description": "城市名称，如：北京、上海"
          }
        },
        "required": ["city"]
      }
    },
    {
      "name": "send_email",
      "description": "发送电子邮件给指定收件人",
      "category": "WRITE",
      "enabled": true,
      "parameters": {
        "type": "object",
        "properties": {
          "to": {
            "type": "string",
            "description": "收件人邮箱地址"
          },
          "subject": {
            "type": "string",
            "description": "邮件主题"
          },
          "body": {
            "type": "string",
            "description": "邮件正文，支持纯文本格式"
          }
        },
        "required": ["to", "subject", "body"]
      }
    },
    {
      "name": "get_document_summary",
      "description": "获取指定文档的摘要信息",
      "category": "READ",
      "enabled": true,
      "parameters": {
        "type": "object",
        "properties": {
          "fileId": {
            "type": "string",
            "description": "文档 ID"
          }
        },
        "required": ["fileId"]
      }
    },
    {
      "name": "get_document_summary",
      "description": "获取指定文档的摘要信息",
      "category": "READ",
      "enabled": true,
      "parameters": {
        "type": "object",
        "properties": {
          "fileId": {
            "type": "string",
            "description": "文档 ID"
          }
        },
        "required": ["fileId"]
      }
    },
    {
      "name": "read_url",
      "description": "读取指定URL链接的网页正文内容并返回可读文本。当用户分享链接或询问某个网页内容时使用",
      "category": "READ",
      "enabled": true,
      "parameters": {
        "type": "object",
        "properties": {
          "url": {
            "type": "string",
            "description": "完整的URL地址，必须以http://或https://开头"
          }
        },
        "required": ["url"]
      }
    },
    {
      "name": "search_internet",
      "description": "搜索互联网获取最新信息。当用户需要实时信息或你不确定答案时使用",
      "category": "READ",
      "enabled": true,
      "parameters": {
        "type": "object",
        "properties": {
          "query": {
            "type": "string",
            "description": "搜索关键词，简明扼要的中文关键词"
          }
        },
        "required": ["query"]
      }
    },
    {
      "name": "toggle_chat_mode",
      "description": "切换 AI 对话模式：普通对话 / 深度分析 / 代码模式",
      "category": "SYSTEM",
      "enabled": true,
      "parameters": {
        "type": "object",
        "properties": {
          "mode": {
            "type": "string",
            "enum": ["NORMAL", "DEEP", "CODE"],
            "description": "对话模式"
          }
        },
        "required": ["mode"]
      }
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | string | 工具名，全局唯一，遵循 `snake_case` |
| `description` | string | 功能描述，**决定 LLM 是否选择这个工具，写得越清楚越好** |
| `category` | string | `READ` / `WRITE` / `SYSTEM` |
| `enabled` | boolean | 是否启用 |
| `parameters` | object | JSON Schema 格式（详见 6.5） |

> ⚠ **description 的编写质量直接影响 AI 选不选对工具** —— 描述要包含：什么时候用、参数怎么填、返回什么。  
> ❌ `"发送邮件"`  
> ✅ `"发送电子邮件给指定收件人。适用于用户要求通过邮件发送信息时，参数需要收件人地址、主题和正文"`

---

### 6.2 工具执行机制（v1.1 变更）

> **v1.1 重要变更**: 工具执行不再通过 HTTP 端点调用。AI 引擎通过 LangChain4j 的 `@Tool` 注解机制，在 JVM 内部**直接反射调用**已注册的 Tool Bean。  
> 原 `POST /api/v1/tools/execute` 接口已移除。

#### 执行流程

```
LLM 输出 tool_calls
      │
      ▼
LangChain4j 内置 ToolExecutor
      │
      ├─ 根据 tool_name 查找对应的 @Tool Bean（Spring 容器自动装配）
      ├─ 解析参数（JSON → Java 方法参数）
      ├─ 反射调用方法
      ├─ 将返回值序列化为 JSON 回填给 LLM
      └─ LLM 继续推理（或直接回复用户）
```

#### 为什么不需要 HTTP

| 之前的设计（v1.0，已废弃） | 现在的设计（v1.1） |
|---------------------------|-------------------|
| AI 引擎 → HTTP → 工具模块 | AI 引擎 → **方法调用** → 工具模块 |
| 需要序列化/反序列化两次 | 直接 Java 对象传递 |
| 需要额外的回调接口 | LangChain4j 自动管理往返 |
| 多一次网络开销 | 零开销 |

#### 工具实现规范（面向传统 Java 组员）

工具仍然由传统 Java 组员开发，和之前一样写 `@Component` + `@Tool` 注解即可，**不需要懂 LangChain4j 的内部机制**：

```java
@Component
public class SearchDocumentTool {

    private final DocumentContentService documentContentService;

    public SearchDocumentTool(DocumentContentService documentContentService) {
        this.documentContentService = documentContentService;
    }

    /**
     * @Tool 描述决定了 LLM 在什么场景下调用该工具
     * @P 描述了每个参数的含义
     * 返回普通 Java 对象即可，LangChain4j 自动转 JSON 回填给 LLM
     */
    @Tool("在当前会话的已解析文档中搜索指定关键词或语义相关内容")
    public SearchResult searchDocument(
        @P("搜索关键词，支持语义匹配") String keywords,
        @P("限定搜索范围，不传则搜索会话下所有文档") List<String> fileIds,
        @P("最大返回结果数，默认 5") @Max(20) Integer maxResults
    ) {
        // —— 参数校验 & 业务逻辑 ——
        if (keywords == null || keywords.trim().isEmpty()) {
            throw new IllegalArgumentException("关键词不能为空");
        }
        List<DocumentMatch> matches = documentContentService.search(
            keywords, fileIds, maxResults != null ? maxResults : 5);
        return new SearchResult(matches, matches.size());
    }
}
```

#### 工具执行结果格式规范

工具方法的返回值会由 LangChain4j 自动序列化为 JSON 并回填给 LLM。**结构要清晰、扁平、用自然语言描述代替代码缩写**：

| 原则 | ❌ 差 | ✅ 好 |
|------|-------|-------|
| 结构 | `{"code": "0", "data": {"list": [...]}}` | `{"matches": [...], "totalMatches": 1}` |
| 字段名 | `c`, `p`, `s` 等缩写 | `content`, `pageNum`, `score` 等自然名称 |
| 嵌套 | 层层包裹 | 扁平优先 |

#### 错误处理规范

工具方法抛出异常时，LangChain4j 会将异常信息回填给 LLM。为了让 LLM 能正确处理，**异常消息要写清楚**：

| 错误场景 | 异常消息示例 | AI 引擎处理策略 |
|---------|-------------|---------------|
| 文档未解析 | `"文档尚未完成解析，预计还需要 10 秒"` | 自动重试（最多 2 次） |
| 参数错误 | `"参数 city 只能填中国城市名，请重新提供"` | 向用户询问正确参数 |
| 网络超时 | `"查询天气服务暂时无响应"` | 重试 1 次，失败后告知用户 |
| 权限不足 | `"你没有权限查看该文档"` | 直接告诉用户无权限 |
| 资源不存在 | `"未找到文档 ID 对应的文档"` | 告诉用户文件不存在 |

> **💡 对传统 Java 组员来说**：写工具 = 写一个带 `@Tool` 注解的 Spring Bean，和平常写 Service 一模一样。  
> 方法返回的对象自动变成 LLM 能看懂的 JSON。异常消息也要写得像人话，因为 LLM 真的会"读"它。

---

### 6.3 工具参数 JSON Schema 规范（核心）

这是团队成员**写工具时必须遵循的规范**，决定了 LLM 能否正确生成参数。

#### 6.3.1 基础规则

```json
{
  "name": "工具名",
  "description": "工具描述",
  "parameters": {
    "type": "object",
    "properties": {
      "参数名": {
        "type": "string | integer | number | boolean | array | object",
        "description": "参数描述"
      }
    },
    "required": ["必填参数名列表"]
  }
}
```

#### 6.3.2 各类型示例

**string 类型**:

```json
{
  "email": {
    "type": "string",
    "description": "收件人邮箱地址"
  }
}
```

**enum 枚举类型**:

```json
{
  "priority": {
    "type": "string",
    "enum": ["HIGH", "MEDIUM", "LOW"],
    "description": "邮件优先级"
  }
}
```

**integer/数字类型**:

```json
{
  "maxResults": {
    "type": "integer",
    "description": "返回结果数量上限",
    "minimum": 1,
    "maximum": 20
  }
}
```

**array 数组类型**:

```json
{
  "fileIds": {
    "type": "array",
    "items": { "type": "string" },
    "description": "文档 ID 列表",
    "minItems": 1,
    "maxItems": 10
  }
}
```

**嵌套 object 类型**:

```json
{
  "filter": {
    "type": "object",
    "properties": {
      "startDate": { "type": "string", "description": "开始日期 yyyy-MM-dd" },
      "endDate": { "type": "string", "description": "结束日期 yyyy-MM-dd" }
    },
    "description": "过滤条件"
  }
}
```

> **编写要点**:
> 1. `description` 要写清楚参数含义和格式，LLM 看不懂缩写
> 2. `enum` 类型优先用（限制 LLM 的自由度，减少参数错误）
> 3. 数字类型尽量加 `minimum` / `maximum` 范围约束
> 4. **参数名用 `camelCase`** 保持统一

---

### 6.4 注册新工具

> **调用方**: 你（新增工具时注册）  
> **实现方**: 传统 Java 组员在各自的包内开发

工具注册有两种方式，**建议先用注解方式**：

#### 方式一：@Tool 注解（推荐，最常用）

```java
@Component
@RequiredArgsConstructor
public class SearchDocumentTool {

    private final DocumentContentService documentContentService;

    @Tool("在当前会话的已解析文档中搜索指定关键词或语义相关内容，返回匹配的文本段落")
    public SearchResult searchDocument(
        @P("搜索关键词，支持语义匹配") String keywords,
        @P("限定搜索范围，不传则搜索会话下所有文档") List<String> fileIds,
        @P("最大返回结果数") @P("maxResults") @Max(20) int maxResults
    ) {
        // 实现逻辑
        return documentContentService.search(keywords, fileIds, maxResults);
    }
}
```

LangChain4j 自动扫描 `@Tool` 注解，将方法转为工具定义注入 AI 引擎。

---

### 6.5 工具错误处理规范

工具执行可能失败，**错误信息必须设计成 LLM 能理解**，这样 AI 才知道怎么处理。

| 错误场景 | error.code | error.message 示例 | retryable | AI 处理策略 |
|---------|-----------|-------------------|-----------|-----------|
| 文档未解析 | `NOT_PARSED` | "文档尚未完成解析，预计还需要 10 秒" | true | 等待后重试 |
| 参数错误 | `INVALID_ARGUMENTS` | "参数 city 只能填中国城市名，请重新提供" | false | 向用户询问正确参数 |
| 网络超时 | `TIMEOUT` | "查询天气服务暂时无响应" | true | 重试 1 次，失败后告诉用户 |
| 权限不足 | `FORBIDDEN` | "你没有权限查看该文档" | false | 直接告诉用户无权限 |
| 服务不可用 | `SERVICE_UNAVAILABLE` | "邮件服务暂时不可用，请稍后重试" | true | 告诉用户稍后重试 |
| 资源不存在 | `NOT_FOUND` | "未找到文档 ID 对应的文档" | false | 告诉用户文件不存在 |
| 限流 | `RATE_LIMITED` | "操作太频繁，请 30 秒后再试" | true | 等待后重试 |

**AI 引擎对 retryable 的处理逻辑（LangGraph 图编排）**:

```
工具执行失败
   │
   ├─ retryable = true
   │     ├─ 重试次数 < 2 → 自动重试（等待 1s）
   │     └─ 重试次数 >= 2 → 告诉用户"重试后仍然失败，请稍后重试"
   │
   └─ retryable = false
         └─ 直接向用户说明错误原因，引导用户提供正确信息
```

### 6.6 工具管理 API（运维用）

#### 6.6.1 查询工具调用历史

```
GET /api/v1/tools/history?sessionId=xxx
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | string | 是 | 会话 ID |

**响应 data**:

```json
[
  {
    "toolCallId": "call_abc123",
    "toolName": "search_document",
    "arguments": {"keywords": "违约条款"},
    "success": true,
    "resultSummary": "找到 3 条匹配结果",
    "latencyMs": 850,
    "calledAt": "2026-07-15 10:30:00"
  }
]
```

> 用于排查问题和调试，方便看 AI 在背后调了哪些工具。

#### 6.6.2 禁用/启用工具

```
POST /api/v1/tools/{toolName}/toggle
```

**请求体**:

```json
{
  "enabled": false
}
```

> 当某个工具出故障时，可以临时禁用，AI 就不会再调用它。

---

### 6.7 当前工具清单

基于当前需求，已实现以下 **39 个工具**（v2.18）：
- **26 个本地 @Tool**（同 JVM 反射调用）
- **13 个 MCP 外部工具**（RestClient 直调滴滴出行 API，非 @Tool）

#### READ 工具（33 个 — 直接执行，无需确认）

**本地 @Tool（20 个）**：

| 工具名 | 说明 | 实现类 | 状态 |
|--------|------|--------|:----:|
| `queryWeather` | 查询指定城市实时天气 | `QueryWeatherTool` | ✅ |
| `searchDocument` | MySQL FULLTEXT + LIKE 文档搜索 | `SearchDocumentTool` | ✅ |
| `getDocumentSummary` | 获取文档摘要 | `GetDocumentSummaryTool` | ✅ |
| `readUrl` | Jsoup 抓取网页正文 | `ReadUrlTool` | ✅ |
| `searchInternet` | 必应搜索互联网 | `ReadUrlTool` | ✅ |
| `searchConversation` | 搜索历史对话记录 | `ConversationSearchTool` | ✅ |
| `translate` | 多语言翻译（15 种语言） | `TranslateTool` | ✅ **v2.14** |
| `queryStock` | A/H/美股行情查询 | `QueryStockTool` | ✅ **v2.14** |
| `rollDice` | 掷骰子 | `DiceTool` | ✅ **v2.14** |
| `getDailyWaifu` | 每日老婆 | `DailyWaifuTool` | ✅ **v2.14** |
| `queryOnThisDay` | 历史上的今天 | `OnThisDayTool` | ✅ **v2.14** |
| `makeRandomChoice` | 随机选择 | `RandomChoiceTool` | ✅ **v2.14** |
| `downloadAsMarkdown` | 公众号文章转 Markdown | `WeChatArticleDownloaderTool` | ✅ **v2.15** |
| `navigate` | 打开指定网页（Playwright） | `BrowserTool` | ✅ **v2.16** |
| `getPageElements` | 获取页面可交互元素 | `BrowserTool` | ✅ **v2.16** |
| `click` | 点击页面元素 | `BrowserTool` | ✅ **v2.16** |
| `fill` | 填写输入框 | `BrowserTool` | ✅ **v2.16** |
| `getText` | 获取页面文本内容 | `BrowserTool` | ✅ **v2.16** |
| `goBack` | 浏览器后退 | `BrowserTool` | ✅ **v2.16** |
| `goForward` | 浏览器前进 | `BrowserTool` | ✅ **v2.16** |

**MCP 外部工具（13 个 — 滴滴出行）**：

| 工具名 | 说明 | 分类 | 状态 |
|--------|------|:----:|:----:|
| `maps_textsearch` | POI 地点搜索（关键词+城市） | READ | ✅ **v2.17** |
| `maps_regeocode` | 经纬度 → 地址信息 | READ | ✅ **v2.17** |
| `maps_place_around` | 周边 POI 搜索 | READ | ✅ **v2.17** |
| `maps_direction_driving` | 驾车路线规划 | READ | ✅ **v2.17** |
| `maps_direction_transit` | 公交/地铁路线规划 | READ | ✅ **v2.17** |
| `maps_direction_walking` | 步行路线规划 | READ | ✅ **v2.17** |
| `maps_direction_bicycling` | 骑行路线规划 | READ | ✅ **v2.17** |
| `taxi_estimate` | 查看可用车型与预估价格 | READ | ✅ **v2.17** |
| `taxi_generate_ride_app_link` | 生成打车跳转链接 | READ | ✅ **v2.17** |
| `taxi_query_order` | 查询订单状态 | READ | ✅ **v2.17** |
| `taxi_get_driver_location` | 获取司机实时位置 | READ | ✅ **v2.17** |
| `taxi_create_order` | **创建打车订单** | **WRITE** ⚠️ | ✅ **v2.17** |
| `taxi_cancel_order` | **取消打车订单** | **WRITE** ⚠️ | ✅ **v2.17** |

> 浏览器操作工具由 Playwright 驱动 Chrome/Edge 实现。每次 `navigate` 后需先调 `getPageElements` 了解页面结构，再调 `click`/`fill` 操作。

> MCP 工具接入说明：详见 [6.10 MCP 外部工具接入](#610-mcp-外部工具接入-v217)。

#### WRITE 工具（6 个 — 需用户确认）

**本地 @Tool（4 个）**：

| 工具名 | 说明 | 实现类 | 状态 |
|--------|------|--------|:----:|
| `setRemind` | 设置定时提醒 | `SetRemindTool` | ✅ **v2.12** |
| `generateDocument` | AI 生成文档（txt/docx）并录入文档库 | `GenerateDocumentTool` | ✅ **v2.14** |
| `sendEmail` | QQ SMTP 发送电子邮件 | `SendEmailTool` | ✅ **v2.14** |
| `transformContent` | 内容风格转换 | `TransformContentTool` | ✅ **v2.15** |
| `screenshot` | 截取页面截图并自动发送 | `BrowserTool` | ✅ **v2.16** |

**MCP 外部工具（2 个）**：

| 工具名 | 说明 | 来源 | 状态 |
|--------|------|:----:|:----:|
| `taxi_create_order` | 创建打车订单 | 滴滴出行 MCP | ✅ **v2.17** |
| `taxi_cancel_order` | 取消打车订单 | 滴滴出行 MCP | ✅ **v2.17** |

**分类逻辑**：
- 类上标注 `@WriteTool` 注解 → WRITE（本地工具）
- 按名匹配 `WRITE_TOOLS` 集合 → WRITE（本地工具）
- MCP 工具在 `McpToolBridge.WRITE_MCP_TOOLS` 中声明 → WRITE
- 其余默认 READ

**工具名即为 `@Tool` 方法名**（camelCase，MCP 工具名由滴滴定义）。

### 6.8 Skills 系统（v2.10 新增）

工具格式规则从 SYSTEM_PROMPT 拆分为独立的 Skill 组件，绑定到对应工具动态加载：

| Skill | 绑定工具 | 规则内容 | 源文件 |
|-------|---------|---------|--------|
| `WeatherSkill` | `queryWeather` | 🌤 天气格式 + 下雨/晴天景点推荐组合规则 | `WeatherSkill.java` |
| `DocumentSkill` | `searchDocument`, `getDocumentSummary` | 📄 文档检索 + 摘要格式 | `DocumentSkill.java` |
| `WebSearchSkill` | `readUrl`, `searchInternet` | 🔗 网页搜索与链接处理规则 | `WebSearchSkill.java` |
| `ConversationSearchSkill` | `searchConversation` | 💬 历史对话搜索规则 | `ConversationSearchSkill.java` |
| `RemindSkill` | `setRemind` | ⏰ 提醒时间处理规则 + 设置阶段只调 setRemind，其他需求存 content | `RemindSkill.java` |
| `TranslateSkill` | `translate` | 🌐 翻译结果格式 + 语言学习场景附加音标/例句规则 | `TranslateSkill.java` |
| `DocumentGenSkill` | `generateDocument` | 📝 文档生成规范：内容结构、回复格式、不重复展示全文 | `DocumentGenSkill.java` |
| `EmailSkill` | `sendEmail` | 📮 发送前必须向用户展示收件人/主题/内容并确认；不添加额外签名 | `EmailSkill.java` |

**加载机制**：`llm_think` 前根据上一轮 `toolExecutionRequests` 匹配 → 拼入 system prompt
**卸载机制**：`invoke()` 结束 → state 销毁 → 自动卸载（零销毁代码）
**设计意图**：价值随工具数量增长。RemindSkill 体现了 Skill 的核心价值——将「相对时间处理」「设置阶段行为限制」等规则从 @Tool 描述中剥离，让 LLM 按规则办事而非靠碰运气。

### 6.10 MCP 外部工具接入（v2.17 → v2.18 重构为多源框架）

> **零依赖方案**：使用 Spring `RestClient` 直调 JSON-RPC 2.0，不引入任何 MCP SDK。
> 对比 `langchain4j-mcp:1.0.0-beta5` 库仅支持旧版 SSE 传输层，滴滴 Streamable HTTP 协议无法兼容。

#### 多源架构

```
ToolRegistry（统一注册中心）
  ↑
McpToolBridge（ApplicationReadyEvent 遍历所有源注册）
  ↑
McpClientManager（多源管理器）
  ├── toolIndex: Map<toolName, McpSource>
  └── sources: List<McpSource>
        ├── DidiMcpSource  ("MCP:DIDI")
        └── (未来) xxxMcpSource

ToolExecuteNode.apply()
  ├─ isExternalTool()? → McpClientManager.executeTool()
  └─ 否则 → 反射本地 @Tool Bean
```

#### 代码架构（6 个文件）

| 文件 | 职责 | 类型 |
|------|:----|:----:|
| `McpSource.java` | MCP 源抽象接口 | **新增** |
| `McpToolDefinition.java` | MCP 工具定义 POJO | **新增** |
| `McpConfigProperties.java` | 滴滴 MCP 配置映射 | 已有 |
| `DidiMcpSource.java` | 滴滴出行 MCP 实现 | **新增** |
| `McpClientManager.java` | 多源管理器 | 改 |
| `McpToolBridge.java` | ApplicationReadyEvent 遍历注册 | 改 |

#### 配置项

```properties
# 启用 MCP（默认关闭）
mcp.didi.enabled=true
# API Key（从 https://mcp.didichuxing.com/ 获取）
mcp.didi.api-key=your_key_here
# 沙箱环境（调试用，正式切换为 false）
mcp.didi.sandbox=true
# 连接超时（秒）
mcp.didi.timeout-seconds=30
```

#### WRITE 工具标记

各 MCP 源在自己实现中声明 WRITE 工具：

```java
// DidiMcpSource.java
@Override
public Set<String> getWriteToolNames() {
    return Set.of("taxi_create_order", "taxi_cancel_order");
}
```

#### ToolRegistry 接口扩展

```java
void registerExternalTool(ToolDefinitionVO def, String source);     // 注册 MCP 工具
boolean isExternalTool(String toolName);                              // 判断是否外部工具
String executeExternalTool(String toolName, String argsJson);         // 执行外部工具
```

#### 执行流程

```
ToolExecuteNode.apply()
  ① 解析参数 → Map<String, Object> argsMap
  ② 判断 isExternalTool(toolName)？
     ├─ 是 → McpClientManager.executeTool(toolName, argsJson)
     │         → RestClient POST JSON-RPC 2.0 → 滴滴 MCP Server
     │         → 返回结果 JSON → 回填 messageHistory
     │         → continue（跳过本地反射）
     └─ 否 → 找 @Tool Bean → 反射执行（原逻辑不变）
```

#### 当前接入的 MCP Server

| 服务商 | Server URL | 认证方式 | 工具数 |
|--------|-----------|:--------:|:------:|
| 滴滴出行 | `https://mcp.didichuxing.com/mcp-servers?key=API_KEY` | URL Query Key | 13 |

#### 可扩展性：新增一个 MCP 源只需一个文件

当前架构基于 `McpSource` 接口，新增一个 MCP 源只需新建一个类实现该接口：

```java
@Component
public class AmapMcpSource implements McpSource {

    @Override
    public String getSourceName() {
        return "MCP:AMAP";
    }

    @Override
    public List<McpToolDefinition> fetchTools() {
        // 别担心，${escape} 是正常的——Jinja 模板语法不关它的事。
        // 调 MCP Server 的 tools/list → 返回工具列表
    }

    @Override
    public String executeTool(String toolName, String argsJson) {
        // 调 MCP Server 的 tools/call → 返回执行结果
    }

    @Override
    public Set<String> getWriteToolNames() {
        return Set.of(); // 没有需要确认的工具
    }
}
```

**一个文件，零改动现有代码，重启后自动注册。**

不受任何第三方 MCP SDK 的 API 限制。当前 6 个文件均为自有代码，可自由修改和扩展。

> 💡 **多 Agent 协作**：MCP source 前缀天然适合做 Agent 分组边界。v2.19 起，工具按 source 自动分组为专职 Agent（如 `MCP:DIDI` → TaxiAgent），中央 Orchestrator 只看到 2 个"专家工具"。详见 `MCP多Agent协作设计.md`。

### 6.11 工具实现规范（面向传统 Java 组员）

为了方便不熟悉 LangChain4j 的组员也能快速上手写工具，以下是他们在实现工具时必须遵守的规范：

#### Java 代码规范

```java
/**
 * 工具类命名规范: XxxTool（功能名 + Tool 后缀）
 * 包路径: com.wechatai.tool.impl
 */
@Component  // 注册为 Spring Bean
public class SearchDocumentTool {
    
    // 依赖注入，使用构造器注入
    private final DocumentContentService documentContentService;
    
    public SearchDocumentTool(DocumentContentService documentContentService) {
        this.documentContentService = documentContentService;
    }

    /**
     * 工具方法规范:
     * 1. 方法上 @Tool("描述") — 描述决定了 LLM 在什么场景下调用该工具
     * 2. 参数上 @P("描述") — 每个参数都要写清楚含义
     * 3. 返回值 — 返回普通 Java 对象即可，框架自动转 JSON
     * 4. 方法名 — 见名知意，camelCase
     */
    @Tool("在当前会话的已解析文档中搜索指定关键词或语义相关内容")
    public SearchResult searchDocument(
        @P("搜索关键词，支持语义匹配") String keywords,
        @P("限定搜索范围，不传则搜索会话下所有文档") List<String> fileIds,
        @P("最大返回结果数，默认 5") @Max(20) Integer maxResults
    ) {
        // ---- 实现要点 ----
        // 1. 参数校验 & 异常处理
        if (keywords == null || keywords.trim().isEmpty()) {
            throw new IllegalArgumentException("关键词不能为空");
        }
        
        // 2. 业务逻辑
        List<DocumentMatch> matches = documentContentService.search(keywords, fileIds, 
            maxResults != null ? maxResults : 5);
        
        // 3. 返回结构清晰的对象
        return new SearchResult(matches, matches.size());
    }
}
```

#### 需要导入的 Maven 依赖

```xml
<!-- LangChain4j 工具注解支持（你们只需要知道这个存在，你负责配好） -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-core</artifactId>
</dependency>
```

> **🎯 对传统 Java 组员来说**: 写工具 = 写一个带 `@Tool` 注解的 Spring Bean，其他和平时写 Service 一模一样。  
> 不懂 LangChain4j 的细节没关系，你帮他们搭好架子，他们只关心业务逻辑实现。

---

## 7. 微信接入模块

> **说明**: 当前使用 iLink Java SDK（`io.github.lith0924:wechat-ilink-sdk:2.3.3`）直连微信，不走公众号 HTTP 回调。
> 已废弃的 REST 接口（7.1 验证 / 7.2 回调接收 / 7.4 Token）已在 v2.2 中清理。

### 7.1 iLink SDK 接入（当前方案）

微信接入通过 iLink Java SDK 直接实现，无需 HTTP 回调端点：

| 功能 | 实现方式 |
|------|---------|
| 登录 | `client.executeLogin()` 生成二维码，手机微信扫码；或从 `WechatLoginStore` 恢复持久化状态 |
| 登录持久化 | `client.exportResumeContext()` 导出 → JSON 文件；`builder.resumeContext(ctx)` 恢复 → 跳过扫码 |
| 消息拉取 | SDK 内置 heartbeat（`heartbeatIntervalMs=3000`，3s），`.onMessage()` 回调分发 |
| 发消息 | `client.sendText(fromUser, content)` 主动推送 |
| 发送图片 | `client.sendImage(fromUser, data, fileName, ext)` 发送行内图片 |
| 发送文件 | `client.sendFile(fromUser, data, fileName, ext)` 发送文件 |
| 文件/图片/语音下载 | `client.downloadXxxFromMessageItem(item)` 自动解密 |

**登录持久化流程**：

```
启动 → WechatLoginStore.load() 读取 ./wechat-login/login-state.json
  ├─ 文件存在 → 重建 ResumeContext → builder.resumeContext(ctx) → 跳过扫码 ✅
  └─ 文件不存在 → 正常扫码登录 → exportResumeContext() → 存文件

运行期间 → onHeartbeatSuccess 每次心跳刷新文件，token 自动保活
```

**消息分发规则**（`WechatMessageDispatcher.dispatchItem()` → `WechatInboundHandler`）：

| item.type | 消息类型 | 处理方式 |
|:---------:|:--------:|---------|
| 1 | 文本 | 走 AI 引擎 |
| 2 | 图片 | Qwen-VL 多模态识别 → 拼文字 → 走 AI 引擎 |
| 3 | 语音 | SDK 2.3.3 VoiceItem.getText() 取微信转写文字 → 走 AI 引擎 |
| 4 | 文件 | 预留，走文档上传 |

### 7.2 多轮对话上下文保持（消息记忆）

**问题**：每次消息都走 `graph.compile().invoke(initData)`，图执行完毕后状态销毁。上一轮的 `messageHistory` 丢失，LLM 每次都是"第一次见到你"，无法记住上下文。

**解决方案**：`WechatChatBridge` 中用 `ConcurrentHashMap` 按 userId 缓存消息历史。每次调图前传入历史，跑完后取回更新。

#### 数据结构

```java
// key = 微信用户ID (openid), value = 该用户的消息历史列表
private final Map<String, List<Map<String, Object>>> userContexts = new ConcurrentHashMap<>();
```

#### 完整流程

```
用户发消息 "我叫小明"
    │
    ├─ userContexts.get(fromUser) → null（首次对话）
    │
    ├─ initData.put("messageHistory", null) → 图内 LlmService 自动创建 [system, user]
    │
    ├─ graph.invoke() → LlmService 返回 messageHistory
    │
    ├─ 取 state.data().get("messageHistory")
    │   = [system, user("我叫小明"), assistant("你好小明！")]
    │
    └─ userContexts.put(fromUser, messageHistory)  ← 存起来
                           │
用户再发 "我叫什么名字"     │
    │                      │
    ├─ userContexts.get(fromUser) → 取出历史
    │   = [system, user("我叫小明"), assistant("你好小明！")]
    │
    ├─ initData.put("messageHistory", 上面的历史)
    │
    ├─ graph.invoke()
    │   ├─ LlmService 发现已有 messageHistory → 直接追加当前 user
    │   └─ 发 DeepSeek: [system, user("小明"), assistant("你好"), user("叫什么")]
    │      → LLM: "你刚才说过啦，你叫小明！"
    │
    └─ userContexts.put(fromUser, 更新后的历史)
```

#### 边界说明

| 场景 | 行为 |
|------|------|
| 用户首次发消息 | `history == null`，LlmService 自动创建 `[system, user]` |
| 用户连续对话 | 历史累积，每次追加当前消息和 assistant 回复 |
| 进程重启 | `ConcurrentHashMap` 清空 → 相当于首次对话 |
| 多用户同时对话 | 按 userId 隔离 |

#### 后续优化方向

| 方向 | 说明 | 优先级 |
|------|------|--------|
| Redis 持久化 | 替代 ConcurrentHashMap，重启不丢失 | 高 |
| 消息上限 | 限制每用户 50 轮，超出丢弃最早 | 中 |
| Token 控制 | 接近上限时自动摘要压缩 | 低 |

### 7.3 多账号架构（v2.22+）

> **开关**: `wechat.multi-account.enabled`（默认 `false`，完全向后兼容）

#### 架构概览

```
单账号（enabled=false，默认）              多账号（enabled=true）
───────────────────────────               ──────────────────────────────
WechatConnectionManager                  WechatClientRegistry
  └── ILinkClient (单例)                   ├── bot-1 → ManagedWechatClient
                                          │     ├── ILinkClient (独立)
                                          │     ├── ClientHeartbeatListener
                                          │     └── onMessage → publishEvent
                                          ├── bot-2 → ManagedWechatClient
                                          └── ...
                                         WechatMessageEventListener
                                           └── @EventListener + userId→client 映射
```

#### 条件装配

| Bean | enabled=false | enabled=true |
|------|:--:|:--:|
| `WechatConnectionManager` | ✅ | ❌ |
| `WechatLoginStore` | ✅ | ❌ |
| `WechatMessageDispatcher` | ✅ | ❌ |
| `WechatClientRegistryImpl` | ❌ | ✅ |
| `WechatLoginStateStoreImpl` | ❌ | ✅ |
| `WechatMessageEventListener` | ❌ | ✅ |

#### 消息流（多账号）

```
ILinkClient(bot-N) → onMessage 回调
  → WechatInboundBatchEvent(clientId, messages)
  → WechatMessageEventListener.onInboundBatch()
    → recordUserClient(fromUser → clientId)     // 记住用户属于哪个 Bot
    → InboundHandler.handleTextByKey(conversationKey)
      → ChatBridge.thinkByKey(conversationKey)
        → OutboundSender.getActiveClientForUser(fromUser)
          → 查找正确 Bot → sendText ✅
```

#### 隔离设计

| 维度 | 隔离键 | 实现 |
|------|--------|------|
| Bot 登录态 | `clientId` | `./wechat-login/clients/{clientId}.json` |
| 消息路由 | `clientId` | Event 携带 + EventListener 分发 |
| AI 对话上下文 | `conversationKey = clientId:fromUserId` | WechatChatBridge 适配层 |
| Session | `(wechatClientId, userId)` | MyBatis `<if test>` 条件 SQL |
| Remind | `(wechatClientId, userId)` | 同上 |
| 消息幂等 | `clientId:messageId` | Caffeine Cache（60min TTL） |

#### 配置参考

```properties
# 多账号开关（默认 false）
wechat.multi-account.enabled=true
# 启动时自动创建的 Bot 数
wechat.multi-account.initial-clients=2

# Client 参数
wechat.client.max-count=20
wechat.client.heartbeat-interval-ms=3000
wechat.client.read-timeout-ms=35000
wechat.client.rebuild-after-failures=10
wechat.client.rebuild-after-duration-ms=300000

# 登录态存储
wechat.login.store-directory=./wechat-login/clients

# 对话
wechat.conversation.executor-idle-timeout-ms=1800000

# 消息幂等
wechat.inbound.idempotency-ttl-minutes=60
wechat.inbound.idempotency-max-size=100000
```

#### Admin API

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/admin/wechat-clients` | 创建新 client，返回二维码 URL |
| `GET` | `/api/admin/wechat-clients` | 列出所有 client 状态 |
| `GET` | `/api/admin/wechat-clients/{id}` | 单个 client 详情 |
| `GET` | `/api/admin/wechat-clients/{id}/qrcode` | 获取二维码 URL |
| `POST` | `/api/admin/wechat-clients/{id}/relogin` | 强制重新登录 |
| `DELETE` | `/api/admin/wechat-clients/{id}` | 删除 client |

#### DB 迁移

```sql
ALTER TABLE session ADD COLUMN wechat_client_id VARCHAR(36) NULL AFTER user_id;
ALTER TABLE remind ADD COLUMN wechat_client_id VARCHAR(36) NULL AFTER user_id;
CREATE INDEX idx_session_client_user_active ON session (wechat_client_id, user_id, status, expire_at);
CREATE INDEX idx_remind_client_pending ON remind (wechat_client_id, status, remind_at);
```

所有新列为 NULL，MyBatis `<if test="wechatClientId != null">` 条件 SQL，单账号模式完全不受影响。

#### 使用步骤

```bash
# 1. 执行 DB 迁移（SQL 见上）
# 2. 修改 application.properties
wechat.multi-account.enabled=true
wechat.multi-account.initial-clients=2
# 3. 启动
mvn spring-boot:run -pl wechat-ai-starter
# 控制台输出 2 个二维码，分别扫码登录
```

#### 新增文件清单

```
wechat-ai-common/src/main/java/com/wechatai/common/
├── enums/ClientStatus.java
├── model/WechatMessageContext.java
└── model/ConversationKey.java

wechat-ai-wechat/src/main/java/com/wechatai/wechat/
├── event/WechatInboundBatchEvent.java
├── config/WechatMultiAccountProperties.java
├── config/WechatMultiAccountConfig.java
├── WechatClientRegistry.java
├── WechatClientRegistryImpl.java
├── WechatClientHandle.java
├── WechatClientHandleImpl.java
├── WechatLoginStateStore.java
├── WechatLoginStateStoreImpl.java
├── ClientLoginData.java
├── WechatConversationGateway.java
├── WechatMessageSender.java
├── WechatMessageEventListener.java
├── controller/WechatClientAdminController.java
├── model/WechatMediaDescriptor.java
└── exception/
    ├── ClientNotFoundException.java
    ├── ClientLimitReachedException.java
    └── MigrationAmbiguousException.java
```

---

## 附录 A: Redis Key 规范

| Key 模式 | 类型 | 说明 | TTL |
|----------|------|------|-----|
| `session:{sessionId}` | String | 会话元数据（JSON） | 24h |
| `session:{sessionId}:messages` | List | 最近 N 条消息 | 24h |
| `file:{fileId}:content` | String | 文档文本摘要（快速检索用） | 24h |
| `task:queue:doc_parse` | List | 文档解析任务队列 | - |
| `task:status:{taskId}` | String | 任务状态 | 72h |
| `wechat:token` | String | 微信 Access Token | 续期 |
| `rate:limit:{userId}` | ZSet | 用户频率限制 | 1h |

> **说明**: Redis 主要承担 3 个角色：**缓存**（加速读取）、**队列**（异步解耦）、**限流**。
> MySQL 为最终数据主库，Redis 中的数据允许一定时间差内的不一致。

---

## 附录 B: 阿里云部署注意事项

### B.1 端口规划

| 服务 | 端口 | 说明 |
|------|------|------|
| Spring Boot 主应用 | 8080 | 对外 HTTP |
| MySQL | 3306 | 内网不对外暴露 |
| Redis | 6379 | 内网不对外暴露 |
| Nginx (可选) | 80/443 | 反向代理 + SSL 证书 |

### B.2 目录规划

```
/data/wechat-ai/
├── logs/             # 应用日志
├── files/            # 文档文件存储
│   ├── upload/       # 原始上传文件
│   └── parsed/       # 解析后的文本缓存
└── backup/           # 数据库及配置备份
```

### B.3 文件大小限制配置

```properties
# application.properties
spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=60MB
```

---

## 附录 C: 开发协作规范

### C.1 分支策略

```
main          → 生产分支，保护
├─ dev        → 开发主分支，所有人从此拉分支
│  ├─ feat/session      → 会话模块
│  ├─ feat/document     → 文档模块
│  ├─ feat/ai-engine    → AI 引擎
│  ├─ feat/wechat       → 微信接入
│  └─ feat/tools        → 工具模块
└─ fix/*      → 修复分支
```

### C.2 接口变更流程

1. **修改前**：在群内告知其他组成员
2. **修改后**：更新本接口文档（文档即契约）
3. **影响范围**：检查调用方是否需要同步修改
4. **Merge 前**：确认接口兼容性

### C.3 共用工具类/模型放置规范

```
com.wechatai
├── common/                  # 公共模块（所有人共用）
│   ├── model/
│   │   ├── Result.java      # 统一响应体
│   ├── enums/
│   │   └── ErrorCode.java   # 错误码枚举
│   └── util/
│       ├── TraceIdUtil.java
│       └── JsonUtil.java
├── session/                 # 会话模块（传统 Java）
├── document/                # 文档模块（传统 Java）
├── ai/                      # AI 引擎（你）
├── wechat/                  # 微信接入（传统 Java）
└── tool/                    # 工具模块（你 + 协助）
```

> **关键规则**: `common` 模块不依赖任何业务模块。  
> 业务模块之间通过接口调用，不直接引用对方 DAO/BEAN。

---

## 附录 D: POJO 类定义大全

### D.0 包路径总览

```
com.wechatai
├── common.model          ── 通用模型（所有人共用）
├── common.enums          ── 通用枚举
├── common.constant       ── 通用常量
├── common.util           ── 通用工具
├── session.entity        ── 会话数据实体
├── session.model.request ── 会话请求体
├── session.model.vo      ── 会话响应体
├── document.entity       ── 文档数据实体
├── document.model.request
├── document.model.vo
├── ai.entity             ── 消息数据实体
├── ai.model.request
├── ai.model.vo
├── tool.model.dto        ── 工具数据传输
│   ├── tool.model.vo
│   └── task.entity
│       task.model.vo
```

> 每个模块下的 `model.request` 和 `model.vo` 可根据实际情况合并或拆分，这里分开只为清晰区分入参与出参。
>
> ⚠ **关于 VO**：虽然项目依托公众号没有传统前端，但 VO 仍用于定义接口响应的固定结构，供调用方（其他模块、运维排查、调试工具）依赖。可理解为"响应格式定义"而非"页面视图"。

---

### D.1 通用模型 (common.model)

```java
package com.wechatai.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体 — 所有接口统一返回此结构
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private int code;            // 业务码，200 成功
    private String message;      // 提示信息
    private T data;              // 业务数据
    private String traceId;      // 链路追踪 ID
    private long timestamp;      // 服务端时间戳 (ms)

    // ── 快速构建静态方法 ──

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data, null, System.currentTimeMillis());
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null, null, System.currentTimeMillis());
    }

    public static <T> Result<T> error(int code, String message, String traceId) {
        return new Result<>(code, message, null, traceId, System.currentTimeMillis());
    }
}
```

<!-- PageReq / PageResp 已移除：第一期暂不需要分页，后续有需求再加 -->

---

### D.2 通用枚举 (common.enums)

```java
package com.wechatai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码枚举 — 与 2.4 节错误码表一一对应
 * 命名: 模块前缀 + 具体异常
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ── 通用 ──
    SUCCESS(200, "success"),
    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "not found"),
    INTERNAL_ERROR(500, "internal error"),

    // ── 会话 (1xxx) ──
    SESSION_NOT_FOUND(1001, "session not found"),
    SESSION_EXPIRED(1002, "session expired"),

    // ── 文档 (2xxx) ──
    FILE_TOO_LARGE(2001, "file too large"),
    UNSUPPORTED_FILE_TYPE(2002, "unsupported file type"),
    FILE_PARSE_FAILED(2003, "file parse failed"),
    FILE_NOT_FOUND(2004, "file not found"),

    // ── AI (3xxx) ──
    AI_SERVICE_ERROR(3001, "ai service error"),
    TOOL_EXECUTION_FAILED(3002, "tool execution failed"),
    CONTEXT_OVERFLOW(3003, "context overflow"),

    // ── 微信 (4xxx) ──
    WECHAT_MESSAGE_ERROR(4001, "wechat message error"),


    private final int code;
    private final String message;
}
```

```java
package com.wechatai.common.enums;

/**
 * 会话状态
 */
public enum SessionStatus {
    ACTIVE,     // 活跃
    EXPIRED,    // 已过期（超过 24h 未使用）
    DELETED     // 已删除（逻辑删除）
}
```

```java
package com.wechatai.common.enums;

/**
 * 文档状态
 */
public enum FileStatus {
    UPLOADED,   // 已上传，待解析
    PARSING,    // 解析中
    PARSED,     // 解析完成
    FAILED      // 解析失败
}
```

```java
package com.wechatai.common.enums;

/**
 * 消息角色
 */
public enum MessageRole {
    USER,       // 用户
    ASSISTANT,  // AI
    SYSTEM      // 系统（Prompt 预设等）
}
```

```java
package com.wechatai.common.enums;

/**
 * 消息类型
 */
public enum MessageType {
    TEXT,           // 文本
    IMAGE,          // 图片
    FILE,           // 文件
    TOOL_CALL,      // 工具调用请求（LLM 输出）
    TOOL_RESULT     // 工具调用结果（回填给 LLM）
}
```

```java
package com.wechatai.common.enums;

/**
 * 异步任务类型
 */
public enum TaskType {
    DOC_PARSE,      // 文档解析
    TOOL_EXECUTE,   // 工具执行
    AI_CHAT         // AI 对话
}
```

```java
package com.wechatai.common.enums;

/**
 * 任务状态
 */
public enum TaskStatus {
    QUEUED,     // 已入队，等待处理
    RUNNING,    // 执行中
    DONE,       // 执行成功
    FAILED      // 执行失败
}
```

```java
package com.wechatai.common.enums;

/**
 * 工具分类
 */
public enum ToolCategory {
    READ,       // 查询工具，幂等，可自动重试
    WRITE,      // 写入工具，有副作用，需确认
    SYSTEM      // 系统工具，控制 AI 自身行为
}
```

```java
package com.wechatai.common.enums;

/**
 * 文档类型（文件扩展名映射）
 */
public enum FileType {
    PDF,
    DOC,
    DOCX,
    TXT;

    /**
     * 从文件扩展名解析
     */
    public static FileType fromExtension(String extension) {
        return valueOf(extension.toUpperCase());
    }
}
```

```java
package com.wechatai.common.enums;

/**
 * AI 回复终止原因
 *
 * 对外暴露的 finish_reason 只返回 STOP / ERROR。
 * TOOL / LENGTH 为内部调试用，记录在 message 表中，不返回给调用方。
 */
public enum FinishReason {
    STOP,       // LLM 正常结束回复（对外暴露）
    TOOL,       // LLM 要求调用工具（内部，不对外暴露）
    LENGTH,     // 内容超出长度限制被截断（内部，不对外暴露）
    ERROR       // LLM 调用异常（对外暴露）
}
```

```java
package com.wechatai.common.enums;

/**
 * 会话来源
 */
public enum SessionSource {
    WECHAT,     // 微信（公众号 / 机器人等）
    API         // 外部 API 调用
}
```

---

### D.3 常量定义 (common.constant)

```java
package com.wechatai.common.constant;

/**
 * Redis Key 前缀常量
 *
 * 把所有 Redis Key 集中管理，避免各处写死字符串。
 * 用 interface 是为了省写 public static final（接口字段默认就是）。
 *
 * 使用方式:
 *   redisTemplate.opsForValue().set(RedisKeys.SESSION_PREFIX + sessionId, data);
 */
public interface RedisKeys {

    String SESSION_PREFIX     = "session:";         // session:{sessionId} — 会话元数据
    String SESSION_MESSAGES   = ":messages";        // session:{sessionId}:messages — 最近消息列表
    String FILE_CONTENT       = "file:content:";    // file:content:{fileId} — 文档文本缓存
    String TASK_QUEUE_DOC     = "task:queue:doc_parse";  // 文档解析任务队列（固定值）
    String TASK_STATUS        = "task:status:";     // task:status:{taskId} — 任务状态
    String WECHAT_TOKEN       = "wechat:token";     // 微信 Access Token（固定值）
    String RATE_LIMIT         = "rate:limit:";      // rate:limit:{userId} — 用户频率限制
}
```

```java
package com.wechatai.common.constant;

/**
 * API 路径常量
 */
public interface ApiPrefix {

    String V1 = "/api/v1";

    // 各模块路径
    String SESSION  = V1 + "/session";
    String DOCUMENT = V1 + "/document";
    String CHAT     = V1 + "/chat";
    String TOOLS    = V1 + "/tools";
    String WECHAT   = V1 + "/wechat";
    String DESKTOP  = V1 + "/desktop";
    String TTS      = V1 + "/tts";
    String TASK     = V1 + "/task";
    String MAIL     = V1 + "/mail";   // v2.31 桌面邮箱登录
}
```

---

### D.4 会话模块 POJO (session)

```java
package com.wechatai.session.entity;

import com.wechatai.common.enums.SessionSource;
import com.wechatai.common.enums.SessionStatus;

import java.time.LocalDateTime;

/**
 * 会话数据实体 — 对应 MySQL session 表
 * 
 * CREATE TABLE session (
 *     id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
 *     session_id    VARCHAR(36)  NOT NULL UNIQUE COMMENT '业务 UUID',
 *     user_id       VARCHAR(64)  NOT NULL COMMENT '微信 openid',
 *     title         VARCHAR(200) DEFAULT NULL COMMENT '会话标题',
 *     source        VARCHAR(20)  NOT NULL DEFAULT 'WECHAT' COMMENT '来源',
 *     status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
 *     message_count INT          NOT NULL DEFAULT 0 COMMENT '消息数',
 *     last_message_at DATETIME   DEFAULT NULL COMMENT '最后消息时间',
 *     created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
 *     expire_at     DATETIME     NOT NULL COMMENT '过期时间',
 *     updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *     INDEX idx_user_id (user_id),
 *     INDEX idx_status (status)
 * ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
 */
@Data
public class SessionEntity {

    private Long id;                  // 自增主键
    private String sessionId;         // 业务 UUID
    private String userId;            // 微信 openid
    private String title;             // 标题
    private SessionSource source;     // 来源
    private SessionStatus status;     // 状态
    private Integer messageCount;     // 消息数
    private LocalDateTime lastMessageAt; // 最后消息时间
    private LocalDateTime createdAt;  // 创建时间
    private LocalDateTime expireAt;   // 过期时间
    private LocalDateTime updatedAt;  // 更新时间
}
```

```java
package com.wechatai.session.model.request;

import com.wechatai.common.enums.SessionSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建会话请求
 */
@Data
public class CreateSessionReq {

    @NotBlank(message = "用户 ID 不能为空")
    private String userId;                   // 微信 openid

    @NotNull(message = "来源不能为空")
    private SessionSource source;            // 来源: WECHAT / API

    private String title;                    // 标题，不传则由 AI 自动生成
}
```

```java
package com.wechatai.session.model.vo;

import com.wechatai.common.enums.SessionStatus;
import lombok.Data;

/**
 * 会话视图 — 对外展示
 */
@Data
public class SessionVO {

    private String sessionId;        // 会话 ID
    private String userId;           // 用户 ID
    private String title;            // 标题
    private SessionStatus status;    // 状态
    private Integer messageCount;    // 消息数
    private String lastMessageAt;    // 最后消息时间
    private String createdAt;        // 创建时间
    private String expireAt;         // 过期时间
}
```

```java
package com.wechatai.session.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 更新标题请求
 */
@Data
public class UpdateTitleReq {

    @NotBlank(message = "标题不能为空")
    private String title;
}
```

---

### D.4.5 提醒模块 POJO (remind — v2.12 新增)

```java
package com.wechatai.session.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提醒数据实体 — 对应 MySQL remind 表。
 * 用户通过 AI 设置定时提醒后写入此表，RemindScheduler 每秒扫描到期提醒并推送。
 */
@Data
public class RemindEntity {

    private Long id;
    private String remindId;
    private String userId;
    private String wechatClientId;  // 多账号client ID（可空）
    private String content;
    private LocalDateTime remindAt;
    private String status;   // PENDING / SENT / CANCELLED
    private String repeatType;      // NONE / DAILY / WEEKLY / MONTHLY / INTERVAL
    private String repeatValue;     // WEEKLY:星期几, MONTHLY:日期, INTERVAL:天数
    private LocalDateTime repeatEndAt;  // 重复截止日期，NULL=永久
    private int repeatCount;        // 已重复次数
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

---

### D.5 文档模块 POJO (document)

```java
package com.wechatai.document.entity;

import com.wechatai.common.enums.FileStatus;
import com.wechatai.common.enums.FileType;

import java.time.LocalDateTime;

/**
 * 文档数据实体 — 对应 MySQL document 表
 * 
 * CREATE TABLE document (
 *     id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
 *     file_id       VARCHAR(36)  NOT NULL UNIQUE COMMENT '文件业务 ID',
 *     session_id    VARCHAR(36)  NOT NULL COMMENT '所属会话 ID',
 *     user_id       VARCHAR(64)  NOT NULL COMMENT '上传者',
 *     file_name     VARCHAR(255) NOT NULL COMMENT '原始文件名',
 *     file_type     VARCHAR(10)  NOT NULL COMMENT '文件类型 pdf/doc/docx/txt',
 *     file_size     BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
 *     file_path     VARCHAR(500) NOT NULL COMMENT '存储路径',
 *     status        VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED' COMMENT '状态',
 *     content       LONGTEXT     DEFAULT NULL COMMENT '解析后的文本内容',
 *     error_message VARCHAR(500) DEFAULT NULL COMMENT '解析失败原因',
 *     uploaded_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
 *     parsed_at     DATETIME     DEFAULT NULL COMMENT '解析完成时间',
 *     created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
 *     updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *     INDEX idx_session_id (session_id),
 *     INDEX idx_user_id (user_id),
 *     FULLTEXT INDEX ft_content (content) WITH PARSER ngram
 * ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
 */
@Data
public class DocumentEntity {

    private Long id;
    private String fileId;
    private String sessionId;
    private String userId;
    private String fileName;
    private FileType fileType;
    private Long fileSize;
    private String filePath;              // 文件在服务器上的存储路径
    private FileStatus status;
    private String content;               // 解析后的文本内容（LONGTEXT）
    private String errorMessage;
    private LocalDateTime uploadedAt;
    private LocalDateTime parsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

```java
package com.wechatai.document.model.vo;

import com.wechatai.common.enums.FileStatus;
import com.wechatai.common.enums.FileType;
import lombok.Data;

/**
 * 文档视图 — 列表及状态查询用
 */
@Data
public class DocumentVO {

    private String fileId;
    private String fileName;
    private Long fileSize;
    private FileType fileType;
    private FileStatus status;
    private String uploadedAt;
    private String parsedAt;
}
```

```java
package com.wechatai.document.model.vo;

import lombok.Data;

/**
 * 文档内容视图 — AI 模块拉取文档全文用
 */
@Data
public class DocumentContentVO {

    private String fileId;
    private String fileName;
    private String content;              // 文本内容，最大 10 万字
    private String contentType;          // PLAIN_TEXT / MARKDOWN
}
```

```java
package com.wechatai.document.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发起解析请求
 *
 * 文档上传（4.1）完成后，拿着返回的 fileId 调此接口，
 * 告诉后端"这个文件可以开始解析了"。文件将入 Redis 队列，
 * 由后台 Worker 异步解析。
 *
 * 就传一个 fileId，不需要其他信息。
 */
@Data
public class ParseReq {

    @NotBlank(message = "文件 ID 不能为空")
    private String fileId;
}
```

---

### D.6 AI 对话模块 POJO (ai)

> message 表定义见附录 E.3。

```java
package com.wechatai.ai.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 发送消息请求 — 核心接口入参
 */
@Data
public class ChatSendReq {

    @NotBlank(message = "会话 ID 不能为空")
    private String sessionId;

    @NotBlank(message = "用户 ID 不能为空")
    private String userId;

    @NotBlank(message = "消息内容不能为空")
    private String message;              // 用户输入

    private String messageType = "TEXT"; // TEXT / IMAGE / FILE

    private List<String> fileIds;        // 关联文档 ID

    private Map<String, Object> extra;   // 扩展字段
}
```

```java
package com.wechatai.ai.model.vo;

import lombok.Data;

/**
 * 对话响应视图
 *
 * 注意：工具调用在 AI 引擎内部自动完成（LangChain4j @Tool），
 * 对外部调用方透明。响应中只返回最终结果。
 */
@Data
public class ChatResponseVO {

    private String replyMessage;         // AI 最终回复（工具调用已在内部完成）
    private String messageId;            // 消息 ID
    private String sessionId;            // 会话 ID
    private String finishReason;         // STOP / ERROR
    private Long latencyMs;              // 处理总耗时（含内部工具调用链）
}
```

<!-- ToolCallbackReq 已移除（v1.1 工具调用改为 AI 引擎内部自动完成，不再需要外部回调） -->
<!-- HistoryMessageVO / ToolCallVO 已移除（第一期不对外暴露历史消息接口） -->

---

### D.7 工具系统 POJO (tool)

```java
package com.wechatai.tool.model.vo;

import com.wechatai.common.enums.ToolCategory;
import lombok.Data;

/**
 * 工具定义 VO — 返回给 AI 引擎的工具描述
 */
@Data
public class ToolDefinitionVO {

    private String name;                 // 工具名
    private String description;          // 功能描述
    private ToolCategory category;       // READ / WRITE / SYSTEM
    private boolean enabled;             // 是否启用
    private Object parameters;           // JSON Schema 参数定义
}
```

```java
package com.wechatai.tool.model.dto;

import lombok.Data;

import java.util.Map;

/**
 * 工具调用参数内部传输对象
 * 
 * 由 AI 引擎内部构造，不再通过 HTTP 传入。
 * 保留此 DTO 用于日志记录和调用追踪。
 */
@Data
public class ToolExecuteReq {

    private String toolName;                  // 工具名
    private String toolCallId;                // LLM 生成的调用 ID
    private Map<String, Object> arguments;    // 参数键值对
    private String userId;
    private String sessionId;
}
```

```java
package com.wechatai.tool.model.vo;

import lombok.Data;

/**
 * 工具执行结果内部 VO
 *
 * 由 @Tool 方法的返回值自动序列化为 JSON 回填给 LLM。
 * 此结构不再通过 HTTP 返回，仅用于内部日志和调试。
 */
@Data
public class ToolExecuteResp {

    private String toolName;
    private String toolCallId;
    private boolean success;
    private Object result;               // 执行结果（给 LLM 看的结构化数据）
    private ToolError error;             // 失败时填充
    private Long latencyMs;              // 执行耗时

    @Data
    public static class ToolError {

        private String code;             // 错误码
        private String message;          // 错误信息（LLM 可读）
        private boolean retryable;       // 是否可重试
    }

    // ── 快速构建 ──

    public static ToolExecuteResp ok(String toolName, String toolCallId, Object result, long latency) {
        ToolExecuteResp resp = new ToolExecuteResp();
        resp.setToolName(toolName);
        resp.setToolCallId(toolCallId);
        resp.setSuccess(true);
        resp.setResult(result);
        resp.setLatencyMs(latency);
        return resp;
    }

    public static ToolExecuteResp fail(String toolName, String toolCallId,
                                        String code, String message, boolean retryable, long latency) {
        ToolExecuteResp resp = new ToolExecuteResp();
        resp.setToolName(toolName);
        resp.setToolCallId(toolCallId);
        resp.setSuccess(false);
        ToolError error = new ToolError();
        error.setCode(code);
        error.setMessage(message);
        error.setRetryable(retryable);
        resp.setError(error);
        resp.setLatencyMs(latency);
        return resp;
    }
}
```

```java
package com.wechatai.tool.model.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 文档搜索工具 — 专用返回 DTO
 * 每个工具应有自己的返回类型，这里以 search_document 为例
 *
 * 一期实现方式（不做 RAG / 向量语义匹配）：
 * 1. 文档解析时按段落/页数切片，存入 document_chunk 表
 * 2. MySQL FULLTEXT INDEX（ngram 分词器）做中文关键词搜索，自带 TF-IDF 相关性打分
 * 3. relevance 即 MySQL 算出的相关性分数，直接透传给 LLM
 * 4. 页数(pageNum)在切片时记录
 */
@Data
public class SearchDocumentResult {

    private List<MatchItem> matches;
    private int totalMatches;

    @Data
    public static class MatchItem {

        private String fileId;
        private String fileName;
        private Integer pageNum;
        private String text;
        private double relevance;        // 相关度 0~1
    }
}
```

```java
package com.wechatai.tool.model.vo;

import lombok.Data;

/**
 * 工具调用历史记录 VO
 */
@Data
public class ToolHistoryVO {

    private String toolCallId;
    private String toolName;
    private String arguments;            // 参数（JSON）
    private boolean success;
    private String resultSummary;        // 结果摘要
    private Long latencyMs;
    private String calledAt;             // 调用时间
}
```

```java
package com.wechatai.tool.model.request;

import lombok.Data;

/**
 * 启用/禁用工具请求
 */
@Data
public class ToggleToolReq {

    private boolean enabled;             // true=启用，false=禁用
}
```

---

---
---
	
## 附录 E: 数据库表关系与结构

### E.1 表关系图

```
┌───────────────────────────────────────────────┐
│                   session                      │
│  PK  id                                        │
│  UK  session_id ────────┐                      │
│      user_id            │                      │
│      status             │                      │
│      expire_at          │                      │
│      message_count      │                      │
│      ...                │                      │
└─────────────────────────┼──────────────────────┘
                          │
              ┌───────────┼───────────────┐
              │           │               │
              ▼           ▼               │
  ┌───────────────────┐ ┌─────────────────┐│
  │     message       │ │    document     ││
  │  PK  id           │ │  PK  id         ││
  │  UK  message_id   │ │  UK  file_id    ││
  │      session_id ──┘ │      session_id ─┘
  │      role            │      user_id       │
  │      content         │      file_name     │
  │      file_ids        │      status        │
  │      tool_calls      │      content       │
  └──────────────────────┘ └──────────────────┘

┌───────────────────────────────────────────────┐
```
```

### E.2 关系说明

| 表 | 关联 | 说明 |
|----|------|------|
| `session` ← `message` | 1:N | 一个会话有多条消息，`message.session_id` → `session.session_id` |
| `session` ← `document` | 1:N | 一个会话关联多个文档，`document.session_id` → `session.session_id` |
| `user` ← `user_profile` | 1:1 | 一个用户有一份画像摘要，`user_profile.user_id` 逻辑关联微信 openid |

### E.3 所有表结构速览

#### session

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| session_id | VARCHAR(36) UK | 业务 UUID |
| user_id | VARCHAR(64) | 用户 openid |
| title | VARCHAR(200) | 会话标题 |
| source | VARCHAR(20) | 来源：WECHAT / API |
| status | VARCHAR(20) | ACTIVE / EXPIRED / DELETED |
| message_count | INT | 消息数 |
| last_message_at | DATETIME | 最后消息时间 |
| expire_at | DATETIME | 过期时间 |
| created_at / updated_at | DATETIME | 创建/更新时间 |

#### message

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| message_id | VARCHAR(36) UK | 消息业务 ID |
| session_id | VARCHAR(36) | 所属会话 ID |
| role | VARCHAR(20) | USER / ASSISTANT / SYSTEM |
| message_type | VARCHAR(20) | TEXT / IMAGE / FILE / TOOL_CALL / TOOL_RESULT |
| content | TEXT | 消息内容 |
| file_ids | TEXT | 关联文档 ID 列表（JSON 数组） |
| tool_calls | TEXT | 工具调用记录（JSON 数组） |
| finish_reason | VARCHAR(20) | STOP / TOOL / LENGTH / ERROR |
| latency_ms | INT | 处理耗时 |
| created_at | DATETIME | 创建时间 |

#### document

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| file_id | VARCHAR(36) UK | 文件业务 ID |

#### user_profile

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| user_id | VARCHAR(64) UK | 用户 openid |
| summary | TEXT | LLM 生成的用户画像摘要文本 |
| version | INT | 版本号，每次更新+1 |
| msg_count | INT | 上次提炼时的 message 表总条数，用于判断增量 |
| updated_at | DATETIME | 更新时间 |

#### remind（v2.12 新增，v2.27 周期提醒）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| remind_id | VARCHAR(50) UK | 提醒业务 ID |
| user_id | VARCHAR(64) | 微信 openid |
| wechat_client_id | VARCHAR(36) | 多账号 client ID（可空） |
| content | VARCHAR(500) | 提醒内容 |
| remind_at | DATETIME | 提醒时间 |
| status | VARCHAR(20) | PENDING / SENT / CANCELLED |
| repeat_type | VARCHAR(20) | NONE / DAILY / WEEKLY / MONTHLY / INTERVAL |
| repeat_value | VARCHAR(50) | 重复值：周几/日期/间隔天数（可空） |
| repeat_end_at | DATETIME | 重复截止日期（可空，NULL=永久） |
| repeat_count | INT | 已重复次数 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

索引：`idx_remind_recurring_active (status, repeat_type, remind_at)`

#### contact（v2.13 新增）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| name | VARCHAR(50) | 联系人姓名/昵称 |
| user_id | VARCHAR(64) UK | iLink 用户 ID |
| remark | VARCHAR(200) | 备注 |

#### wechat_context（v2.13 新增）

| 字段 | 类型 | 说明 |
|------|------|------|
| bot_id | VARCHAR(64) | 机器人 ID（与 user_id 联合主键） |
| user_id | VARCHAR(64) | iLink 用户 ID（与 bot_id 联合主键） |
| context_token | VARCHAR(255) | 消息上下文 token |
| source_msg_id | BIGINT | 最后消息 ID |
| source_msg_time | BIGINT | 最后消息时间 |
| updated_at | DATETIME | 更新时间 |
| content | LONGTEXT | 解析后的文本内容（FULLTEXT 索引，ngram 分词器支持中文搜索） |
| error_message | VARCHAR(500) | 解析失败原因 |
| uploaded_at | DATETIME | 上传时间 |
| parsed_at | DATETIME | 解析完成时间 |
| created_at / updated_at | DATETIME | 创建/更新时间 |

> **FULLTEXT 索引**（中文搜索）：`FULLTEXT INDEX ft_content (content) WITH PARSER ngram`  
> 使用 MySQL ngram 分词器支持中文关键词搜索。建表时已包含，无需额外执行。


---

## 附录 F: MCP 配置附录

### F.1 mcp.didi 配置项

| 配置项 | 类型 | 默认值 | 说明 |
|--------|:----:|:------:|------|
| `mcp.didi.enabled` | boolean | `false` | 是否启用滴滴 MCP 客户端 |
| `mcp.didi.api-key` | String | `""` | 滴滴 MCP API Key（从 https://mcp.didichuxing.com 获取） |
| `mcp.didi.sandbox` | boolean | `true` | 是否使用沙箱环境（调试完切换为 false） |
| `mcp.didi.base-url` | String | `https://mcp.didichuxing.com/mcp-servers` | 正式环境 URL |
| `mcp.didi.sandbox-url` | String | `https://mcp.didichuxing.com/mcp-servers-sandbox` | 沙箱环境 URL |
| `mcp.didi.timeout-seconds` | int | `30` | 连接超时（秒） |

### F.2 示例配置

```properties
# 启用滴滴 MCP
mcp.didi.enabled=true
mcp.didi.api-key=0vLQCQYoWhnzNFrXqaOxEAUQsjAlpv7zJ
mcp.didi.sandbox=false
```

### F.3 curl 手动测试

```bash
# 列出可用工具
curl -X POST "https://mcp.didichuxing.com/mcp-servers?key=YOUR_KEY" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1,"params":{}}'

# 查位置（maps_textsearch）
curl -X POST "https://mcp.didichuxing.com/mcp-servers?key=YOUR_KEY" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":2,"params":{"name":"maps_textsearch","arguments":{"keywords":"中山陵","city":"南京"}}}'

# 打车预估（taxi_estimate）
curl -X POST "https://mcp.didichuxing.com/mcp-servers?key=YOUR_KEY" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":3,"params":{"name":"taxi_estimate","arguments":{"from_lng":"118.784","from_lat":"32.042","from_name":"新街口","to_lng":"118.853","to_lat":"32.059","to_name":"中山陵"}}}'
```

---

## 附录 G: 猎聘 MCP 配置附录（v2.26）

### G.1 mcp.liepin 配置项

| 配置项 | 类型 | 默认值 | 说明 |
|--------|:----:|:------:|------|
| `mcp.liepin.enabled` | boolean | `false` | 是否启用猎聘 MCP 客户端 |
| `mcp.liepin.credential` | String | `""` | 凭证（登录 liepin.com → liepin.com/mcp/server 生成） |
| `mcp.liepin.base-url` | String | `https://open-agent.liepin.com/mcp/user` | MCP Server 端点 |
| `mcp.liepin.timeout-seconds` | int | `30` | 连接超时（秒） |

### G.2 示例配置

```properties
mcp.liepin.enabled=true
mcp.liepin.credential=eyJhbGciOiJIUzI1NiJ9...
```

### G.3 13 个工具

| 类别 | 工具 | 分类 |
|------|------|:----:|
| 查询 | `my-resume` | READ |
| 新增 | `add-edu-exp` `add-job-want` `add-project-exp` `add-work-exp` | READ |
| 修改 | `modify-edu-exp` `modify-job-want` `modify-project-exp` `modify-resume-base-info` `modify-self-assess` `modify-work-exp` | READ |
| 搜索 | `user-search-job` `search-jobs` | READ |
| 投递 | `user-apply-job` | **WRITE** |

### G.4 curl 手动测试

```bash
# 列出可用工具
curl -X POST "https://open-agent.liepin.com/mcp/user" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "x-user-token: YOUR_TOKEN" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1,"params":{}}'

# 搜职位
curl -X POST "https://open-agent.liepin.com/mcp/user" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "x-user-token: YOUR_TOKEN" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":2,"params":{"name":"user-search-job","arguments":{"address":"南京","jobName":"Java"}}}'

# 查简历
curl -X POST "https://open-agent.liepin.com/mcp/user" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "x-user-token: YOUR_TOKEN" \
  -d '{"jsonrpc":"2.0","method":"tools/call","id":3,"params":{"name":"my-resume","arguments":{}}}'
```

---

## 附录 H: ReAct 超时看门狗配置（v2.26）

### H.1 配置项

| 配置项 | 类型 | 默认值 | 说明 |
|--------|:----:|:------:|------|
| `wechat.watchdog.tool-timeout-ms` | long | `30000` | 单次工具调用超时（毫秒），超时注入 error JSON |
| `wechat.watchdog.bridge-timeout-ms` | long | `90000` | Bridge 总超时（毫秒），超时告知用户 |

### H.2 示例配置

```properties
wechat.watchdog.tool-timeout-ms=60000
wechat.watchdog.bridge-timeout-ms=120000
```

### H.3 三层超时

| 层级 | 配置/参数 | 超时行为 |
|------|-----------|----------|
| 工具级 | `wechat.watchdog.tool-timeout-ms` | 超时注入 `{"error":"工具执行超时"}` → LLM 换策略 |
| Agent 级 | `AgentGraphRunner.run(timeoutMs)` | 超时返回 `AgentResult.fail` → 中央降级 |
| Bridge 总 | `wechat.watchdog.bridge-timeout-ms` | 超时发送"处理超时，请稍后重试" → 释放用户线程 |

---

*文档结束 — 请各模块负责人按此契约开发，接口变更需同步更新此文档。*
