-- 通知增加「阅读停留时长」列：用户必须等待超过该时长才能点击「我知道了」
-- 0 / NULL 表示不限制。撤销通知由后端物理删除 notice + notice_read 实现，无需新增列。
ALTER TABLE notice
    ADD COLUMN read_wait_seconds INT NOT NULL DEFAULT 0
    COMMENT '阅读停留时长（秒）：必须大于该时长用户才能点击「我知道了」；0=不限制';
