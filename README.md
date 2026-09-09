# MOYO-chat（有趣的网页聊天工具）

一个基于 Spring Boot 3 的**有趣的网页聊天工具**，包含用户体系、好友/群聊、实时消息、AI 智能助手、学习空间（计划/番茄钟/AI 对话）、朋友圈（动态/审核）、绘画、通知与管理后台等完整功能。前端为原生 HTML/CSS/JS（微信风格双栏布局），后端使用 REST + WebSocket(STOMP) 双通道。

> 包名：`com.moyo.springchat`
> 默认端口：`8080`（可用 `--server.port=` 覆盖）

---

## 一、技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 后端框架 | Spring Boot 3.0.2 / Java 17 | 主框架 |
| 持久化 | MyBatis-Plus 3.5.7（`mybatis-plus-spring-boot3-starter`） | 显式 Mapper（`@Insert/@Select/...`），不继承 `BaseMapper` |
| 数据库 | MySQL 5.7 | 账号 `root/hsp`，库名 `spring_chat` |
| 缓存/在线状态 | Redis | 在线状态、验证码、通知等 |
| 对象存储 | MinIO | 图片/语音/绘画文件，桶 `spring-chat` |
| 邮件 | spring-boot-starter-mail | QQ SMTP（验证码、通知邮件） |
| 实时通信 | Spring WebSocket + STOMP（SockJS） | 单聊/群聊/通知/通话信令实时推送 |
| AI 能力 | 阿里云百炼 / 通义（`openai-java` 官方 SDK） | 兼容 OpenAI 协议的 `/chat/completions`，支持流式 SSE |
| 前端 | 原生 HTML + CSS + JS（SockJS + STOMP.js via CDN） | 放 `src/main/resources/static`，无构建步骤 |
| 鉴权 | 轻量 Token（内存 `TokenStore` + `X-Token` 请求头） | 不引入 Spring Security |

> **与最初设计稿的差异**：项目早期规划使用 H2 + JPA，实际迭代后改为 **MySQL 5.7 + MyBatis-Plus**，并大幅扩展了 AI 助手、学习空间、朋友圈审核、管理后台、语音/通话等能力。

---

## 二、核心功能

### 1. 用户与账号
- 注册 / 登录（用户名 + 密码），返回 `X-Token`
- 邮箱验证码登录准备（QQ SMTP，配置见下文）
- 修改密码 / 重置密码 / 绑定邮箱 / 修改资料（昵称、头像）
- 在线状态上报（`/user/online`），支持「切换账号」
- 好友备注、拉黑 / 解除拉黑

### 2. 好友体系
- 按用户名/昵称模糊搜索用户
- 发起 / 同意 / 拒绝好友申请
- 好友列表、删除好友（保留聊天记录）
- 好友备注名

### 3. 单聊与群聊
- 文本 / 图片 / **语音**（`<audio>` 播放）/ **通话**（WebRTC P2P 信令中继）四类消息
- 实时收发（WebSocket），历史消息分页加载
- 消息**撤回**、**已读**回执、**置顶（pin）**、**正在输入**指示
- 会话列表持久化（`conversation` 表，删除=标记保留，可恢复）
- 聊天记录搜索（仅 `TEXT`，严格越权校验）
- 建群 / 邀请 / 移除成员 / 退群 / 解散群；群成员管理

### 4. AI 智能助手（好友形态）
- 管理员可创建「AI 助手」账号（独立 `user` + `ai_assistant` 配置，含状态 ONLINE/OFFLINE/MAINTENANCE/BUSY）
- 用户像加好友一样与 AI 对话；助手状态（在线/离线等）实时同步到用户端
- 助手可配置系统提示词、模型、是否流式等

### 5. 学习空间
- **学习计划**：结构化 JSON（标题 + 步骤清单 + 备注），兼容旧纯文本；支持打卡、番茄钟记录
- **学习统计**：每日汇总（`study_stats`，番茄钟时长、打卡时长/次数）
- **AI 对话（四类独立线程）**：`chat`（自由对话）/ `plan`（生成计划）/ `quiz`（出题测验）/ `summarize`（总结）。每类独立会话线与记忆，支持多会话管理与清空
- 绘画：AI 生成图片记录（标题 / 描述 / 图片 URL）

