-- 学习空间：我的学习计划表（2026-07-26 新增）
-- 对运行库 synapse_ai 执行；CREATE TABLE IF NOT EXISTS 幂等，可重复执行。
CREATE TABLE IF NOT EXISTS study_plan (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL COMMENT '所属用户',
    title       VARCHAR(128) NOT NULL COMMENT '计划标题',
    content     TEXT         COMMENT '计划正文（AI 生成的周计划/微任务，纯文本）',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_study_plan_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
