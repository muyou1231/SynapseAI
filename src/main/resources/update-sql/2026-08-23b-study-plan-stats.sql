-- 学习空间：计划表加学习记录字段 + 新建每日学习统计表
-- 运行库：synapse_ai（非 spring_chat_t）
-- MySQL 5.7 不支持 IF EXISTS / DROP INDEX IF EXISTS，用存储过程吞异常做幂等。

USE synapse_ai;
SET NAMES utf8mb4;

DELIMITER $$

-- 1) study_plan 增加学习过程记录字段
CREATE PROCEDURE p_plan_stats()
BEGIN
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION BEGIN END;

    ALTER TABLE study_plan ADD COLUMN checkin_minutes INT NOT NULL DEFAULT 0 COMMENT '累计打卡时长（分钟）';
    ALTER TABLE study_plan ADD COLUMN pomodoro_minutes INT NOT NULL DEFAULT 0 COMMENT '累计番茄钟时长（分钟）';
    ALTER TABLE study_plan ADD COLUMN checkin_days VARCHAR(512) NOT NULL DEFAULT '' COMMENT '打卡日期列表，逗号分隔 yyyy-MM-dd';
    ALTER TABLE study_plan ADD COLUMN last_checkin DATE DEFAULT NULL COMMENT '最后打卡日期';
END$$

-- 2) 新建每日学习统计表（番茄钟/打卡按天汇总，用于总览与趋势）
CREATE PROCEDURE p_stats_table()
BEGIN
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION BEGIN END;
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
END$$

DELIMITER ;

CALL p_plan_stats();
CALL p_stats_table();
DROP PROCEDURE p_plan_stats;
DROP PROCEDURE p_stats_table;