### 6. 朋友圈（「多多的家园」）
- 发布图文动态，三层权限控制：
  1. 全局可见范围（`moment_setting.visibility`：INVISIBLE / 三天 / 一月 / 半年）
  2. 逐条可见性（PUBLIC / FRIENDS / PRIVATE / PARTIAL + 拒绝名单 `deny_list`）
  3. `deny_list` 最高优先
- 评论、点赞（含通知）
- **内容审核**：全局模式 `review_config`（AUTO / MANUAL）。管理员可打回（显示原因）、通过、恢复；MANUAL 模式下提交后实时同步用户端状态

### 7. 通知系统
- `notice` + `notice_read`；分类 ADMIN / COMMENT
- 铃铛红点、未读列表、已读标记

### 8. 管理后台（`/admin`）
- 独立 `admin.html` + `js/admin-app.js`，仅 ADMIN 角色可进入（`window.open('admin.html')`）
- 用户管理（冻结 / 解冻 / 强制下线）、全站统计
- 消息 / 朋友圈 / 群聊查看与处置
- **内容审核**：朋友圈列表、打回 / 通过 / 恢复 / 删除；审核模式切换
- **AI 助手管理**：列表 / 生成账号 / 新增 / 编辑 / 删除
- 系统通知发布

---

## 三、项目结构

```text
spring-chat/
├── pom.xml
├── README.md
├── src/main/java/com/moyo/springchat/
│   ├── SpringChatApplication.java        # 启动类 + @MapperScan
│   ├── config/                           # WebSocket / Minio / Redis / Mail / 拦截器等
│   ├── controller/                       # 16 个 REST + WebSocket 控制器
│   │   ├── AuthController          # 验证码/改密/绑邮箱
│   │   ├── UserController          # 用户/搜索/资料/在线
│   │   ├── FriendController        # 好友申请/列表/备注/拉黑
│   │   ├── MessageController       # 历史/搜索/撤回/已读/pin/置顶/会话
│   │   ├── ChatController          # WebSocket /app/chat.send 等消息信令
│   │   ├── GroupController         # 群聊
│   │   ├── CallController          # /app/call.signal 通话信令中继
│   │   ├── MomentController        # 朋友圈动态/评论/点赞/权限/审核
│   │   ├── NoticeController        # 通知
│   │   ├── AiController            # AI chat/plan/quiz/summarize/stream
│   │   ├── AiAssistantService      # 助手状态推送
│   │   ├── StudyController         # 学习计划/番茄钟/统计
│   │   ├── StudyChatController     # 学习空间 AI 对话（多会话/多线）
│   │   ├── PaintingController      # 绘画
│   │   ├── FileController          # 文件上传/代理下载
│   │   ├── AdminController         # 管理后台 API
│   │   └── AdminPageController     # /admin 页面
│   ├── service/                     # 业务层（14 个 Service）
│   ├── mapper/                      # 显式 SQL Mapper（不继承 BaseMapper）
│   ├── entity/                      # 实体（map-underscore-to-camel-case）
│   └── util/ / interceptor/ / ws/   # 工具、Token 拦截器、WS 处理器
└── src/main/resources/
    ├── application.yml               # 主配置（含 datasource/redis/mail/minio/app）
    ├── application-local.yml         # 本地密钥（AI Key 等，**已 git-ignore，不提交**）
    ├── sql/
    │   ├── schema.sql                # 全量建表（DROP + 重建，仅本地初始化用）
    │   └── update-sql/               # 增量变更（ALTER TABLE，幂等迁移）
    └── static/
        ├── index.html                # 登录/注册 + 主聊天页（SPA 单页切换）
        ├── admin.html                # 管理后台页
        ├── css/
        │   ├── style.css             # 微信风格基础样式（组件/布局/响应式）
        │   └── style-premium.css     # 视觉与交互精装升级层：设计令牌/动效/渐变按钮/玻璃拟态登录页等，
        │                             # 覆盖 style.css 同名选择器做整体打磨，不改动 style.css 本身，移除对应 <link> 即可整体回退
        └── js/                       # api / ws / app / chat / friend / group /
                                      # moment / study / painting / call / admin-app /
                                      # ui-polish（跨页面弹窗关闭动效等通用交互增强）等
```

