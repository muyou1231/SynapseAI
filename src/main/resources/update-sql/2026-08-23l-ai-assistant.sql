-- moyo AI 助手：全局配置表 + 朋友圈 AI 审核列 + 审核模式增加 AI
-- 执行库：synapse_ai（非 spring_chat_t 旧库）

-- 1) AI 助手全局配置（单例，id=1）：管理员可调试/启用/禁用/设置状态/编辑提示词
CREATE TABLE IF NOT EXISTS ai_assistant (
    id          INT         NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64) NOT NULL DEFAULT 'moyo助手' COMMENT '助手展示名',
    avatar      VARCHAR(512) DEFAULT NULL COMMENT '助手头像',
    enabled     TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '1=启用(用户可对话) 0=禁用(暂停服务)',
    status      VARCHAR(32) NOT NULL DEFAULT 'ONLINE' COMMENT 'ONLINE=在线 / BUSY=忙碌 / OFFLINE=离线 / MAINTENANCE=维护中',
    prompt      MEDIUMTEXT  COMMENT '系统角色设定（助手人设与能力说明）',
    signature   VARCHAR(128) DEFAULT NULL COMMENT '助手个性签名',
    create_time DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ai_assistant (id, name, avatar, enabled, status, signature)
VALUES (1, 'moyo助手', NULL, 1, 'ONLINE', '你的智能伙伴，帮你处理广场、生成文案、答疑解惑～')
ON DUPLICATE KEY UPDATE name = VALUES(name), signature = VALUES(signature);

-- 2) 朋友圈动态增加 AI 审核结果列（AI 审核模式下写入）
DELIMITER $$
CREATE PROCEDURE p_moment_ai_review()
BEGIN
    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION BEGIN END;
    ALTER TABLE moment
        ADD COLUMN ai_review      VARCHAR(16) DEFAULT NULL COMMENT 'AI审核结论：PASS=通过 FAIL=不通过 NULL=未经过AI审核',
        ADD COLUMN ai_suggestion  VARCHAR(512) DEFAULT NULL COMMENT 'AI审核建议/不通过原因（展示给用户与管理员）',
        ADD COLUMN manual_review  TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '0=未申请人工复审 1=已申请人工复审(转管理员)';
END$$
CALL p_moment_ai_review()$$
DROP PROCEDURE p_moment_ai_review$$
DELIMITER ;

-- 3) review_config 注释更新（仅注释，模式值在运行时由 ReviewConfigService 校验，AI 也在白名单内）
-- 说明：mode 取值现为 AUTO / MANUAL / AI
