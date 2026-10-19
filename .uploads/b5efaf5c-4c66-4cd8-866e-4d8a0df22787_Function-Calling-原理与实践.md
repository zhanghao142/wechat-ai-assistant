# Function Calling（函数调用）原理与实践

> 项目：Wechat AI Assistant — 微信智能助手
> 更新日期：2026-07-21

---

## 目录

1. [什么是 Function Calling](#1-什么是-function-calling)
2. [OpenAI Function Calling API 工作流](#2-openai-function-calling-api-工作流)
3. [与传统 API 的区别](#3-与传统-api-的区别)
4. [本项目的实现架构](#4-本项目的实现架构)
5. [ReAct 循环详解](#5-react-循环详解)
6. [工具注册与发现机制](#6-工具注册与发现机制)
7. [如何编写自己的工具](#7-如何编写自己的工具)
8. [现有工具一览](#8-现有工具一览)

---

## 1. 什么是 Function Calling

### 1.1 定义

**Function Calling（函数调用）** 是大语言模型（LLM）的一项能力：LLM 在生成回复时，可以输出一个结构化的调用请求（函数名 + 参数），而不是直接输出文本。开发者收到这个请求后，在自己的代码中执行对应的函数，并将结果回传给 LLM，LLM 再结合结果生成最终回复。

### 1.2 核心思想：AI 是大脑，工具是手脚

```
用户提问
    │
    ▼
┌─────────────┐     需要外部数据？
│  LLM 推理    │─────►  输出 tool_calls
│  (大脑)      │◄────  回填 tool_result
└─────────────┘
    │
    ▼
┌─────────────┐
│  执行工具    │  ─── 查天气 / 搜文档 / 读网页 / 搜索历史 ...
│  (手脚)      │
└─────────────┘
```

LLM **不直接执行代码**，它只决定"需要调什么工具、传什么参数"并返回一个 JSON 描述。真正的执行发生在你的后端代码里。

### 1.3 为什么需要 Function Calling？

| 问题 | Function Calling 的解决 |
|------|----------------------|
| LLM 知识有截止日期，不知道实时信息 | 调天气 API、搜索互联网 |
| LLM 不知道你的私有数据 | 调文档搜索工具，从数据库检索 |
| LLM 无法操作外部系统 | 调 API 查库存、发邮件、创工单 |
| LLM 没有记忆（至少不持久） | 调历史对话搜索，从 MySQL 找回上下文 |

---

## 2. OpenAI Function Calling API 工作流

### 2.1 标准请求格式

```json
{
  "model": "deepseek-chat",
  "messages": [
    {"role": "system", "content": "你是一个智能助手..."},
    {"role": "user", "content": "北京今天天气怎么样？"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "queryWeather",
        "description": "查询指定城市的实时天气",
        "parameters": {
          "type": "object",
          "properties": {
            "city": {
              "type": "string",
              "description": "城市名称"
            }
          },
          "required": ["city"]
        }
      }
    }
  ]
}
```

### 2.2 返回格式

**情况 A：LLM 决定调用工具**

```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [
        {
          "id": "call_xxx",
          "type": "function",
          "function": {
            "name": "queryWeather",
            "arguments": "{\"city\": \"北京\"}"
          }
        }
      ]
    }
  }]
}
```

**情况 B：LLM 直接回复**

```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": "北京今天晴，32°C..."
    }
  }]
}
```

### 2.3 回填工具结果

工具执行完成后，以 `role: "tool"` 将结果回填给 LLM，**tool_call_id 必须匹配**：

```json
{
  "role": "tool",
  "tool_call_id": "call_xxx",
  "content": "{\"city\":\"北京\",\"weather\":\"晴\",\"temperature\":32}"
}
```

完整的多轮对话示例：

```
messages: [
  {"role": "system",       "content": "你是一个智能助手..."},
  {"role": "user",         "content": "北京今天天气怎么样？"},
  {"role": "assistant",    "content": null, "tool_calls": [...]},     ← LLM 决定查天气
  {"role": "tool",         "content": "{"city":"北京","weather":"晴"}", "tool_call_id": "call_xxx"},  ← 工具执行结果
  {"role": "assistant",    "content": "北京今天晴，32°C，适合出门~"}    ← LLM 生成最终回复
]
```

---

## 3. 与传统 API 的区别

| 维度 | 传统 API（硬编码） | Function Calling |
|------|------------------|-----------------|
| **交互方式** | 固定请求 → 固定响应 | LLM 自主决定调不调、调哪个 |
| **参数来源** | 前端/客户端手动传参 | LLM 理解用户自然语言后自动生成参数 |
| **工具集成** | 每个功能写一套 Controller + Service | 一个 `@Tool` 方法即可注册 |
| **扩展性** | 加功能 = 加接口 + 改前端逻辑 | 加功能 = 加一个带 `@Tool` 的 Bean |
| **错误处理** | 后端返回错误码，前端处理 | 工具失败结果回填给 LLM，它能自行重试/修正 |
| **多工具编排** | 需硬编码编排逻辑 | LLM 自行决定调用顺序和是否多次调用 |
| **自然语言容错** | 用户必须按格式输入 | 用户说"北京热不热"也会自动匹配天气工具 |

### 什么时候该用 Function Calling？

✅ **适合场景**：
- 需要 LLM 理解自然语言后触发的操作（查天气、搜文档、搜互联网）
- 调用方无法预判用户意图的场景
- 工具种类多、变化频繁的场景

❌ **不适合场景**：
- 纯计算或纯文本生成（不需要外部数据）
- 高频低延迟的简单查询（Function Calling 至少多 1-2 轮网络交互）
- 安全性要求极高，不允许 LLM "自主决定"的操作

---

## 4. 本项目的实现架构

本项目的 Function Calling 基于 **LangGraph** 框架实现 **ReAct 循环**，整体分为三层：

### 4.1 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│                   微信消息入口 (WechatInboundHandler)          │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│              AI 引擎桥梁 (WechatChatBridge)                    │
│  - 管理用户级线程池（每个用户独立单线程）                          │
│  - 维护用户上下文缓存（ConcurrentHashMap）                       │
│  - 调用 StateGraph.compile().invoke()                          │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│              LangGraph 状态图 (StateGraph)                     │
│                                                              │
│  START ──► prepare_input ──► session_prepare ──► llm_think   │
│                                        │                      │
│                                  ┌─────┴─────┐               │
│                                  │ need_tool? │               │
│                                  └─────┬─────┘               │
│                                   ▼          ▼               │
│                                tool      done                │
│                                  │          │                │
│                                  ▼          ▼                │
│                           tool_execute    END                │
│                                  │                           │
│                                  └──► llm_think (循环)        │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 各节点职责

| 节点 | 文件 | 职责 |
|------|------|------|
| `prepare_input` | `PrepareInputNode.java` | 预留：参数归一化、校验 |
| `session_prepare` | `SessionPrepareNode.java` | 获取/创建会话，从 MySQL 加载历史消息（最多 300 条），修复 tool 链断裂 |
| `llm_think` | `LlmThinkNode.java` → `LlmService.java` | 调 DeepSeek API，携带工具清单，解析 tool_calls |
| `tool_execute` | `ToolExecuteNode.java` | 反射调用 `@Tool` 方法，回填结果，持久化 |

### 4.3 数据流：一次完整的对话

以用户问"北京天气"为例：

```
Step 1: 用户发消息 "北京天气怎么样？"
    │
Step 2: session_prepare
    ├── 从 MySQL 加载历史消息（首次对话则空列表）
    └── 获取或创建 SessionEntity
    │
Step 3: llm_think（第 1 轮）
    ├── system prompt + 用户消息 + 工具清单 → DeepSeek API
    ├── LLM 返回 tool_calls: [{ name: "queryWeather", arguments: {city: "北京"} }]
    ├── 状态标记 needTool = true
    └── 保存 assistant 消息到 MySQL（含 tool_calls JSON）
    │
Step 4: tool_execute
    ├── 解析 tool_calls，查找 QueryWeatherTool.queryWeather 方法
    ├── 反射调用，传参 city="北京"
    ├── 得到结果: {city:"北京", weather:"晴", temperature:32, ...}
    ├── 以 role:tool 回填到 messageHistory
    └── 保存 tool 结果到 MySQL
    │
Step 5: llm_think（第 2 轮）
    ├── system + user + assistant(tool_calls) + tool(result) → DeepSeek API
    ├── LLM 生成最终回复: "北京今天晴，32°C，西南风4级~"
    ├── 状态标记 needTool = false
    └── 保存 assistant 消息到 MySQL
    │
Step 6: WechatChatBridge 取 reply 内容，调用 WechatOutboundSender 发消息
```

---

## 5. ReAct 循环详解

### 5.1 什么是 ReAct？

**ReAct = Reasoning + Acting**，是 Function Calling 最经典的实现模式：

1. **Reasoning**：LLM 推理，判断是否需要外部数据
2. **Acting**：执行工具获取数据
3. 重复 1-2 直到 LLM 认为不需要更多数据
4. 生成最终回复

### 5.2 本项目的循环路由

在 `ChatGraphConfig.java` 中：

```java
.addConditionalEdges("llm_think",
    state -> state.isNeedTool() ? "tool" : "done",
    Map.of("tool", "tool_execute", "done", END))
.addEdge("tool_execute", "llm_think");  // ← 关键：执行完工具回到 LLM
```

- `llm_think` 输出 `needTool=true` → 路由到 `tool_execute`
- `tool_execute` 执行完毕 → 自动回到 `llm_think`（第 2 轮推理）
- `llm_think` 输出 `needTool=false` → 结束

### 5.3 消息上限控制

`LlmService.java` 做了消息截断保护：

```java
int MAX_ROUNDS = 150;  // 最多保留 150 轮对话
```

超过上限时：
1. 只保留最近 150 轮的消息
2. 修复截断后可能断裂的 tool 链
3. 如果全是 tool/assistant 残片，清空让 LLM 重新开始

---

## 6. 工具注册与发现机制

### 6.1 架构

```
@Component          @Tool @P
  │                   │
  ▼                   ▼
QueryWeatherTool ──► ToolRegistryImpl
                        │
                        ├── 扫描所有 Spring Bean
                        ├── 提取 @Tool 方法的 name、description
                        ├── 从 @P 构建参数 JSON Schema
                        └── 列出给 LlmService 拼接到 API 请求
```

### 6.2 核心流程（ToolRegistryImpl.java）

```
@PostConstruct init()
    │
    ▼
scanToolBeans()
    ├── 遍历所有 Spring Bean
    ├── 对每个 Bean 的方法，检查 @Tool 注解
    ├── 提取方法名 → toolName
    ├── 提取 @Tool value → description
    ├── 提取 @P 注解 → 参数描述 + JSON Schema
    ├── 防冲突：同名工具只注册第一个（打 warn 日志）
    └── 存入 Map<String, ToolEntry>(toolName, definition + bean)
```

### 6.3 参数 Schema 自动生成

| Java 类型 | JSON Schema type |
|-----------|-----------------|
| `String` | `"string"` |
| `int` / `Integer` | `"integer"` |
| `long` / `Long` | `"integer"` |
| `double` / `Double` | `"number"` |
| `boolean` / `Boolean` | `"boolean"` |
| `List` / `Set` | `"array"` (items: string) |
| 其他类型 | `"object"` |

`@P("描述文字")` 会自动填充到参数的 `description` 字段，帮助 LLM 理解参数含义。

### 6.4 工具启停

通过 `ToolController` 提供的 HTTP 接口，可以在运行时动态启用/禁用工具：

```java
toolRegistry.toggle("queryWeather", false);  // 禁用天气工具
```

禁用后：
- `listTools()` 返回时 `isEnabled() = false`，LLM 看不到这个工具
- `getEnabledToolBeans()` 不再包含该 Bean，不会被调用

---

## 7. 如何编写自己的工具

### 7.1 编写步骤

**三步即可完成一个新工具：**

#### 第一步：创建 Spring Bean

```java
package com.wechatai.tool.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component  // ← 必须，让 Spring 扫描到
public class MyNewTool {

    // 通过构造注入依赖
    private final SomeService someService;
    
    public MyNewTool(SomeService someService) {
        this.someService = someService;
    }
```

#### 第二步：编写方法并标注 `@Tool` 和 `@P`

```java
    @Tool("工具描述 —— 这里写清楚工具能做什么，直接影响 LLM 选工具的准确率")
    public ReturnType myToolMethod(
            @P("参数描述 —— 告诉 LLM 这个参数是什么意思") String param1,
            @P("参数描述") Integer param2) {
        
        // 业务逻辑
        return result;
    }
}
```

#### 第三步：方法返回对象自动序列化为 JSON

```java
public record ReturnType(String field1, int field2) {}
// 或 @Data 的 POJO，ToolExecuteNode 会自动用 ObjectMapper 序列化
```

### 7.2 最佳实践

| 要点 | 说明 |
|------|------|
| **`@Tool` 描述要精准** | 这是 LLM 判断「该不该用这个工具」的唯一依据，描述模糊会导致 LLM 该调时不调，或不该调时乱调 |
| **`@P` 描述要具体** | 告诉 LLM 参数格式，如 `"城市名称，如：北京"` 而非 `"城市"` |
| **返回值要完整** | LLM 依靠返回值生成回复，数据越全回复越好 |
| **参数类型用基本类型** | `String`, `int`, `boolean` 等，避免复杂对象导致序列化失败 |
| **方法名即工具名** | LLM 通过方法名选择工具，命名要语义化且唯一 |
| **方法内尽量 try-catch** | 工具抛出异常会被 `ToolExecuteNode` 捕获并以 error 回填给 LLM，尽量返回友好错误信息而非抛异常 |

### 7.3 完整示例：QueryWeatherTool

```java
@Component
public class QueryWeatherTool {

    @Tool("查询指定城市的实时天气，支持中国城市及国际主要城市（东京、伦敦等）")
    public WeatherResponse queryWeather(
            @P("城市名称，如：北京、东京、London") String city) {

        // 调外部 API
        return restClient.get()
                .uri("https://uapis.cn/api/v1/misc/weather?city={city}&lang=zh", city)
                .retrieve()
                .body(WeatherResponse.class);
        // ↑ 返回 POJO，自动序列化为 JSON 回填给 LLM
    }
}
```

---

## 8. 现有工具一览

| 工具名 | 实现类 | 功能 | 参数 |
|--------|--------|------|------|
| `queryWeather` | `QueryWeatherTool.java` | 查实时天气 | `city`: 城市名称 |
| `searchDocument` | `SearchDocumentTool.java` | 文档内容搜索 | `keywords`: 关键词, `fileIds`: 限定文件(可选), `maxResults`: 返回数(可选) |
| `getDocumentSummary` | `GetDocumentSummaryTool.java` | 文档摘要统计 | `fileId`: 文档 ID |
| `readUrl` | `ReadUrlTool.java` | 读取网页正文 | `url`: 完整的 URL 地址 |
| `searchInternet` | `ReadUrlTool.java`（同一类） | 必应搜索互联网 | `query`: 搜索关键词 |
| `searchConversation` | `ConversationSearchTool.java` | 搜索历史对话 | `keyword`: 搜索关键词 |

### 工具分类

| 分类 | 说明 | 工具 |
|------|------|------|
| **READ** | 查询类，只读不写 | 全部现有工具都是 READ |

---

## 9. Skills 系统（2026-07-21 实现）

### 9.1 动机

随着工具增多，SYSTEM_PROMPT 膨胀到 ~100 行，管理困难。核心问题不在基础规则（那些本来就该一直存在），而在于**工具格式规则**——天气格式只在查了天气才有用，文档格式只在搜了文档才有用。

**核心设计**：格式规则绑定到对应的工具上，LLM 调了哪个工具，下一轮才加载对应的规则。

### 9.2 Skill 接口

```java
// wechat-ai-common/.../skill/Skill.java
public interface Skill {
    String getName();
    List<String> getBoundTools();       // 绑定的工具名
    String getPromptSegment();           // 规则片段
}
```

放在 `wechat-ai-common`，不产生新依赖。

### 9.3 加载机制

```
用户消息 → invoke() 开始
            │
            ├── llm_think 第 1 轮
            │     → toolExecutionRequests 为空 → 不加 Skill
            │     → LLM 返回 tool_calls [queryWeather]
            │     → state 记下调了 queryWeather
            │
            ├── tool_execute → 执行天气
            │
            ├── llm_think 第 2 轮
            │     → SkillManager 读上一轮的 toolExecutionRequests
            │     → 发现调了 queryWeather → 加载 WeatherSkill 到 system prompt
            │     → LLM 看到天气格式规则 + 组合规则
            │
            └── invoke() 结束 → state 销毁 → Skill 自动卸载
```

**加载时机**：只在 `llm_think` 开始前检查上一轮调了哪些工具
**卸载时机**：`invoke()` 结束，state 自动销毁

> 💡 **设计亮点：零销毁代码**
>
> Skill 的卸载由 LangGraph 的 state 生命周期天然解决，不需要写任何销毁逻辑：
> ```
> 用户发"南京天气" → invoke() 开始 → 全新 state, skills 为空
>   → llm_think 第1轮: 不加 Skill
>   → LLM 调 queryWeather
>   → llm_think 第2轮: 加载 WeatherSkill ✅
>   → llm_think 第3轮: LLM 回复结束 → invoke() 结束 → state 销毁
>
> 用户再发"帮我搜个新闻" → 新的 invoke() → 全新 state, skills 再次为空 ✅
> ```
> 对比传统的"按上下文条件激活"方案需要显式判断何时激活、何时卸载，我们的方案将 Skill 绑定到工具调用，生命周期自动跟随 ReAct 循环：**加载由工具调用触发，卸载由 invoke 结束自然完成**。0 行销毁代码。

### 9.4 已实现的 Skill（4 个）

所有 Skill 放在 `wechat-ai-tool/.../skill/` 包下，与对应的工具实现同模块：

| Skill | 绑定工具 | 规则内容 | 源文件 |
|-------|---------|---------|--------|
| `WeatherSkill` | `queryWeather` | 🌤 天气格式 + 下雨/晴天景点推荐组合规则 | `wechat-ai-tool/.../skill/WeatherSkill.java` |
| `DocumentSkill` | `searchDocument`, `getDocumentSummary` | 📄 文档检索回复格式 + 摘要格式 | `wechat-ai-tool/.../skill/DocumentSkill.java` |
| `WebSearchSkill` | `readUrl`, `searchInternet` | 🔗 网页搜索与链接处理规则 | `wechat-ai-tool/.../skill/WebSearchSkill.java` |
| `ConversationSearchSkill` | `searchConversation` | 💬 历史对话搜索规则 | `wechat-ai-tool/.../skill/ConversationSearchSkill.java` |

### 9.5 系统级规则（留在 BASE SYSTEM_PROMPT）

以下规则不属于任何特定工具，始终存在于 base prompt 中：

- 性格设定
- 工具清单描述
- 语音输出标记规则
- 未找到/错误回复格式
- 回复红线（不写 Markdown、200 字内等）
- 图片消息处理规则

### 9.6 设计意图：价值随工具数量增长

当前 6 个工具之间关联性弱，Skill 系统的收益有限。这是**预期之内**的，因为 Skills 解决的问题是「工具之间的协作规范」，而非单个工具的格式规则。

```
工具量少（6 个）：
  queryWeather    ── 无依赖
  searchInternet  ── 无依赖  
  readUrl         ── 无依赖
  searchDocument  ── 无依赖
  searchConversation─ 无依赖
  → 工具调用链的价值 ≈ 0

工具量增多后（>10 个，依赖变复杂）：
  sendEmail         ── 需要先搜收件人邮箱
  uploadToCloud     ── 需要先有文件
  batchProcessDocs  ── 需要文档列表
  createEvent       ── 需要先解析时间
  → 工具调用链的价值随着关联度指数增长
```

**Skills 系统是在工具数量爆发前做的架构准备**，不是解决当下问题的。当前的 4 个 Skill 更多是验证「按工具绑定的加载/卸载机制」能走通，等加 Email、日历、RAG 等强关联工具时，按工具组织组合规则的价值才会显现。

### 9.7 与 Function Calling 的对比

```
Function Calling 层:  @Tool + @P  → 原子操作描述（工具能做什么）
Skill 层:             Skill 接口    → 行为规则封装（工具返回后该怎么回复）
                                                                                
一致的抽象哲学: 声明式 + 按需加载
```

### 9.7 下一步：工具组合规范

当前各 Skill 已包含基本的组合规则（WeatherSkill 有天气+景点），但缺少统一的**工具混合调用规范**。后续可考虑：

**方案 A**：分散到各个 Skill 中，每个 Skill 带自己的组合规则
**方案 B**：独立的 `ToolCombinationSkill`，集中管理所有跨工具协作规范

---

## 附：项目模块依赖关系

```
wechat-ai-common       ← 公共枚举、常量、工具类
wechat-ai-tool         ← 工具定义 + 注册中心（@Tool 注解扫描）
wechat-ai-session      ← 会话 + 消息持久化（MySQL + MyBatis）
wechat-ai-document     ← 文档解析与检索
wechat-ai-ai           ← AI 引擎核心（LangGraph 图 + LlmService）
wechat-ai-wechat       ← 微信消息对接（收消息、发消息）
```

**数据流**：`wechat` → `ai` → `tool` + `session` + `document`

---

> 有任何更新请编辑此文档。
