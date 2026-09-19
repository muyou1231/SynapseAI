-- 类微信聊天工具 建表脚本 (MySQL)
-- 使用说明：
--   1. 先创建数据库： CREATE DATABASE IF NOT EXISTS synapse_ai DEFAULT CHARSET utf8mb4;
--   2. 切换数据库：   USE synapse_ai;
--   3. 执行本脚本：   source schema.sql;   (或直接在客户端全选执行)
-- 说明：脚本开头的 DROP TABLE 会清空旧表并重建（含 account / recall / read / urgent 等最新字段）。
--       若库已建旧表、只想追加新字段/表，请单独执行：
--         ALTER TABLE message ADD COLUMN urgent TINYINT(1) DEFAULT 0;
--         CREATE TABLE urgent_mute ( ... );   -- 见文末 urgent_mute 建表语句
--       （account / recalled / read 列若缺失，也按同样方式 ALTER 或重跑本脚本。）
USE synapse_ai;
SET NAMES utf8mb4;

DROP TABLE IF EXISTS study_plan;
DROP TABLE IF EXISTS urgent_mute;
DROP TABLE IF EXISTS user_privacy;
DROP TABLE IF EXISTS pin;
DROP TABLE IF EXISTS message;
DROP TABLE IF EXISTS group_member;
DROP TABLE IF EXISTS chat_group;
DROP TABLE IF EXISTS friendship;
DROP TABLE IF EXISTS user;