---

## 四、环境准备

| 组件 | 版本/要求 | 说明 |
|---|---|---|
| JDK | 17 | `java.version=17` |
| Maven | 3.x | 构建工具 |
| MySQL | 5.7 | 建库 `spring_chat`，账号 `root/hsp` |
| Redis | 任意稳定版 | 默认 `localhost:6379`（配置中带密码示例，本地可空） |
| MinIO | 任意稳定版 | 桶 `spring-chat`，默认 `http://localhost:9000` |
| 阿里云百炼 Key | 可选（启用 AI 时需要） | 填 `application-local.yml` 的 `app.bailian.api-key` |

> ⚠️ MySQL 5.7 **不支持** `DROP/CREATE INDEX/COLUMN IF EXISTS`，增量迁移统一用「存储过程 + CONTINUE HANDLER 吞异常」实现幂等。

---

## 五、配置说明

主配置 `application.yml`（已提交，不含密钥）：

```yaml
server:
  port: 8080
spring:
  config:
    import: optional:classpath:application-local.yml   # 本地密钥覆盖
  datasource:
    url: jdbc:mysql://localhost:3306/spring_chat?...
    username: root
    password: hsp
  data:
    redis:
      host: localhost
      port: 6379
  mail:                                   # QQ 邮箱 SMTP（验证码登录准备）
    host: smtp.qq.com
    port: 465
    username: ${QQ_MAIL_ACCOUNT}
    password: ${QQ_MAIL_PASSWORD}
minio:
  accessKey: admin
  secretKey: ${COMMON_PASSWORD}
  bucket: spring-chat
  endpoint: http://localhost:9000
app:
  admin:
    username: admin
    password: admin123        # 生产请修改
  verify-code:
    expire-seconds: 300
    length: 6
    resend-seconds: 60
```

本地密钥 `application-local.yml`（**已被 `.gitignore` 忽略，绝不提交**）：

```yaml
app:
  bailian:
    api-key: sk-xxxx            # 阿里云百炼/通义 API Key（真实值，保密）
    model: qwen3.7-plus
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    # 若使用百炼 MaaS 工作区端点，可改为
    # https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
```

> `.gitignore` 已排除 `.workbuddy/`（含本地依赖与日志）与 `application-local.yml`，避免密钥与垃圾文件入库。

---

## 六、数据库初始化

- **全量建表（本地/测试）**：执行 `src/main/resources/sql/schema.sql`（先 DROP 再建表，会清空数据，**不要**直接对线上库跑）。
- **增量迁移（生产/升级）**：将变更放进 `src/main/resources/update-sql/`，按 `YYYY-MM-DDx-描述.sql` 命名，使用 `ALTER TABLE` + 幂等存储过程，避免覆盖线上数据。

---

## 七、构建与运行

```bash
# 1. 编译
mvn clean compile

# 2. 运行（默认 8080）
mvn spring-boot:run
# 或指定端口
java -jar target/spring-chat-*.jar --server.port=8091

# 3. 访问
#    主应用：  http://localhost:8080/
#    管理后台：http://localhost:8080/admin
```

> **前端缓存**：静态资源来自 `target/classes/static`。修改 `src/main/resources/static/js/*.js` 后需 `mvn compile` 重新同步；`index.html` 中脚本带 `?v=YYYYMMDDxx` 缓存 bust，改动后需 bump 版本号并硬刷新（Ctrl+Shift+R）。

---

## 八、REST API 总览

所有业务接口（除登录/注册/验证码外）需在请求头携带 `X-Token`。以下为各 Controller 端点（前缀 `/api`）：

