# SynapseAI 深度解析 & 简历素材

> 本文基于 2026-09-19 全量代码核查（130 个 Java 文件、25 个 SQL、16 个前端 JS）。
> 分两部分：**项目全景解析**（面试能讲清楚）+ **可直接抄的简历文案**（含诚实边界，避免面试翻车）。

---

## 一、30 秒定位（简历项目标题行）

**SynapseAI —— 以即时通讯为底座的 AI 能力集成平台**
Spring Boot 3 + WebSocket 双通道实时通信，内置 AI 助手、学习空间、朋友圈、绘画、管理后台；通过自研 **MCP 功能框架**把 AI 能力做成可配置、可热启停的插件式模块。

技术栈一行版（按需裁剪，别全塞）：
`Spring Boot 3 · Java 17 · MyBatis-Plus · MySQL 5.7 · Redis · WebSocket(STOMP/SockJS) · Spring MVC · MinIO · 阿里云百炼(openai-java) · SSE · WebRTC 信令 · 原生 JS`

---

## 二、可写进简历的量化数字

| 指标 | 数值 | 说明 |
|---|---|---|
| 后端 Java | 129 个文件 / 12,852 行 | 不含 target 产物 |
| 前端 | 16 个 JS 模块 10,060 行 + CSS/HTML 5,308 行 | 零构建、原生模块化 |
| REST 接口 | 160 个 | 覆盖 18 个 Controller |
| 数据库 | 28 张表 / 66 个索引 / 25 个迁移脚本 | 全量 + 增量双轨 |
| 实时通道 | 3 类 Topic | 单播 / 群播 / 私有会话 |
| 定时任务 | 2 个（20s、60s） | 炸弹引爆、胶囊解锁 |

> 写简历时挑 2-3 个数字即可，例如「约 1.3 万行后端代码、160 个 REST 接口、28 张业务表」。

---

## 三、系统架构（讲项目时的骨架）

```
前端（原生 JS，无构建）
  window.App / Api / Ws / Chat / Friend / Group / Moment / Call / Study / Mcp …
      │ REST(X-Token)              │ WebSocket(STOMP over SockJS)   │ fetch 流式
      ▼                            ▼                                 ▼
  拦截器链                        /ws 握手                        /api/ai/stream
  AuthInterceptor → FrozenGuard   AuthChannelInterceptor          SseEmitter
      │                            │                                 │
      ▼                            ▼                                 ▼
  Controller ──► Service ──► Mapper（显式 @Select/@Insert，不继承 BaseMapper）
                    │
        ┌───────────┼───────────┬────────────┬─────────────┐
        ▼           ▼           ▼            ▼             ▼
      MySQL      Redis       MinIO     阿里云百炼      定时任务
   28 张表     Token/缓存   对象存储    OpenAI 兼容     炸弹/胶囊
```

三条关键链路：

1. **消息链路**：客户端 → WS `/app/chat.send` → 服务端身份取自 `Principal`（不信任报文里的 senderId）→ 冻结/拉黑/限流前置裁决 → 落库 → 双端 Topic 推送 → 回执。
2. **AI 链路**：`AiService`（openai-java SDK，OpenAI 兼容协议）→ 同步 `chat()` / 流式 `streamChat()` → `SseEmitter` 打字机推前端。
3. **MCP 链路**：管理端配置功能开关 → `/api/mcp/functions` 下发 → 自然语言 → AI 解析成 JSON → 收件人四类解析 → 预览 → 批量落库推送。

---

## 四、功能模块地图

| 模块 | 能力 | 技术点 |
|---|---|---|
| 用户/鉴权 | 注册登录、邮箱验证码、封禁冻结、强制下线 | 内存 TokenStore + Redis 反向索引精确踢人 |
| 好友/群聊 | 申请同意、备注、拉黑、群成员管理 | SQL 层 `EXISTS group_member` 防越权 |
| 实时消息 | 单聊群聊、撤回、已读、离线补拉、消息搜索 | 自增 id 断点补拉 + 前端 `data-mid` 去重 |
| 创新玩法 | 时间胶囊、消息炸弹、隐身阅读、积分亲密度 | AES-256-GCM、状态机、Redis 计数器防刷 |
| AI 助手 | 多轮对话、流式输出、独立人设、AI 内容审核 | SSE、role=AI 账号体系、审核降级转人工 |
| MCP 框架 | 可插拔 AI 能力，内置消息群发助手 | 配置驱动热启停、NL→JSON→业务执行 |
| 学习空间 | 计划、打卡、番茄钟、AI 会话子线 | 流式增量落库（刷新可恢复） |
| 朋友圈 | 发布、点赞评论、两级可见性、审核状态机 | AI 审核 + 人工复核状态流转 |
| 通话 | 语音通话 | WebRTC + 服务端纯信令中继 |
| 管理后台 | 用户治理、内容审计、公告、AI/MCP 配置 | 27 个管理端点 |

---

## 五、简历亮点（按含金量排序，可直接抄）

### 亮点 1：REST + WebSocket 双通道实时通信架构 ★★★★★

