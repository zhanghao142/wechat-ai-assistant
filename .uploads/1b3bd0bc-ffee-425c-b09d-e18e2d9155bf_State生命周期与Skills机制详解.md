# State 生命周期与 Skills 机制详解

> **项目**：Wechat AI Assistant — 微信智能助手
> **日期**：2026-07-21
> **关键词**：LangGraph StateGraph, ChatGraphState, ReAct 循环, SkillManager

---

## 目录

1. [概述](#1-概述)
2. [ChatGraphState — 图状态定义](#2-chatgraphstate--图状态定义)
3. [Graph 拓扑与路由](#3-graph-拓扑与路由)
4. [State 的生命周期](#4-state-的生命周期)
5. [Skills 系统设计](#5-skills-系统设计)
6. [完整执行流程举例](#6-完整执行流程举例)
7. [设计要点总结](#7-设计要点总结)

---

## 1. 概述

本项目基于 **LangGraph4j** 的 `StateGraph` 框架实现 **ReAct 循环**。整体架构分为三层：

```
┌──────────────────────────────────────────────────────────┐
│              AI 引擎核心 (LangGraph 状态图)                 │
│                                                          │
│  START ──► prepare_input ──► session_prepare ──► llm_think│
│                                        │                 │
│                                  ┌─────┴─────┐           │
│                                  │ need_tool? │          │
│                                  └─────┬─────┘           │
│                                   ▼          ▼           │
│                                tool      done            │
│                                  │          │            │
│                                  ▼          ▼            │
│                           tool_execute    END            │
│                                  │                       │
│                                  └──► llm_think (循环)    │
└──────────────────────────────────────────────────────────┘
```

本文档解释两个核心机制：

- **State 生命周期**：State（图状态）何时创建、在节点间如何流转、何时销毁
- **Skills 系统**：行为规则如何动态加载和卸载

---

## 2. ChatGraphState — 图状态定义

**源文件**：`wechat-ai-ai/.../state/ChatGraphState.java`

所有状态数据的容器，继承自 `AgentState`（LangGraph4j），本质是对 `Map<String, Object>` 的类型化包装：

```java
public class ChatGraphState extends AgentState {

    // ====== 状态字段（全部存储在底层 Map 中） ======
    public static final String KEY_USER_ID = "userId";                     // 用户 ID
    public static final String KEY_SESSION_ID = "sessionId";               // 会话 ID
    public static final String KEY_USER_MESSAGE = "userMessage";           // 用户当前输入
    public static final String KEY_MESSAGE_TYPE = "messageType";           // 消息类型 (TEXT/IMAGE)
    public static final String KEY_FILE_IDS = "fileIds";                   // 上传文件 ID 列表
    public static final String KEY_REPLY = "reply";                        // 最终回复文本
    public static final String KEY_NEED_TOOL = "needTool";                 // 是否需调工具 (boolean)
    public static final String KEY_TOOL_EXECUTION_REQUESTS = "toolExecutionRequests";  // 工具调用列表
    public static final String KEY_MESSAGES = "messages";                  // ChatMessage 列表（预留）
    public static final String KEY_FINISH_REASON = "finishReason";         // 结束原因 (STOP/TOOL/ERROR)

    public ChatGraphState(Map<String, Object> initData) {
        super(initData);  // ← 把 HashMap 存入底层 Map
    }

    public boolean isNeedTool() { ... }          // 条件边路由依据
    public List<Object> getToolExecutionRequests() { ... }  // 工具调用请求
    public List<ChatMessage> getMessages() { ... }          // 消息历史
    // ... 其他 getter
}
```

### 2.1 重要说明：两个"消息历史"的区别

代码中存在两个类似的 key：

| Key | 类型 | 用途 | 被谁使用 |
|-----|------|------|---------|
| `"messageHistory"` | `List<Map<String,Object>>` | 图内流转的消息历史 | `LlmService`、`ToolExecuteNode`、`SessionPrepareNode`（**实际用的这个**） |
| `"messages"` | `List<ChatMessage>` | `KEY_MESSAGES` 常量，LangChain4j 原生消息类型 | 预留，当前未被图内节点使用 |

**`"messageHistory"` 不是 `ChatGraphState` 中定义的常量**，而是节点间以原始字符串传递的 key。

### 2.2 状态字段在各节点的读写分布

| 字段 | prepare_input | session_prepare | llm_think | tool_execute |
|------|:---:|:---:|:---:|:---:|
| `userId` | → 透传 | 读取 | 读取 | — |
| `sessionId` | → 透传 | **写入** | 读取 | 读取 |
| `userMessage` | → 透传 | — | 读取 | — |
| `messageType` | → 透传 | — | — | — |
| `fileIds` | → 透传 | — | — | — |
| `messageHistory` | — | **写入** | **读写** | **读写** |
| `reply` | — | — | **写入** | — |
| `needTool` | — | — | **写入** | **写入**(清false) |
| `toolExecutionRequests` | — | — | **写入** | 读取 |
| `finishReason` | — | — | **写入** | — |

---

## 3. Graph 拓扑与路由

**源文件**：`wechat-ai-ai/.../config/ChatGraphConfig.java`

### 3.1 节点注册

```java
return new StateGraph<>(ChatGraphState::new)   // ← 构造器引用：invoke 时用这个造 State
    .addNode("prepare_input", ...)
    .addNode("session_prepare", ...)
    .addNode("llm_think", ...)
    .addNode("tool_execute", ...)
    // 顺序边
    .addEdge(START, "prepare_input")
    .addEdge("prepare_input", "session_prepare")
    .addEdge("session_prepare", "llm_think")
    // 条件边
    .addConditionalEdges("llm_think",
        state -> state.isNeedTool() ? "tool" : "done",
        Map.of("tool", "tool_execute", "done", END))
    // 循环边
    .addEdge("tool_execute", "llm_think");
```

### 3.2 路由逻辑

| `isNeedTool()` 的值 | 路由目标 | 含义 |
|:---:|:---:|:---|
| `true` | `tool_execute` | LLM 要求调用工具 → 去执行 |
| `false` | `END` | LLM 直接回复了文本 → 结束 |

`tool_execute → llm_think` 是固定边，执行完工具**无条件**回到 LLM 做下一轮推理。

### 3.3 节点职责

| 节点 | 类名 | 职责 |
|------|------|------|
| `prepare_input` | `PrepareInputNode.java` | 预留，当前为空转（返回空 Map），用于未来做输入校验/归一化 |
| `session_prepare` | `SessionPrepareNode.java` | 获取/创建会话，从缓存或 MySQL 加载历史消息，修复 tool 链断裂 |
| `llm_think` | `LlmThinkNode.java → LlmService.java` | 调用 DeepSeek API，携带工具清单，解析 tool_calls，动态加载 Skill |
| `tool_execute` | `ToolExecuteNode.java` | 解析 tool_calls，反射调用 @Tool 方法，回填结果 |

---

## 4. State 的生命周期

### 4.1 创建：invoke() 时

每次 HTTP 请求或微信消息处理，都会调用一次 `graph.compile().invoke(initData)`：

```java
// ChatController.java — REST 接口
ChatGraphState state = chatGraph.compile()
        .invoke(initData)          // ← 内部：new ChatGraphState(initData)
        .orElseThrow(...);
```

```java
// WechatChatBridge.java — 微信入口
ChatGraphState state = chatGraph.compile()
        .invoke(initData)          // ← 同上，每次消息独立 invoke
        .orElse(null);
```

**invoke 的参数**（initData 中的字段）：

| 字段 | 来源 | 说明 |
|------|------|------|
| `userId` | 请求体 / 微信 ID | 用户标识 |
| `sessionId` | 请求体 / 新建 | 会话 ID |
| `userMessage` | 请求体 / 微信消息 | 用户输入的文本 |
| `messageType` | 请求体 / 消息类型 | `TEXT` 或 `IMAGE` |
| `fileIds` | 请求体（可选） | 图片消息的文件 ID 列表 |
| `messageHistory` | WechatChatBridge 缓存 | 仅微信入口有，REST 接口由 session_prepare 加载 |

### 4.2 流转：节点间传递

LangGraph4j 的工作方式是**不可变状态 + 增量更新**：

```
第 N 轮 State (AgentState 实例)
       │
       ├── 节点读取 state.data() 获取当前数据
       ├── 节点返回 Map<String, Object> update（增量）
       │
       ▼
LangGraph 框架内部：
   merged = new HashMap<>(oldState.data())
   merged.putAll(update)
   newState = new AgentState(merged)    ← 新实例，旧实例丢弃
       │
       ▼
第 N+1 轮 State (新的 AgentState 实例)
```

源码中 `withStateLog` 包装器清晰地展示了这个过程：

```java
private AsyncNodeAction<ChatGraphState> withStateLog(String nodeName, ...) {
    return state -> action.apply(state).thenApply(update -> {
        Map<String, Object> merged = new HashMap<>(state.data());  // 读旧状态
        merged.putAll(update);                                     // 合并增量
        log.debug("【{} 执行后状态】{}", nodeName, merged);
        return update;  // 只返回增量，合并由框架完成
    });
}
```

**要点**：

- 每个节点**不能直接修改 state**（因为 AgentState 是只读包装）
- 节点必须通过返回值 `Map<String, Object>` 传递修改
- 每次节点执行后，LangGraph 创建一个**全新的** AgentState 实例
- 旧的 AgentState 失去引用，等待 GC

### 4.3 消息上限控制

在 `LlmService.think()` 中，当消息数超过阈值时会截断：

```java
int MAX_ROUNDS = 150;  // 最多保留 150 轮对话（≈300 条消息）

// 1. 只保留最近 150 轮
// 2. 修复截断后 tool 链断裂
// 3. 如果全是 tool/assistant 残片，清空让 LLM 重新开始
```

### 4.4 销毁：invoke() 返回后

```
invoke() 返回 ChatGraphState 给调用方
       │
       ├── ChatController: 提取 state.getReply() 后方法结束
       │   → state 局部变量离开作用域 → GC 回收
       │
       └── WechatChatBridge: 提取回复，取 messageHistory 缓存到 ConcurrentHashMap
           → state 局部变量离开作用域 → GC 回收
```

**State 的生存期 = 一次 HTTP 请求 / 一次微信消息处理的完整生命周期**。请求结束，State 销毁。

---

## 5. Skills 系统设计

### 5.1 动机

随着工具增多，`SYSTEM_PROMPT` 膨胀到约 100 行，管理困难。核心问题不在基础规则（那些本来就该一直存在），而在于**工具格式规则**——天气格式只在查了天气才有用，文档格式只在搜了文档才有用。

**核心设计**：格式规则绑定到对应的工具上，LLM 调了哪个工具，下一轮才加载对应的规则。

### 5.2 Skill 接口

**源文件**：`wechat-ai-common/.../skill/Skill.java`

```java
public interface Skill {
    String getName();                    // 技能唯一标识
    List<String> getBoundTools();        // 绑定的工具名列表（与 @Tool 方法名一致）
    String getPromptSegment();           // 拼入 system prompt 的规则片段
}
```

放在 `wechat-ai-common` 模块，不产生额外依赖。任何 `@Component` 实现这个接口即可被 `SkillManager` 自动发现。

### 5.3 SkillManager — 加载判定

**源文件**：`wechat-ai-ai/.../skill/SkillManager.java`

```java
@Component
public class SkillManager {

    private final Map<String, Skill> skillMap = new LinkedHashMap<>();

    // Spring 自动注入所有 Skill 实现 Bean
    public SkillManager(List<Skill> skills) {
        for (Skill s : skills) {
            skillMap.put(s.getName(), s);
        }
    }

    /**
     * 根据 state 中上一轮的 toolExecutionRequests，将绑定的 Skill prompt 拼到 basePrompt 末尾。
     */
    public String enrich(ChatGraphState state, String basePrompt) {
        // 1. 读 state 中上一轮的 toolExecutionRequests
        List<Object> toolRequests = state.getToolExecutionRequests();
        if (toolRequests == null || toolRequests.isEmpty()) {
            return basePrompt;  // ← 第 1 轮：没调工具 → 原样返回
        }

        // 2. 提取上一轮 LLM 调过的工具名
        Set<String> calledTools = new HashSet<>();
        for (Object req : toolRequests) {
            Map<String, Object> tc = (Map<String, Object>) req;
            Map<String, Object> func = (Map<String, Object>) tc.get("function");
            calledTools.add((String) func.get("name"));
        }

        // 3. 遍历所有 Skill，检查是否匹配调过的工具
        Set<String> loaded = new LinkedHashSet<>();
        List<Skill> matched = new ArrayList<>();
        for (Skill skill : skillMap.values()) {
            for (String toolName : skill.getBoundTools()) {
                if (calledTools.contains(toolName) && loaded.add(skill.getName())) {
                    matched.add(skill);
                    break;
                }
            }
        }

        if (matched.isEmpty()) return basePrompt;

        // 4. 拼接到 base prompt 末尾
        StringBuilder sb = new StringBuilder(basePrompt);
        sb.append("\n\n");
        for (Skill s : matched) {
            sb.append(s.getPromptSegment()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }
}
```

### 5.4 加载时机：每轮 llm_think

`LlmService.think()` 中**每轮**都调用 `skillManager.enrich()`：

```java
public Map<String, Object> think(ChatGraphState state) {
    List<Map<String, Object>> messages = state.data().get("messageHistory");

    // 每轮都重新 enrich
    String enrichedPrompt = skillManager.enrich(state, SYSTEM_PROMPT);

    if (messages.isEmpty() || !"system".equals(messages.getFirst().get("role"))) {
        messages.addFirst(Map.of("role", "system", "content", enrichedPrompt));
    } else {
        // ReAct 循环中已有 system → 替换为带 Skill 的最新版本
        messages.set(0, Map.of("role", "system", "content", enrichedPrompt));
    }
    // ... 调 API ...
}
```

**关键细节**：`SYSTEM_PROMPT` 是 `LlmService` 的 `static final` 不可变常量，`enrich()` 永远在原始 base prompt 上追加，不会出现重复累加。

### 5.5 卸载机制 — 零代码销毁

**卸载由两层自动完成**：

**第一层：State 生命周期**

```
invoke() 开始 → 创建 State → 节点执行 → invoke() 返回 → State 销毁
                                                            ↓
                                                   Skill "不存在了"
```

每次 HTTP 请求都是独立的 `invoke()`。下一次请求的 State 中没有上一轮的 `toolExecutionRequests`，`SkillManager.enrich()` 原样返回 base prompt。

**第二层：ReAct 循环内即使在同一 invoke() 中**

```
第 1 轮 llm_think: toolExecutionRequests = null  → 不加 Skill (70行 base)
第 2 轮 llm_think: toolExecutionRequests = [weather] → 加 WeatherSkill (70+15=85行)
第 3 轮 llm_think: toolExecutionRequests = [weather] → 再加 WeatherSkill (幂等)
```

每轮都是重新 `enrich()`，不依赖缓存。即使同一 invoke 内多轮，也是基于上轮的工具调用重新判定。

**所以"卸载"的本质是**：下一次 invoke() 时，全新的 state 没有旧工具的调用记录，SkillManager 自然不加载任何 Skill。0 行销毁代码。

### 5.6 已实现的 4 个 Skill

所有 Skill 放在 `wechat-ai-tool/.../skill/` 包下：

| Skill | 绑定工具 | 规则内容 |
|-------|---------|---------|
| `WeatherSkill` | `queryWeather` | 🌤 天气回复格式 + 下雨/晴天景点推荐组合规则 |
| ~~`DocumentSkill`~~ | ~~`searchDocument`~~, `getDocumentSummary` | ❌ 已删除（v2.28）。`searchDocument` 工具已移除，文档检索改为 RAG 预处理自动完成 |
| `WebSearchSkill` | `readUrl`, `searchInternet` | 🔗 网页搜索与链接处理规则 |
| `ConversationSearchSkill` | `searchConversation` | 💬 历史对话搜索规则 |

### 5.7 实例：WeatherSkill

```java
@Component
public class WeatherSkill implements Skill {
    public String getName() { return "weather"; }
    public List<String> getBoundTools() { return List.of("queryWeather"); }
    public String getPromptSegment() {
        return """
                ### 天气查询回复格式
                🌤 [城市名]目前[天气状况]，[温度]°C，湿度[湿度]%，[风向][风力]

                ### 工具组合规则（天气）
                查完天气后，如果用户还问景点推荐：
                - 下雨/中雨/大雨 → 搜"城市名 + 下雨天 + 室内景点"
                - 阴天/多云 → 搜"城市名 + 景点"
                - 晴天 → 搜"城市名 + 户外景点"
                """;
    }
}
```

用户说"南京天气怎么样？顺便推荐景点"时：
- `llm_think` 第 1 轮：只有 base prompt（70行），没有组合规则 → LLM 只调 `queryWeather`
- `tool_execute` 执行天气查询
- `llm_think` 第 2 轮：base prompt + WeatherSkill（70+15行）→ LLM 看到组合规则 → 再调 `searchInternet`

### 5.8 保留在 base SYSTEM_PROMPT 中的系统级规则

- 性格设定
- 工具清单描述
- 语音输出标记规则（`[VOICE]` 标记）
- 未找到/错误回复格式
- 回复红线（不写 Markdown、200 字内、不编造数据等）
- 图片消息处理规则

---

## 6. 完整执行流程举例

以用户问"北京天气怎么样？"为例，展示 State 和 Skills 的完整流转：

### Step-by-step

```
┌─────────────────────────────────────────────────────────────────┐
│  1. 用户发消息 "北京天气怎么样？"                                   │
└──────────────────────────┬──────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  2. invoke(initData)                                            │
│     ├── new ChatGraphState({userId, sessionId, userMessage})     │
│     └── State 创建完成                                           │
└──────────────────────────┬──────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  3. prepare_input (空转节点)                                     │
│     → 返回 {}（无增量）                                           │
│     → LangGraph: new AgentState(old + {})                       │
└──────────────────────────┬──────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  4. session_prepare                                             │
│     → 从 MySQL 加载历史消息（首次对话则空列表）                     │
│     → 获取或创建 SessionEntity                                   │
│     → 返回 {sessionId, messageHistory: [...]}                    │
│     → LangGraph: new AgentState(old + update)                   │
└──────────────────────────┬──────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  5. llm_think 第 1 轮                                            │
│     │                                                            │
│     ├─ skillManager.enrich(state, SYSTEM_PROMPT)                 │
│     │   → state.getToolExecutionRequests() = null                │
│     │   → 返回原始 base prompt（70行，不加 Skill）                │
│     │                                                            │
│     ├─ 构建消息列表:                                              │
│     │   [system(70行), user("北京天气怎么样？")]                   │
│     │                                                            │
│     ├─ 调 DeepSeek API (带工具清单)                               │
│     │                                                            │
│     ├─ LLM 返回 tool_calls:                                      │
│     │   [{name: "queryWeather", arguments: {city: "北京"}}]      │
│     │                                                            │
│     ├─ 持久化: user + assistant(tool_calls) → MySQL              │
│     │                                                            │
│     └─ 返回:                                                     │
│        ├─ messageHistory: 追加了 assistant(tool_calls)           │
│        ├─ toolExecutionRequests: [{id, function: {name, args}}]  │
│        ├─ needTool = true                                        │
│        └─ finishReason = "TOOL"                                  │
│                                                                  │
│     → LangGraph: new AgentState(old + update)                    │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                   条件边: needTool=true
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  6. tool_execute                                                │
│     │                                                            │
│     ├─ 读 state.toolExecutionRequests                            │
│     ├─ 查找 QueryWeatherTool.queryWeather Bean                   │
│     ├─ 反射调用: queryWeather(city="北京")                        │
│     ├─ 注入 sessionId (强制覆盖)                                  │
│     ├─ 得到结果: {"city":"北京","weather":"晴","temperature":32}  │
│     ├─ 追加到 messageHistory:                                     │
│     │   {role:tool, tool_call_id:xxx, content:天气JSON}           │
│     ├─ 持久化: tool_result → MySQL                               │
│     │                                                            │
│     └─ 返回:                                                     │
│        ├─ messageHistory: 追加了 tool(result)                    │
│        └─ needTool = false                                       │
│                                                                  │
│     → LangGraph: new AgentState(old + update)                    │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                   固定边: tool_execute → llm_think
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  7. llm_think 第 2 轮                                            │
│     │                                                            │
│     ├─ skillManager.enrich(state, SYSTEM_PROMPT)                 │
│     │   → state.getToolExecutionRequests() = [{queryWeather}]    │
│     │   → 匹配到 WeatherSkill (bound tool: "queryWeather")       │
│     │   → 返回 base prompt + WeatherSkill（70+15=85行）          │
│     │                                                            │
│     ├─ 更新 system prompt（替换第 0 条为增强版）:                  │
│     │   [system(85行), user, assistant(tool_calls), tool(result)]│
│     │                                                            │
│     ├─ 调 DeepSeek API                                           │
│     │                                                            │
│     ├─ LLM 看到天气结果 + 天气格式规则 → 直接回复:                │
│     │   "🌤 北京目前晴，32°C，湿度45%，西南风2级"                 │
│     │                                                            │
│     ├─ 持久化: assistant(reply) → MySQL                          │
│     │                                                            │
│     └─ 返回:                                                     │
│        ├─ messageHistory: 追加了 assistant(reply)                │
│        ├─ reply = "🌤 北京目前晴..."                              │
│        ├─ needTool = false                                       │
│        └─ finishReason = "STOP"                                  │
│                                                                  │
│     → LangGraph: new AgentState(old + update)                    │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                   条件边: needTool=false
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  8. END — invoke() 返回                                          │
│     ├─ ChatController 取 state.getReply() 返回给客户端            │
│     ├─ 方法结束 → state 局部变量离开作用域                        │
│     └─ GC → State 销毁 → Skill 自动卸载                          │
└─────────────────────────────────────────────────────────────────┘
```

---

## 7. 设计要点总结

### 7.1 核心设计哲学

```
Function Calling 层:  @Tool + @P   → 原子操作描述（工具能做什么）
Skill 层:             Skill 接口   → 行为规则封装（工具返回后该怎么回复）

一致的抽象哲学: 声明式 + 按需加载
```

### 7.2 关键问题与答案

| 问题 | 答案 |
|------|------|
| **State 何时创建？** | 每次 `invoke(initData)` 调用时，通过 `ChatGraphState::new` 构造器创建 |
| **State 如何在节点间流转？** | 每个节点返回 `Map<String, Object>` 增量，LangGraph 框架自动合并到**新的** `AgentState` 实例中，旧的丢弃 |
| **State 何时销毁？** | `invoke()` 返回后，Controller/Bridge 中引用消失，GC 回收 |
| **`needTool` 谁设置？** | `LlmService.think()`：返回 tool_calls 时设 `true`；`ToolExecuteNode`：执行完工具设 `false` |
| **`toolExecutionRequests` 的双重作用？** | ① `ToolExecuteNode` 用它决定调什么工具；② 下一轮的 `SkillManager.enrich()` 用它决定加载什么 Skill |
| **Skill 何时加载？** | 每轮 `llm_think` 都调 `skillManager.enrich()`，只有上一轮调过工具才加载匹配的 Skill |
| **Skill 加载到哪？** | 拼接到 system prompt 末尾，替换 `messages[0]` |
| **Skill 如何卸载？** | **不写卸载代码。** 下一次 HTTP 请求是全新的 invoke()、全新的 state、toolExecutionRequests 为空 → SkillManager 不追加任何 Skill |
| **`enrich()` 会不会重复累加？** | 不会。`SYSTEM_PROMPT` 是 `static final` 不可变常量，每轮都在原始 base prompt 上追加 |

### 7.3 设计价值随工具数量增长

```
工具量少（6 个）：
  queryWeather     ── 无依赖
  searchInternet   ── 无依赖
  readUrl          ── 无依赖
  searchConversation ── 无依赖
  → 调用链价值 ≈ 0

工具量增多后（>10 个，依赖变复杂）：
  sendEmail         ── 需要先搜收件人邮箱
  uploadToCloud     ── 需要先有文件
  batchProcessDocs  ── 需要文档列表
  createEvent       ── 需要先解析时间
  → 调用链价值随关联度指数增长
```

Skills 系统是在工具数量爆发前做的架构准备。当前 4 个 Skill 只是验证机制走通，真正收益在加 Email、日历、RAG 等强关联工具时才会显现。

### 7.4 相关源码索引

| 文件 | 路径（相对于 `306-wechat-project/`） |
|------|--------------------------------------|
| `ChatGraphState.java` | `wechat-ai-ai/src/.../state/ChatGraphState.java` |
| `ChatGraphConfig.java` | `wechat-ai-ai/src/.../config/ChatGraphConfig.java` |
| `LlmService.java` | `wechat-ai-ai/src/.../ai/LlmService.java` |
| `LlmThinkNode.java` | `wechat-ai-ai/src/.../node/LlmThinkNode.java` |
| `ToolExecuteNode.java` | `wechat-ai-ai/src/.../node/ToolExecuteNode.java` |
| `SkillManager.java` | `wechat-ai-ai/src/.../skill/SkillManager.java` |
| `Skill.java` | `wechat-ai-common/src/.../skill/Skill.java` |
| `WeatherSkill.java` | `wechat-ai-tool/src/.../skill/WeatherSkill.java` |
| `ChatController.java` | `wechat-ai-ai/src/.../controller/ChatController.java` |

---

---

## 8. 我的理解

State这个东西本质上就是一个带名字的HashMap，所有节点都能往里面读写东西，相当于这一轮invoke里面所有工具的一个共享数据池。每个节点干完活就返回一个"我改了啥"的增量Map，LangGraph框架自动把增量合并到旧的State上，造出一个新的State实例，旧的就丢掉了等GC回收。所以每个节点拿到的State都是新对象，但效果上跟你直接往同一个Map里写东西是一样的——后面的节点总能读到前面节点写进去的东西。

invoke和State的关系就是：invoke是执行动作，State是这个动作过程中产生的一串快照。一次invoke等于一次完整的ReAct循环，从用户消息进去开始，到最终回复出来结束，中间不管绕了多少圈tool_calls（比如搜了好几次才找到答案），全都在这一次invoke里面完成。invoke结束了State就销毁，下一次用户消息进来是全新的invoke、全新的State。

整个图的核心其实就是两样东西：控制流和数据流。控制流就是节点和边——节点做什么事，边决定怎么走（顺序走还是条件判断走哪个分支）。数据流就是State——节点之间靠它传东西。控制流在ChatGraphConfig.java里定义，数据流在ChatGraphState.java里定义。LangGraph这种框架的本质就是你把State的结构、节点和边的拓扑定义好，框架自动帮你把数据在节点间传来传去。

条件边说白了就是个if-else路由。llm_think执行完了，框架调你的路由函数，state.isNeedTool()是true就去tool_execute，是false就到END。

整个过程是单线程同步跑的，没有开任何新线程。虽然接口返回的是CompletableFuture看起来像异步，但实际上每个节点都是同步算完结果然后包了个completedFuture()返回，调.get()的时候已经算好了，不用等。

Skills系统的本质就是在llm_think这个节点里插了个队——在调DeepSeek API之前，读一下state里上一轮LLM调了啥工具，把对应的Skill规则拼到system prompt末尾，然后再发请求。就是这么简单一个事，不是什么独立的节点，不是额外的API调用，不是图结构的一部分，就是在发请求之前拼了一段字符串。skillManager.enrich(state, SYSTEM_PROMPT)这行代码就是全部了。

拼接的逻辑也很直白：第1轮llm_think的时候toolExecutionRequests是空的（因为没有"上一轮"），SkillManager一看没调过工具，原样返回base prompt。等LLM返回了tool_calls，llm_think把调用记录写进state。第2轮llm_think再调enrich()的时候，读到上一轮调了queryWeather，就去skillMap里找绑定了queryWeather的Skill，找到WeatherSkill，把它的规则片段拼到prompt末尾。下一轮如果调了searchInternet，就拼WebSearchSkill。每一轮都是重新判断、重新拼接，不依赖缓存。

至于卸载，完全不需要写卸载代码。因为Skill的规则本质上就是system prompt末尾的一段字符串，而State在invoke结束后就销毁了，那段字符串自然跟着没了。下一次用户发消息，新的invoke、新的State、toolExecutionRequests从null开始，第1轮永远不会有Skill。只有LLM在本轮invoke里又调了工具，第2轮才会再次加载对应的Skill。这就相当于把Skill的生存期绑定到了State的生存期上，State的销毁机制免费充当了Skill的卸载机制，你只需要管"什么时候加载"这一个问题就行。

所以整个机制串起来就是：一轮invoke包一整个ReAct循环，State在这轮里给所有节点共享数据，SkillManager在llm_think里读State判断要不要拼规则，invoke结束State销毁Skill自动消失——没有任何多余的复杂度。

> 有任何更新请编辑此文档。