### 用户与鉴权 `UserController` / `AuthController`
```
POST /user/register           注册
POST /user/login              登录 -> token
POST /user/logout             退出
POST /user/online             上报在线状态
POST /user/switch             切换账号
POST /user/profile            修改资料
GET  /user/me                 当前用户
GET  /user/search?kw=         搜索用户
GET  /user/search/assistant   搜索 AI 助手
GET  /user/{id}/profile       他人资料
POST /auth/send-code          发送验证码
POST /auth/verify-code        校验验证码
POST /auth/change-password    修改密码
POST /auth/reset-password     重置密码
POST /auth/bind-email         绑定邮箱
GET  /auth/code-config        验证码配置
```

### 好友 `FriendController`
```
POST /friend/apply            发起申请
POST /friend/accept           同意
POST /friend/reject           拒绝
POST /friend/block            拉黑
POST /friend/unblock          解除拉黑
POST /friend/remark           备注
GET  /friend/list             好友列表
GET  /friend/requests         待处理申请
GET  /friend/search?kw=       搜索
```

### 消息 `MessageController`
```
GET  /message/history         历史消息
GET  /message/search          聊天记录搜索
GET  /message/sync            增量同步
GET  /message/conversation/list  会话列表
GET  /message/urgent-mute/list   免打扰列表
DELETE /message/{id}          删除单条
DELETE /message/conversation  删除会话
POST /message/conversation/restore  恢复会话
POST /message/session         新建私人会话/笔记
DELETE /message/session/{id}  删除会话
POST /message/pin             置顶
POST /message/recall          撤回
POST /message/read            标记已读
POST /message/typing          正在输入
POST /message/urgent-mute     设置免打扰
```

### 群聊 `GroupController`
```
POST /group/create            建群
POST /group/invite            邀请成员
POST /group/remove            移除成员
POST /group/quit              退群
POST /group/dissolve          解散群
GET  /group/list              我的群
GET  /group/members?id=       群成员
```

### 朋友圈 `MomentController`
```
POST /moment/publish           发布动态
POST /moment/{id}/manual-review   申请人工复审
POST /moment/{id}/comment     评论
POST /moment/{id}/like        点赞
POST /moment/setting          设置全局可见范围
GET  /moment/feed             广场/好友动态
GET  /moment/{id}             详情
GET  /moment/mine             我的动态
GET  /moment/user/{userId}    指定用户动态
GET  /moment/{id}/comments    评论列表
GET  /moment/setting          我的可见设置
DELETE /moment/{id}           删除动态
DELETE /moment/comment/{id}   删除评论
PUT  /moment/{id}             编辑动态
PUT  /moment/{id}/permission  修改单条可见性
```

### 通知 `NoticeController`
```
GET  /notice/list             通知列表
GET  /notice/pending          待处理
POST /notice/read             标记已读
```

### AI 助手 `AiController`
```
POST /ai/chat                 对话（非流式）
POST /ai/plan                 生成学习计划
POST /ai/quiz                 出题测验
POST /ai/summarize            总结
POST /ai/assistant/copy       复制助手会话
POST /ai/stream               流式输出（SSE，text/event-stream）
```

### 学习空间 `StudyController` / `StudyChatController`
```
POST /study/plan              新建计划
GET  /study/plans             计划列表
GET  /study/plan/{id}         计划详情
PUT  /study/plan/{id}         更新计划
DELETE /study/plan/{id}       删除计划
POST /study/plan/{id}/checkin 打卡
POST /study/pomodoro/report   上报番茄钟
GET  /study/stats             学习统计

GET  /study/chat/sessions     我的 AI 会话列表
POST /study/chat/session      新建会话
DELETE /study/chat/session/{id}  删除会话（含全部线程消息）
GET  /study/chat/list         会话全量消息（按线程分桶）
POST /study/chat/send         发送消息（指定 mode/thread）
POST /study/chat/update       更新消息
DELETE /study/chat/{id}       删除单条
DELETE /study/chat/clear      清空（可按 thread）
```

### 绘画 `PaintingController`
```
GET  /painting/list           列表
POST /painting/create         创建
DELETE /painting/{id}         删除
```

### 文件 `FileController`
```
POST /file/upload             上传（MinIO，支持 prefix=voice/chat 等）
GET  /files/**                代理下载
```

