-- 2026-08-23a：study_chat 增加 thread 列，支持「同一会话内 chat/plan/quiz/summarize 四条独立记录线」
-- 兼容 MySQL 5.7（不支持 DROP/CREATE INDEX IF EXISTS），用存储过程包裹 ALTER 实现幂等。
USE synapse_ai;
SET NAMES utf8mb4;

DELIMITER $$

CREATE PROCEDURE p_study_chat_thread()
BEGIN
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION BEGIN END;

    -- 1) 增加 thread 列（缺则加，已存在则忽略）
    ALTER TABLE study_chat ADD COLUMN thread VARCHAR(16) NOT NULL DEFAULT 'chat'
        COMMENT '子线：chat/plan/quiz/summarize，同一会话下四类各自独立记录与记忆';

    -- 2) 重建索引：原 (user_id, seq) -> 按 thread 隔离的 (user_id, thread, seq)
    ALTER TABLE study_chat DROP INDEX idx_study_chat_user;
    ALTER TABLE study_chat ADD INDEX idx_study_chat_user (user_id, thread, seq);
END$$

DELIMITER ;

CALL p_study_chat_thread();
DROP PROCEDURE p_study_chat_thread;