**简历写法**
> 设计 REST + WebSocket(STOMP/SockJS) 双通道通信架构：握手阶段解析 Token 绑定 Principal，业务侧拒绝信任报文中的发送者身份；按单播/群播/私有会话三类 Topic 分发消息，配合自增 id 断点补拉与指数退避重连（1s→15s），实现断线消息零丢失。

**实现位置**：`WebSocketConfig.java:25-51`、`AuthChannelInterceptor.java:24-35`、`MessageMapper.java:183-202`、`ws.js:186-209`

**追问准备**
- Q：为什么用内存 `SimpleBroker`？多实例怎么办？
  A：单实例演示足够；水平扩展需替换为 RabbitMQ/Redis 中继 Broker，让任意节点都能向任意用户推送。
- Q：消息顺序和重复怎么保证？
  A：顺序依赖自增 id，重复由前端 `data-mid` 判重；**服务端幂等未实现**，改进方案是客户端生成 `clientMsgId` 并在 DB 建唯一键。

---

### 亮点 2：MCP 可插拔 AI 能力框架（最有差异化）★★★★★

**简历写法**
> 设计配置驱动的 MCP 功能框架：AI 能力以功能行持久化（唯一 code、开关、JSON 配置），管理端可实时启停，前端按已启用能力动态渲染入口；内置「消息群发助手」实现自然语言 → AI 结构化 JSON → 收件人四类解析（账号/昵称/UID/关键词）→ 去重预览 → 批量落库推送的完整 Agent 化链路，单批上限 50 人并排除自发送。

**实现位置**：`McpService.java:19-304`、`AdminMcpController.java:46-138`、`mcp_config` 表

**追问准备**
- Q：热启停怎么实现的？
  A：每次调用直查 `mcp_config`（目前无缓存），改库即生效；可优化为 Caffeine 本地缓存 + 变更事件刷新。
- Q：AI 返回 JSON 不可靠怎么办？
  A：做首尾花括号截取并统一 `asText()` 取值（避免 `toString()` 带引号导致匹配失败）；解析失败返回可读错误，不静默发送。

---

### 亮点 3：AI 流式输出 + 学习空间增量落库 ★★★★☆

**简历写法**
> 基于 `SseEmitter` 与 openai-java SDK 实现 AI 流式对话（打字机效果），前端用 fetch + `ReadableStream` 自行分帧解析并支持 `AbortController` 中断；学习空间按「子线 thread」隔离多轮上下文，流式过程中逐 token 回写数据库占位行，页面刷新可恢复未生成完的回复。

**实现位置**：`AiService.java:107-158`、`AiController.java:89-146`、`api.js:264-335`、`StudyChatService.java:92-105`

**追问准备**
- Q：为什么不用原生 EventSource？
  A：EventSource 不支持自定义请求头（带不了 X-Token）和中断，故用 fetch 流自行解析。
- Q：推理模型输出带思考链怎么办？
  A：通过 `putAdditionalBodyProperty("enable_thinking", false)` 关闭，避免污染要求纯 JSON 的返回。

---

### 亮点 4：时间胶囊 AES-256-GCM 密态存储 ★★★★☆

**简历写法**
> 实现时间胶囊功能：正文采用 AES-256-GCM 加密存储，每次加密生成随机 12 字节 IV 并随密文一起 Base64 保存，密钥由主口令 SHA-256 派生；到期前服务端不下发正文与密文，由 60s 定时任务扫描到期记录解锁并推送站内通知与 WebSocket 事件。

**实现位置**：`CapsuleCrypto.java:42-96`、`TimeCapsuleService.java:113-173`

**追问准备**
- Q：为什么选 GCM 而不是 CBC？
  A：GCM 自带完整性校验（128 位 tag），能防止密文被篡改；配合随机 IV 可安全复用同一密钥。
- Q：密钥管理？
  A：当前由配置口令派生，生产应托管到 KMS 并支持按胶囊轮换。

---

### 亮点 5：消息炸弹状态机（双触发）★★★★☆

**简历写法**
> 设计消息炸弹玩法：发送方设置 30s 倒计时，消息以 PENDING 状态落库并写入到期时间；接收方回复即在消息链路上即时拆弹（PENDING→REPLIED），超时则由 20s 定时任务扫描到期记录引爆（PENDING→EXPLODED，覆写正文并双端推送），形成「事件触发 + 定时兜底」的双保险状态机。

**实现位置**：`ChatController.java:133-200`、`InnovationTasks.java:48-73`、`MessageMapper.java:205-211`

**追问准备**
- Q：定时扫描的精度问题？
  A：20s 扫描意味着最多 20s 延迟；更精准的做法是延迟队列（Redis ZSet / RocketMQ 定时消息），扫描仅作兜底防漏。

---

### 亮点 6：MySQL 5.7 幂等迁移方案 ★★★☆☆

**简历写法**
> 针对 MySQL 5.7 不支持 `ADD COLUMN IF NOT EXISTS` 的限制，设计「存储过程 + `CONTINUE HANDLER` 吞异常」的幂等迁移模板，25 个增量脚本可重复执行、不覆盖线上数据；全量建表脚本与增量脚本分离管理。

**实现位置**：`update-sql/*.sql`、`sql/schema.sql`

