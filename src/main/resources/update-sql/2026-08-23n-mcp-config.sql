-- MCP 功能配置表（增量迁移）
CREATE TABLE IF NOT EXISTS mcp_config (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    code         VARCHAR(64)  NOT NULL COMMENT '功能唯一编码，如 message_sender',
    name         VARCHAR(128) NOT NULL COMMENT '功能显示名',
    description  VARCHAR(512) DEFAULT '' COMMENT '功能简介',
    enabled      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0=停用 1=启用',
    config_json  TEXT         COMMENT 'JSON 配置（各功能自定义）',
    sort         INT          NOT NULL DEFAULT 0 COMMENT '排序（升序）',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mcp_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