-- 用户表
CREATE TABLE user (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    username    VARCHAR(64)  NOT NULL,
    account     VARCHAR(20)  NOT NULL,
    password    VARCHAR(128) NOT NULL,
    nickname    VARCHAR(64),
    avatar      VARCHAR(512),
    gender      VARCHAR(8)   NULL COMMENT '性别：男/女/保密',
    age         INT          NULL COMMENT '年龄',
    birthday    DATE         NULL COMMENT '生日 yyyy-MM-dd',
    religion    VARCHAR(32)  NULL COMMENT '宗教信仰',
    education   VARCHAR(32)  NULL COMMENT '学历',
    signature   VARCHAR(128) NULL COMMENT 'QQ式个性签名',
    location    VARCHAR(64)  NULL COMMENT '所在地，如 北京/上海',
    hobbies     VARCHAR(128) NULL COMMENT '爱好，逗号分隔',
    email       VARCHAR(128) DEFAULT NULL COMMENT '注册/绑定邮箱，一个邮箱最多 3 个账号，用于邮箱验证与登录',
    online      TINYINT(1)   DEFAULT 0 COMMENT '在线状态：1=在线 0=离线',
    role        VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '角色：USER=普通用户 ADMIN=管理员',
    frozen      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '账号冻结状态：0=正常 1=已冻结',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    UNIQUE KEY uk_user_account (account)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 资料字段可见范围表（field: 资料字段名；visibility: PUBLIC公开/FRIENDS仅好友/PRIVATE仅自己）
CREATE TABLE user_privacy (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    field       VARCHAR(32)  NOT NULL COMMENT '资料字段名：signature/location/hobbies/gender/age/birthday/religion/education/createDays',
    visibility  VARCHAR(16)  NOT NULL COMMENT 'PUBLIC/FRIENDS/PRIVATE',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_privacy (user_id, field),
    KEY idx_user_privacy_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 好友关系表（status: PENDING=申请中, ACCEPTED=已成为好友）
CREATE TABLE friendship (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    friend_id   BIGINT       NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    remark      VARCHAR(64)  DEFAULT NULL COMMENT '好友备注（仅本人视角）',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_friendship (user_id, friend_id),
    KEY idx_friendship_friend (friend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 群表
CREATE TABLE chat_group (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL,
    owner_id    BIGINT       NOT NULL,
    avatar      VARCHAR(512),
    deleted     TINYINT(1)   DEFAULT 0 COMMENT '1=已解散（保留历史，成员可见但不可发消息）',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 群成员表（role: OWNER=群主, MEMBER=成员）
CREATE TABLE group_member (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    group_id    BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    role        VARCHAR(16),
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_member (group_id, user_id),
    KEY idx_group_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 消息表（type: TEXT/IMAGE; target_type: USER/GROUP; read: 0未读 1已读; deleted: 0正常 1已软删除）
CREATE TABLE message (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    sender_id   BIGINT       NOT NULL,
    type        VARCHAR(16)  NOT NULL,
    content     TEXT,
    target_type VARCHAR(16)  NOT NULL,
    target_id   BIGINT       NOT NULL,
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `read`      TINYINT(1)   DEFAULT 0,
    recalled    TINYINT(1)   DEFAULT 0,
    urgent      TINYINT(1)   DEFAULT 0,
    deleted     TINYINT(1)   DEFAULT 0 COMMENT '软删除：0=正常 1=用户已删除',
    edited      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '消息改写：1=对方已读后修改过（需显示“已编辑”标记）',
    edited_time DATETIME     DEFAULT NULL COMMENT '最后一次修改时间',
    edit_hidden TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=已消耗积分隐藏“已编辑”标记',
    bomb_seconds  INT        DEFAULT NULL COMMENT '消息炸弹：发送时设定的倒计时秒数，NULL=非炸弹消息',
    bomb_deadline DATETIME   DEFAULT NULL COMMENT '消息炸弹：对方须在此时间前回复，否则自动引爆',
    bomb_status   VARCHAR(16) DEFAULT NULL COMMENT '消息炸弹状态：PENDING/REPLIED/EXPLODED',
    PRIMARY KEY (id),
    KEY idx_message_target (target_type, target_id, create_time),
    KEY idx_message_bomb (bomb_status, bomb_deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 会话置顶表（用户对好友/群的置顶标记）
CREATE TABLE pin (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    target_type VARCHAR(16)  NOT NULL,
    target_id   BIGINT       NOT NULL,
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pin (user_id, target_type, target_id),
    KEY idx_pin_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 加急消息屏蔽表（user_id 屏蔽了 peer_id 的加急弹窗；单聊按好友、群聊按群成员，均按发送方 peer 处理）
CREATE TABLE urgent_mute (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    peer_id     BIGINT       NOT NULL,
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_urgent_mute (user_id, peer_id),
    KEY idx_urgent_mute_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 朋友圈（动态）表
CREATE TABLE moment (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    content     TEXT,
    images      TEXT         COMMENT 'JSON 数组，存走 8080 代理的图片 URL',
    visibility  VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC=公开 / FRIENDS=仅好友可见 / PRIVATE=仅自己可见 / PARTIAL=部分好友可见',
    status      VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT '动态状态：NORMAL=正常 / PENDING=待审核 / REJECTED=已打回整改',
    reject_reason VARCHAR(255) DEFAULT NULL COMMENT '打回原因（status=REJECTED 时由管理员填写）',
    ai_review   VARCHAR(16)  DEFAULT NULL COMMENT 'AI审核结论：PASS=通过 / FAIL=不通过',
    ai_suggestion VARCHAR(512) DEFAULT NULL COMMENT 'AI审核建议/不通过原因',
    manual_review TINYINT(1) NOT NULL DEFAULT 0 COMMENT '0=未申请人工复审 1=已申请(转管理员复核)',
    allow_list  TEXT         COMMENT 'JSON 数组，部分可见(PARTIAL)模式下的可见好友 id',
    deny_list   TEXT         COMMENT 'JSON 数组，不给谁看的好友 id',
    expire_time DATETIME     NULL COMMENT '可见截止时间；超过后仅作者可见（限时可见）',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_moment_user (user_id),
    KEY idx_moment_create (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 朋友圈评论表（支持文字 + 图片；images 为 JSON 数组，无图时为空数组）
CREATE TABLE moment_comment (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    moment_id   BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    parent_id   BIGINT       DEFAULT NULL COMMENT '父评论 id：回复某条评论时填写，顶级评论为 NULL',
    content     TEXT,
    images      TEXT         COMMENT 'JSON 数组，存走 8080 代理的图片 URL，无图时为空数组',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_comment_moment (moment_id),
    KEY idx_comment_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 朋友圈全局权限设置表（用户级，一人一条）
CREATE TABLE moment_setting (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    visibility  VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC=公开 / FRIENDS=仅好友可见 / PRIVATE=仅自己可见 / PARTIAL=部分好友可见',
    allow_list  TEXT         COMMENT 'JSON 数组，部分可见(PARTIAL)模式下的可见好友 id',
    deny_list   TEXT         COMMENT 'JSON 数组，不给谁看的好友 id',
    expire_time DATETIME     NULL COMMENT '可见截止时间；超过后仅作者可见（对所有动态统一生效）',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_moment_setting_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 消息列表（会话列表）持久化表：记录用户每个会话的显示/隐藏状态
-- 删除会话 = 标记 deleted=1（消息记录保留）；恢复 = 从通讯录/群列表重新打开会话置 deleted=0
CREATE TABLE conversation (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL COMMENT '所属用户',
    target_type VARCHAR(8)  NOT NULL COMMENT 'USER=单聊 / GROUP=群聊',
    target_id   BIGINT      NOT NULL COMMENT '对方用户 id 或群 id',
    deleted     TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '1=用户已删除该会话（消息列表不显示）',
    create_time DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_user_target (user_id, target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 管理员通知
CREATE TABLE notice (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    title           VARCHAR(200) NOT NULL COMMENT '通知标题',
    content         TEXT        COMMENT '通知正文',
    sender_id       BIGINT      NOT NULL COMMENT '发布者（管理员）id',
    target_type     VARCHAR(16) NOT NULL DEFAULT 'ALL' COMMENT 'ALL=全员 / SPECIFIED=指定人',
    target_ids      VARCHAR(1000) DEFAULT NULL COMMENT '指定人时的目标用户 id 列表，逗号分隔',
    duration_minutes INT        DEFAULT NULL COMMENT '有效时长（分钟），NULL=永久有效',
    expire_at       DATETIME    DEFAULT NULL COMMENT '过期时间 = 发布时间 + duration_minutes，NULL=永久',
    category        VARCHAR(16) NOT NULL DEFAULT 'ADMIN' COMMENT 'ADMIN=管理员通知 / COMMENT=朋友圈评论回复通知',
    read_wait_seconds INT       NOT NULL DEFAULT 0 COMMENT '阅读停留时长（秒）：必须大于该时长用户才能点击「我知道了」；0=不限制',
    ref_id          BIGINT      DEFAULT NULL COMMENT '关联业务 id（如朋友圈评论通知关联的 moment id），用于点击通知跳转；ADMIN 类等可为空',
    create_time     DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_notice_target (target_type),
    KEY idx_notice_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 通知已读记录（user_id, notice_id 唯一，已读后不再弹窗）
CREATE TABLE notice_read (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL COMMENT '用户 id',
    notice_id   BIGINT      NOT NULL COMMENT '通知 id',
    read_at     DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notice_read (user_id, notice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 朋友圈审核模式（全局，仅一行 id=1）：AUTO=自动审核(直接发布) / MANUAL=人工审核(待审核 PENDING) / AI=AI审核(不通过转人工复审)
CREATE TABLE review_config (
    id    INT         NOT NULL AUTO_INCREMENT,
    mode  VARCHAR(16) NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO=自动审核 / MANUAL=人工审核 / AI=AI审核',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI 助手配置（每行一个 AI 助手，user_id 关联 user 表 role=AI 账户）：管理员可调试/启用/禁用/设置状态/编辑人设
CREATE TABLE ai_assistant (
    id          INT         NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      DEFAULT NULL COMMENT '关联的 user 表 AI 账户 id（role=AI）',
    name        VARCHAR(64) NOT NULL DEFAULT 'moyo助手',
    avatar      VARCHAR(512) DEFAULT NULL,
    enabled     TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '1=启用 0=禁用',
    status      VARCHAR(32) NOT NULL DEFAULT 'ONLINE' COMMENT 'ONLINE/BUSY/OFFLINE/MAINTENANCE',
    is_default  TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '1=默认助手：新用户自动添加，删除后自动切换到下一个',
    prompt      MEDIUMTEXT,
    signature   VARCHAR(128) DEFAULT NULL,
    create_time DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_assistant_user (user_id),
    KEY idx_ai_assistant_default (is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 朋友圈点赞表（用户对动态点赞；唯一键 (moment_id, user_id) 防重复点赞）
CREATE TABLE moment_like (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    moment_id   BIGINT       NOT NULL COMMENT '被点赞的动态 id',
    user_id     BIGINT       NOT NULL COMMENT '点赞用户 id',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_moment_like (moment_id, user_id),
    KEY idx_moment_like_moment (moment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 学习空间：用户保存的「我的学习计划」（AI 生成或手动编辑后保存）
CREATE TABLE study_plan (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL COMMENT '所属用户',
    title            VARCHAR(128) NOT NULL COMMENT '计划标题',
    content          TEXT         COMMENT '计划正文（AI 生成的周计划/微任务，纯文本或结构化 JSON）',
    checkin_minutes  INT          NOT NULL DEFAULT 0 COMMENT '累计打卡时长（分钟）',
    pomodoro_minutes INT          NOT NULL DEFAULT 0 COMMENT '累计番茄钟时长（分钟）',
    checkin_days     VARCHAR(512) NOT NULL DEFAULT '' COMMENT '打卡日期列表，逗号分隔 yyyy-MM-dd',
    last_checkin     DATE         DEFAULT NULL COMMENT '最后打卡日期',
    create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_study_plan_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 学习空间：每日学习统计（番茄钟/打卡按天汇总，用于总览与趋势）
CREATE TABLE study_stats (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL COMMENT '所属用户',
    stat_date        DATE         NOT NULL COMMENT '统计日期 yyyy-MM-dd',
    pomodoro_minutes INT          NOT NULL DEFAULT 0 COMMENT '当日番茄钟总时长（分钟）',
    checkin_minutes  INT          NOT NULL DEFAULT 0 COMMENT '当日打卡总时长（分钟）',
    checkin_count    INT          NOT NULL DEFAULT 0 COMMENT '当日打卡次数',
    create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_study_stats_user_date (user_id, stat_date),
    KEY idx_study_stats_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 学习空间：AI 对话历史（后端持久化，刷新/换设备可靠恢复，流式实时落库中途不丢）
-- 每轮对话 = 一条用户消息 + 一条 AI 消息（seq 相邻），按 (user_id, seq) 顺序回放。
CREATE TABLE study_chat (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL COMMENT '所属用户',
    role        VARCHAR(8)   NOT NULL COMMENT 'user=用户消息 / ai=AI 回复',
    mode        VARCHAR(16)  NOT NULL DEFAULT 'chat' COMMENT '对话模式：chat/plan/quiz/summarize',
    thread      VARCHAR(16)  NOT NULL DEFAULT 'chat' COMMENT '子线：chat/plan/quiz/summarize，同一会话下四类各自独立记录与记忆',
    session_id  BIGINT       NOT NULL DEFAULT 0 COMMENT '所属 AI 会话 id（关联 study_ai_session）；0 表示改造前的旧数据',
    content     MEDIUMTEXT   COMMENT '消息内容（流式过程中实时更新 AI 回复）',
    seq         INT          NOT NULL DEFAULT 0 COMMENT '同一会话内、同一子线下的顺序号，从 1 递增，用于回放排序',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_study_chat_user (user_id, thread, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 程序空间：绘画作品管理（用户上传/填URL + 描述，可创建/删除）
CREATE TABLE painting (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL COMMENT '所属用户',
    title       VARCHAR(128) NOT NULL COMMENT '作品标题',
    description VARCHAR(512) DEFAULT '' COMMENT '作品描述',
    image_url   VARCHAR(512) NOT NULL COMMENT '图片地址（MinIO 代理 URL 或外部链接）',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_painting_user (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===================== 2026-09-04 新增：创新功能批次一 =====================

-- 用户通用开关设置（隐身阅读等开关型配置聚合，避免表膜胀）
CREATE TABLE user_setting (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    ghost_read  TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '隐身阅读：1=开启后自己阅读不会触发已读回执推送给对方',
    update_time DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_setting_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 消息改写历史（保留每一次修改前的内容，仅发送者本人可查）
CREATE TABLE message_edit_history (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    message_id  BIGINT      NOT NULL,
    content     TEXT        NOT NULL COMMENT '该次修改前的历史内容（含最初原文 version=0）',
    version     INT         NOT NULL DEFAULT 0,
    edited_time DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_edit_history_msg (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 聊天挖矿：用户积分余额（一人一行）
CREATE TABLE user_points (
    id          BIGINT     NOT NULL AUTO_INCREMENT,
    user_id     BIGINT     NOT NULL,
    points      BIGINT     NOT NULL DEFAULT 0,
    level       TINYINT    NOT NULL DEFAULT 1,
    update_time DATETIME   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_points_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 聊天挖矿：积分变动流水（用于明细展示 + 每日限额统计）
CREATE TABLE points_log (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    change_val  INT         NOT NULL COMMENT '正=获得，负=消耗',
    reason      VARCHAR(32) NOT NULL COMMENT 'CHAT_DURATION/MESSAGE_SEND/HIDE_EDIT_MARK 等',
    ref_id      BIGINT      DEFAULT NULL,
    create_time DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_points_log_user_time (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 聊天挖矿：双人亲密度（user_a 总是较小的 user_id，保证 (a,b) 唯一且不存反向对）
CREATE TABLE intimacy (
    id          BIGINT     NOT NULL AUTO_INCREMENT,
    user_a      BIGINT     NOT NULL,
    user_b      BIGINT     NOT NULL,
    exp         INT        NOT NULL DEFAULT 0,
    level       TINYINT    NOT NULL DEFAULT 1,
    update_time DATETIME   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_intimacy_pair (user_a, user_b)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 时间胶囊：写给未来的信（可写给自己或好友，指定日期解锁）
CREATE TABLE time_capsule (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    sender_id     BIGINT       NOT NULL,
    receiver_id   BIGINT       DEFAULT NULL COMMENT 'NULL=写给未来的自己',
    content_enc   TEXT         NOT NULL COMMENT 'AES-256-GCM 加密后的正文（密文 Base64）',
    open_time     DATETIME     NOT NULL COMMENT '精确到日的开启日期',
    status        VARCHAR(16)  NOT NULL DEFAULT 'SEALED' COMMENT 'SEALED=封存中 / UNLOCKED=已解锁',
    unlocked_time DATETIME     DEFAULT NULL,
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_capsule_open (status, open_time),
    KEY idx_capsule_receiver (receiver_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