---

### 亮点 7：缓存与防刷 ★★★☆☆

**简历写法**
> 引入 Redis 缓存用户安全信息/朋友圈 Feed/通知（30s~10min 分级 TTL），配置 `CacheErrorHandler` 在 Redis 故障时降级回源、不阻断业务；在线用户统计用 SCAN+MGET 替代 KEYS 避免阻塞；积分与亲密度用 Redis 日计数器做每日上限（积分 200/天、亲密度 100/天）防刷，替代 `SUM` 聚合查询。

**实现位置**：`CacheConfig.java:54-115`、`TokenStore.java:93-118`、`PointsService.java:89-155`

---

### 亮点 8：前端工程与 WebRTC 语音通话 ★★★☆☆

**简历写法**
> 前端零构建，14 个原生 JS 模块按职责划分（消息/好友/群聊/朋友圈/通话/学习/绘画/MCP）并以命名空间挂载；语音通话基于 WebRTC `RTCPeerConnection`，服务端仅做 OFFER/ANSWER/ICE 信令中继并强制使用 Principal 身份防止信令冒用。

**实现位置**：`call.js:33-102`、`CallController.java:25-58`

---

## 六、按求职方向的取舍

| 方向 | 主打亮点 | 弱化 |
|---|---|---|
| Java 后端 | 1、5、6、7（架构/状态机/SQL/缓存） | 前端、WebRTC |
| 全栈 | 1、3、8（双通道 + 流式 + 前端工程） | SQL 迁移细节 |
| AI 应用 / Agent | 2、3（MCP 框架 + 流式 + 结构化输出） | 朋友圈审核、积分 |

---

## 七、面试高频追问 Q&A

1. **多实例部署会出什么问题？** 内存 TokenStore、验证码、限流均失效，定时任务重复执行 → 改为 Redis 存储 + ShedLock 分布式锁。
2. **消息已读未读怎么做的？** 逐条 update；隐身阅读仍更新 DB（保证多端同步）但不推 READ 回执。
3. **群聊历史分页的索引命中吗？** `(target_type, target_id, create_time)` 覆盖群聊；单聊因 `OR` 双向条件无法完全命中，改进方向是冗余 `conversation_id` 字段。
4. **AI 审核不准怎么办？** AI 判定 FAIL 时转 PENDING 人工复核状态机，AI 服务异常时降级放行，不阻断用户发布。
5. **为什么不用 Spring Security？** 轻量 Token 方案足够，避免过度设计；代价是权限模型较简单。
6. **文件上传安全？** 经 MinIO 存储；目前只判空，未做类型白名单（可改进点）。
7. **如何防止消息风暴？** 加急消息 10s 内存限流；群聊场景下需 Redis 令牌桶 + 批量合并推送。
8. **DB 连接与慢查询？** HikariCP 20 连接；无慢查询日志（可改进点）。
9. **项目最大的技术难点？** 建议答「实时链路的可靠性」：握手鉴权 + 前置裁决 + 自增 id 补拉 + 退避重连，这四件事让断网重连后消息不丢不重。
10. **有没有做压测/监控？** 未做（如实回答，并说明会补：actuator 已引入但未配置）。

---

## 八、⚠️ 诚实边界 — 这些**不能写**

核查确认的未实现项，写了容易在面试中被问穿：

- ❌ 密码加密存储（当前明文，代码注释自述"演示用"）
- ❌ 分布式限流 / 分布式 Session（限流、验证码均为内存结构，多实例失效）
- ❌ 单元测试覆盖率（仅 1 个 contextLoads 用例）
- ❌ 全局异常处理 `@ControllerAdvice`（仅 3 处局部 `@ExceptionHandler`）
- ❌ 参数校验 `@Valid`（0 处，全靠手写正则）
- ❌ 事务控制 `@Transactional`（0 处）
- ❌ 服务熔断 / 重试 / 成本控制 / AI 调用监控
- ❌ 视频通话（仅语音）
- ❌ AI 绘画（绘画模块是纯 CRUD + 上传，不接 AI）
- ❌ 服务端消息幂等去重

---

## 九、面试前值得补齐的短板（半天工作量，换 2-3 条硬 bullet）

| 改进项 | 预估耗时 | 补完后可写 |
|---|---|---|
| BCrypt 密码加盐哈希 | 30 min | 「BCrypt 加盐哈希存储密码」 |
| `@ControllerAdvice` 全局异常处理 | 1 h | 「统一异常处理与错误码体系」 |
| `@Valid` + 分组校验 | 1 h | 「JSR-303 参数校验」 |
| JUnit5 + Mockito 补 10 个用例 | 半天 | 「核心链路单元测试」 |
| 上传类型白名单 + 大小限制 | 20 min | 「文件安全校验」 |
| 关键链路 `@Transactional` | 1 h | 「事务一致性保障」 |
| 消息 `clientMsgId` 唯一键幂等 | 2 h | 「消息幂等去重（防重复投递）」 |

> 其中「BCrypt + 全局异常处理 + @Valid + 单元测试」性价比最高，加起来一天内可完成，且都是面试官高频考察点。
