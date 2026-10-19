# DevLog — 2026-07-29

## 主线：RAG 文档语义检索改造

### 目标

将文档检索从「LLM 调工具 → MySQL FULLTEXT 关键字匹配」改造为「预处理 → Qdrant 向量语义检索 → 注入 system prompt」。

### 架构设计（方案 C）

RAG 检索嵌入 `LlmService.think()`，在 `skillManager.enrich()` 之前执行。不改图拓扑。

```
用户消息 → LlmService.think()
  → embed(消息) → Qdrant.search(sessionId) → 拼接 chunks 到 system prompt
  → skillManager.enrich() → LLM 调用
```

### 新增组件

**ChunkService** — 段落感知文本分块
- maxChars=500, overlapChars=50
- 切分优先级：段落边界 → 换行 → 句末标点 → 硬切

**EmbeddingService** — DashScope text-embedding-v3 调用
- 原生 API：`POST /api/v1/services/embeddings/text-embedding/text-embedding`
- text_type: query（用户消息）/ document（文档索引）
- 维度 1024

**QdrantService** — 云端向量库封装
- 搜索：Spring RestClient POST（验证可行）
- 写入：原生 HttpURLConnection PUT（避 JDK HttpClient 兼容问题）
- 隔离：sessionId filter，只搜当前会话文档
- 检索参数：topK=5, minScore=0.5, Cosine

### 删除

| 文件 | 原因 |
|------|------|
| `SearchDocumentTool.java` | 文档检索不再由 LLM 工具调用 |
| `DocumentSkill.java` | 绑定 searchDocument，工具没了 Skill 孤儿 |
| `DocumentSearchRow.java` | 仅服务于 MySQL FULLTEXT 查询 |
| `DocumentMapper.searchByFulltext/searchByLike` | Qdrant 替代 |
| `DocumentService.search()` | Qdrant 替代 |
| `DocumentServiceImpl.search/extractSnippet` | Qdrant 替代 |

### 修改的关键点

| 文件 | 改动 |
|------|------|
| `LlmService.java` | +EmbeddingService/QdrantService 注入；think() 中加 RAG 检索环节 |
| `DocumentServiceImpl.java` | +ChunkService/EmbeddingService/QdrantService；upload/parse/createTextDocument 后自动索引；delete 清理 Qdrant |
| `WechatChatBridge.java` | MemorySaver 泄漏修复：threadId 从 `userId_counter++` 改为 `userId`（每用户一个 checkpoint） |
| `application.properties` | +Qdrant/Embedding/RAG 配置项 |

---

## 调试记录

### 1. ChunkService 死循环
**现象**：上传文件后日志停在 `【RAG】切块完成`，后续 embedding 永不执行，线程卡死。
**原因**：当剩余内容 < overlapChars 时，`start = end - overlapChars` 回到之前位置，start < content.length() 永远成立。
**修复**：最后一段直接 `content.substring(start).strip()` 后 break。

### 2. Qdrant REST API filter 格式
**现象**：搜索报 `400 data did not match any variant of untagged enum MatchInterface`。
**原因**：gRPC API 用 `"match": {"keyword": "xxx"}`，REST API 用 `"match": {"value": "xxx"}`。
**修复**：全部改为 `"value"`。

### 3. Qdrant payload 索引缺失
**现象**：搜索报 `Index required but not found for "sessionId"`。
**原因**：Qdrant 的 payload 字段需要显式建索引才能 filter。
**修复**：`ensureCollection()` 中加 `PUT /collections/{name}/index` 创建 sessionId 和 fileId 的 keyword 索引。

### 4. JDK HttpClient PUT Connection reset
**现象**：REST API 的搜索（POST）正常，但 upsert（PUT）报 `Connection reset`。
**原因**：JDK HttpClient 对某些 Qdrant Cloud TLS 配置的 PUT 请求存在兼容问题。
**修复**：写入操作改用 Java 原生 `HttpURLConnection`，读取保持 RestClient。

### 5. MemorySaver 检查点无限增长 → OOM
**现象**：应用运行几分钟后 `java.lang.OutOfMemoryError: Java heap space`，调用栈在 MyBatis/OkHttp/Tomcat 各处。
**原因**：每次图调用生成唯一 threadId（`userId_counter++`），MemorySaver 为每个 threadId 保存完整检查点，永不清理。堆被检查点撑爆后，任何新分配都可能触发 OOM。
**修复**：threadId 改为直接使用 userId，每用户只维护一个检查点，新调用自动覆盖。

### 6. Embedding API 格式
**初始错误**：使用了 OpenAI 兼容格式 `POST /compatible-mode/v1/embeddings` → `{model, input: [...]}`。
**修正**：DashScope 原生 API 格式为 `POST /api/v1/services/embeddings/text-embedding/text-embedding` → `{model, input: {texts: [...]}, parameters: {text_type: "query"|"document"}}`。

---

## 验证结果

测试文档：`产品说明文档.txt`（1328 字符，智能门锁 X1 说明书）

```
切块: 4 chunks
Embedding: 4 向量 (1024 维)
Qdrant 索引: 成功
检索: "门锁保修多久" → 命中 4 个 chunks
LLM 回复: 准确引用文档内容（整机保修3年，热线400-888-1234）
```

全链路延迟约 2-3 秒（embed ~1s + Qdrant search ~1.5s + LLM ~3s）。

---

## 遗留问题

1. **延迟偏高**：无文档的会话也在做无意义的 embedding 搜索。`DocumentMapper.countBySessionId()` 已加，待接入 `LlmService.think()` 做跳过判断。
2. **Qdrant 配置为空**：`application.properties` 中 Qdrant URL/API Key 需用户自行填写后提交。
3. **MemorySaver 修复需验证**：threadId 改为 userId 后，图中断（WRITE 工具确认）的 resume 流程是否正常，未覆盖测试。