### 管理后台 `AdminController` / `AdminPageController`
```
GET  /admin                                管理页面
GET  /admin/check                          鉴权检查
GET  /admin/users                          用户列表
POST /admin/users/{id}/freeze              冻结
POST /admin/users/{id}/unfreeze            解冻
POST /admin/users/{id}/offline             强制下线
GET  /admin/stats                          全站统计
GET  /admin/messages                       消息列表
GET  /admin/moments                        动态列表
GET  /admin/groups                         群列表
GET  /admin/groups/{id}/members            群成员
POST /admin/notice                         发布系统通知
GET  /admin/notices                        通知列表
DELETE /admin/notice/{id}                  删除通知
GET  /admin/moments/{id}/comments          动态评论
POST /admin/moments/{id}/reject            打回（含原因）
POST /admin/moments/{id}/approve           通过
POST /admin/moments/{id}/restore           恢复
DELETE /admin/moments/{id}                 删除动态
GET  /admin/review                         待审列表
GET  /admin/review/mode                    审核模式
POST /admin/review/mode                    切换审核模式
GET  /admin/assistants                     AI 助手列表
POST /admin/generate-account               生成助手账号
POST /admin/assistants                     新增助手
PUT  /admin/assistants/{id}                编辑助手
DELETE /admin/assistants/{id}              删除助手
```

---

## 八·五、创新功能（实验性）

一批「趣味增强」功能，入口集中在程序空间（顶部「程序空间」Tab）。

### ① 时间胶囊
写给未来的自己或指定好友，正文以 **AES-256-GCM** 加密封存，到期前任何人都看不到内容，到点后自动解锁并推送通知。
- 创建：`POST /api/capsule/create` `{ receiverId?(好友id，空=写给自己), content, openDate(yyyy-MM-dd，须晚于今天) }`
- 列表（我写的 + 写给我的，未解锁不返回正文）：`GET /api/capsule/list`
- 详情：`GET /api/capsule/{id}`
- 前端：程序空间 → 时间胶囊，可新建并在解锁后查看正文。

### ③ 隐身阅读
开启后自己阅读消息**不会**向对方触发「已读」回执（仅影响阅读回执，消息仍正常接收）。
- 开关：`POST /api/setting/ghost-read` `{ enabled }`；查看：`GET /api/setting`

### ⑨ 消息炸弹
仅单聊文本消息可用。发送时装填炸弹（默认 30 秒倒计时），对方须在倒计时内**回复**才能拆弹；超时未回复则自动引爆，原消息内容被替换为占位文案，双方收到 `BOMB_EXPLODED` 推送。
- 前端：聊天输入框的 💣 按钮装填/取消，下一条文本消息即挂上炸弹。

### ⑪ 聊天挖矿 + 亲密度
- 发消息得积分（每日有上限），积分可用于后续扩展消耗场景。
- 单聊互动累计双人亲密度（等级 + exp）。
- 我的积分/等级：`GET /api/points/me`；积分流水：`GET /api/points/log`；与某好友亲密度：`GET /api/points/intimacy?peerId=`

---

## 九、WebSocket 实时通信（STOMP）

```
连接端点：  ws://<host>:<port>/ws        （SockJS 回退）
应用前缀：  /app
广播前缀：  /topic

客户端发送：
  /app/chat.send        发送单聊/群聊消息
  /app/call.signal      通话信令（服务端原样转发给目标用户）
  /app/typing.content   正在输入（内容侧）
  /app/typing.view      对方正在查看

客户端订阅：
  /topic/user/{userId}      单播：私聊消息、通知、AI 状态变更、
                             朋友圈审核结果、通话信令、已读/输入等
  /topic/group/{groupId}    群聊消息广播
```

鉴权：`AuthChannelInterceptor` 在 CONNECT 时校验 `X-Token` 并设置 Principal，点对点统一走 `/topic/user/{uid}` 单播。前端 `ws.js` 含指数退避重连、断线横幅、可见性/在线事件立即重连等移动端友好逻辑。

---

