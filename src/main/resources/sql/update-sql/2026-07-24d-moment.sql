-- 朋友圈（动态）表（2026-07-24 新增）
CREATE TABLE moment (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    content     TEXT,
    images      TEXT         COMMENT 'JSON 数组，存走 8080 代理的图片 URL',
    visibility  VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC=公开 / FRIENDS=仅好友可见 / PRIVATE=仅自己可见',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_moment_user (user_id),
    KEY idx_moment_create (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
