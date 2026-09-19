-- AI 助手独立类型改造：将 moyo 助手从普通 USER 升级为 role=AI 的独立账户，
-- ai_assistant 配置表新增 user_id 关联，并回填默认助手(id=1)的 user_id。
-- 执行库：synapse_ai（非 spring_chat_t 旧库）
-- 说明：role=AI 的账户不可被普通用户注册/搜索/加好友/改资料，仅管理员在管理端增改。

-- 1) ai_assistant 增加 user_id 列（关联 user 表 role=AI 账户）
DELIMITER $$
CREATE PROCEDURE p_ai_user_id()
BEGIN
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION BEGIN END;
    ALTER TABLE ai_assistant ADD COLUMN user_id BIGINT DEFAULT NULL COMMENT '关联的 user 表 AI 账户 id（role=AI）';
    ALTER TABLE ai_assistant ADD KEY idx_ai_assistant_user (user_id);
END$$
CALL p_ai_user_id()$$
DROP PROCEDURE p_ai_user_id$$
DELIMITER ;

-- 2) 将默认助手配置(id=1)的 user_id 关联到底层 moyo_assistant 账户，
--    并把该账户 role 从 USER 升级为 AI（若已是 AI 则不变）。
UPDATE ai_assistant a
SET a.user_id = (SELECT u.id FROM user u WHERE u.username = 'moyo_assistant' LIMIT 1)
WHERE a.id = 1 AND a.user_id IS NULL;

UPDATE user SET role = 'AI' WHERE username = 'moyo_assistant' AND (role IS NULL OR role <> 'AI');