## 十、AI 能力说明

- **接入方式**：官方 `openai-java` SDK（`com.openai:openai-java:2.6.0`），封装于 `service/AiService.java`，对外 `chat(system,user)` / `chat(user)`。
- **端点与模型**：兼容 OpenAI 协议，`base-url` 默认 `https://dashscope.aliyuncs.com/compatible-mode/v1`，模型 `qwen3.7-plus`；鉴权 `Bearer {api-key}`（SDK 自动处理）。切换 MaaS 工作区端点改 `app.bailian.base-url` 即可。
- **流式输出**：`POST /api/ai/stream` 返回 `SseEmitter`，按 `mode` 复用提示词模板，逐 token 推送（前端 `api.js aiStream` 用 `fetch` + `ReadableStream` 解析 SSE 做打字机效果）。
- **学习空间多线**：`chat/plan/quiz/summarize` 四类独立对话线程，每条线程各自维护会话顺序与记忆，互不串线。
- **超时**：推理模型响应慢，SDK 统一设 `Duration.ofMinutes(5)` 覆盖。

---

## 十一、默认账号

| 角色 | 用户名 | 密码 | 说明 |
|---|---|---|---|
| 管理员 | `admin` | `admin123` | 管理后台 `/admin` 登录（生产请改密） |
| 测试用户 | 见数据库 `user` 表 | — | 注册后直接使用；示例账号如 `2314780778` / `6530823031` 等 |

> `AdminBootstrap` 会在启动时确保 `admin/admin123` 存在。

---

## 十二、开发约定

- **Mapper 显式化**：所有 Mapper 位于 `com.moyo.springchat.mapper`，**不继承 `BaseMapper`**；CRUD 用显式 `@Insert/@Select/@Update/@Delete` + `@Options(useGeneratedKeys=true, keyProperty="id")`。
- **SQL 分类管理**：全量建表仅放 `schema.sql`（DROP+重建）；增量变更放 `update-sql/` 用 `ALTER TABLE`，不让单个脚本覆盖线上数据。
- **前端版本缓存**：`index.html` 脚本带 `?v=YYYYMMDDxx`，改动后 bump 版本号 + 硬刷；改 `static/js` 后必须 `mvn compile` 同步到 `target/classes`。
- **样式分层**：`style.css` 为基础布局/组件样式，`style-premium.css` 为后置升级层（加载顺序在其之后，用相同选择器覆盖/叠加阴影、动效、渐变等视觉打磨）；新组件优先在 `style.css` 定义基础样式，视觉精装改动放到 `style-premium.css`，便于整体回退。`js/ui-polish.js` 需作为页面第一个 `<script>` 加载（早于 app.js / admin-app.js），提供弹层关闭动效等跨页面通用交互能力。
- **密钥管理**：任何密钥（AI Key、邮箱授权码、MinIO 密码）只放 `application-local.yml`，该文件已 git-ignore，**绝不提交**。
- **消息类型**：`TEXT / IMAGE / VOICE / CALL`；图片/语音复用 `content` 存 MinIO 代理 URL；`CALL` content 存 `{r:结果码, d:秒}`，搜索不收录。
- **异步任务**：需要后台异步执行的任务（AI 回复/审核等）统一复用线程池（`Executors.newCachedThreadPool()`），不要为每次调用 `new Thread(...).start()`。

---

## 十三、常见问题

- **AI 对话报错 401/无响应**：检查 `application-local.yml` 的 `app.bailian.api-key` 是否正确，以及 `base-url` 是否可达。
- **WebSocket 连不上**：确认后端已启动 `/ws` 端点；前端通过 SockJS 连接，需同源或正确配置跨域。
- **前端改了不生效**：`mvn compile` 同步静态资源 + 硬刷新（Ctrl+Shift+R）+ 检查 `index.html` 中 `?v=` 版本号。
- **MySQL 5.7 迁移报错**：勿使用 `IF EXISTS` 类语法；增量脚本改用幂等存储过程包裹。
- **密钥泄漏风险**：`application-local.yml` 已忽略；若曾误提交，立即轮换密钥并清理历史。
