-- 2026-09-04 创新功能第一批：③隐身阅读 / ④消息改写 / ⑨消息炸弹 / ⑪聊天挖矿+亲密度 / ①时间胶囊
-- 对已存在的运行库执行（库已由旧版 schema.sql 建好，不想重跑会 DROP 全表的脚本）。
-- 若尚未建库 / 允许重建，直接重跑 src/main/resources/sql/schema.sql 即可（已含本次全部变更）。
-- MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，message 表的 ALTER 用存储过程包裹 CONTINUE HANDLER 做幂等；
-- 全新表用 CREATE TABLE IF NOT EXISTS 原生幂等，无需包裹。

DELIMITER $$

CREATE PROCEDURE p_20260904_batch1()
BEGIN
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION BEGIN END;

    -- message 表：④消息改写 + ⑨消息炸弹 所需列
    ALTER TABLE message
        ADD COLUMN edited        TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '消息改写：1=对方已读后修改过（需显示"已编辑"标记）',
        ADD COLUMN edited_time   DATETIME    DEFAULT NULL COMMENT '最后一次修改时间',
        ADD COLUMN edit_hidden   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '1=已消耗积分隐藏"已编辑"标记',
        ADD COLUMN bomb_seconds  INT         DEFAULT NULL COMMENT '消息炸弹：发送时设定的倒计时秒数，NULL=非炸弹消息',
        ADD COLUMN bomb_deadline DATETIME    DEFAULT NULL COMMENT '消息炸弹：对方须在此时间前回复，否则自动引爆',
        ADD COLUMN bomb_status   VARCHAR(16) DEFAULT NULL COMMENT '消息炸弹状态：PENDING/REPLIED/EXPLODED';

    ALTER TABLE message
        ADD KEY idx_message_bomb (bomb_status, bomb_deadline);
END$$

CALL p_20260904_batch1()$$
DROP PROCEDURE p_20260904_batch1$$

DELIMITER ;

-- ③ 隐身阅读：用户通用开关设置表
CREATE TABLE IF NOT EXISTS user_setting (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    ghost_read  TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '隐身阅读：1=开启后自己阅读不会触发已读回执推送给对方',
    update_time DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_setting_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ④ 消息改写：历史版本（保留原始内容，仅发送者本人可查）
CREATE TABLE IF NOT EXISTS message_edit_history (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    message_id  BIGINT      NOT NULL,
    content     TEXT        NOT NULL COMMENT '该次编辑前的历史内容（含最初原文 version=0）',
    version     INT         NOT NULL DEFAULT 0,
    edited_time DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_edit_history_msg (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ⑪ 聊天挖矿：积分余额
CREATE TABLE IF NOT EXISTS user_points (
    id          BIGINT     NOT NULL AUTO_INCREMENT,
    user_id     BIGINT     NOT NULL,
    points      BIGINT     NOT NULL DEFAULT 0,
    level       TINYINT    NOT NULL DEFAULT 1,
    update_time DATETIME   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_points_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ⑪ 聊天挖矿：积分变动流水
CREATE TABLE IF NOT EXISTS points_log (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    change_val  INT         NOT NULL COMMENT '正=获得，负=消耗',
    reason      VARCHAR(32) NOT NULL COMMENT 'CHAT_DURATION/MESSAGE_SEND/HIDE_EDIT_MARK 等',
    ref_id      BIGINT      DEFAULT NULL,
    create_time DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_points_log_user_time (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ⑪ 双人亲密度
CREATE TABLE IF NOT EXISTS intimacy (
    id          BIGINT     NOT NULL AUTO_INCREMENT,
    user_a      BIGINT     NOT NULL COMMENT '较小的 user_id，保证 (a,b) 唯一且不重复存反向对',
    user_b      BIGINT     NOT NULL,
    exp         INT        NOT NULL DEFAULT 0,
    level       TINYINT    NOT NULL DEFAULT 1,
    update_time DATETIME   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_intimacy_pair (user_a, user_b)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ① 时间胶囊
CREATE TABLE IF NOT EXISTS time_capsule (
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
