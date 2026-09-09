-- 2026-09-09 移除「消息改写」功能遗留的库结构
-- MySQL 5.7 不支持 DROP COLUMN IF EXISTS / DROP TABLE IF EXISTS，
-- 用存储过程包裹 CONTINUE HANDLER 实现幂等（列不存在时静默跳过）。

DROP PROCEDURE IF EXISTS drop_message_edit_cols;
DELIMITER $$
CREATE PROCEDURE drop_message_edit_cols()
BEGIN
    -- 列已不存在时忽略错误，继续后续语句
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION BEGIN END;

    ALTER TABLE message DROP COLUMN edited;
    ALTER TABLE message DROP COLUMN edited_time;
    ALTER TABLE message DROP COLUMN edit_hidden;
END$$
DELIMITER ;

CALL drop_message_edit_cols();
DROP PROCEDURE IF EXISTS drop_message_edit_cols;

DROP TABLE IF EXISTS message_edit_history;
