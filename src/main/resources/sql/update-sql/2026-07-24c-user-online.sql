-- 增量变更：user 表新增 online（在线状态）列
-- 适用场景：数据库已由旧版 schema.sql 建好，不想重跑会 DROP 全表的脚本。
-- 若尚未建库 / 允许重建，直接重跑 src/main/resources/sql/schema.sql 即可（已含本列）。

-- 1) 给 user 表加 online 列
ALTER TABLE user
    ADD COLUMN online TINYINT(1) DEFAULT 0 COMMENT '在线状态：1=在线 0=离线';

-- 校验：返回 0 表示建表时未加过；如报错「Duplicate column」说明已存在，可忽略。
SELECT online FROM user LIMIT 0;
