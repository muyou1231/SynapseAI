-- 通知增加 category 列：区分「管理员通知(ADMIN)」与「朋友圈评论/回复通知(COMMENT)」
-- 用于「多多的家园」红点只统计评论类未读，而不受管理员通知干扰。
ALTER TABLE notice
    ADD COLUMN category VARCHAR(16) NOT NULL DEFAULT 'ADMIN'
    COMMENT 'ADMIN=管理员通知 / COMMENT=朋友圈评论回复通知';
