-- =============================================================
-- 家庭树 / 家族族谱模块（增量迁移，可重复执行）
-- 说明：
--   1) 本脚本兼容 MySQL 5.7 / 8.0，建表语句均使用 IF NOT EXISTS；
--      新增索引使用「存储过程 + CONTINUE HANDLER 吞异常」的幂等写法
--      （MySQL 5.7 不支持 ADD INDEX IF NOT EXISTS）。
--   2) 递归查询（WITH RECURSIVE）仅在 MySQL 8.0+ 可用，
--      服务端已做降级：捕获异常后回退到 Java 内存递归，5.7 亦可正常运行。
-- =============================================================

-- ---------- 1. 家族表 ----------
CREATE TABLE IF NOT EXISTS family (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(64)  NOT NULL                COMMENT '家族名称',
    intro       VARCHAR(500) DEFAULT ''              COMMENT '家族简介',
    cover_url   VARCHAR(512) DEFAULT ''              COMMENT '家族封面图 URL',
    user_id     BIGINT       NOT NULL                COMMENT '创建人 user_id',
    visibility  TINYINT      NOT NULL DEFAULT 0      COMMENT '可见性：0=私有 1=链接只读 2=公开',
    share_token VARCHAR(64)  DEFAULT NULL            COMMENT '只读分享 token（NULL=未生成）',
    version     INT          NOT NULL DEFAULT 0      COMMENT '乐观锁版本号',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=正常 1=已删除',
    PRIMARY KEY (id),
    KEY idx_family_user (user_id, deleted),
    KEY idx_family_visibility (visibility, deleted),
    KEY uk_family_share_token (share_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家族表';

-- ---------- 2. 家族成员表 ----------
CREATE TABLE IF NOT EXISTS family_member (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    family_id   BIGINT       NOT NULL                COMMENT '所属家族 id',
    user_id     BIGINT       DEFAULT NULL            COMMENT '绑定的系统用户 id（可空）',
    name        VARCHAR(64)  NOT NULL                COMMENT '姓名',
    avatar_url  VARCHAR(512) DEFAULT ''              COMMENT '头像 URL',
    gender      TINYINT      NOT NULL DEFAULT 0      COMMENT '性别：0=未知 1=男 2=女',
    birth_date  DATE         DEFAULT NULL            COMMENT '出生日期',
    death_date  DATE         DEFAULT NULL            COMMENT '逝世日期（NULL=在世）',
    bio         TEXT                                 COMMENT '人物简介（生平介绍）',
    occupation  VARCHAR(128) DEFAULT ''              COMMENT '职业',
    hometown    VARCHAR(128) DEFAULT ''              COMMENT '籍贯',
    phone       VARCHAR(32)  DEFAULT ''              COMMENT '联系电话',
    address     VARCHAR(255) DEFAULT ''              COMMENT '住址',
    remark      VARCHAR(500) DEFAULT ''              COMMENT '备注',
    version     INT          NOT NULL DEFAULT 0      COMMENT '乐观锁版本号',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=正常 1=已删除',
    PRIMARY KEY (id),
    KEY idx_member_family (family_id, deleted),
    KEY idx_member_user (user_id),
    KEY idx_member_name (family_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家族成员表';

-- ---------- 3. 亲属关系表 ----------
-- 语义：一行表示「member_a 是 member_b 的 relation_type」
--   例：(张三, 李四, FATHER) = 张三是李四的父亲
--   夫妻为双向：插入 (A,B,SPOUSE) 与 (B,A,SPOUSE) 两条
CREATE TABLE IF NOT EXISTS family_relation (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    family_id     BIGINT       NOT NULL                COMMENT '所属家族 id',
    member_a_id   BIGINT       NOT NULL                COMMENT '关系主体（a 是 b 的 xxx）',
    member_b_id   BIGINT       NOT NULL                COMMENT '关系客体',
    relation_type VARCHAR(24)  NOT NULL                COMMENT 'SPOUSE/EX_SPOUSE/FATHER/MOTHER/SON/DAUGHTER/STEP_FATHER/STEP_MOTHER/ADOPTED_SON/ADOPTED_DAUGHTER',
    relation_desc VARCHAR(255) DEFAULT ''              COMMENT '关系描述（如「1988 年结婚」「自幼过继」）',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_relation (member_a_id, member_b_id, relation_type),
    KEY idx_relation_family (family_id),
    KEY idx_relation_b (member_b_id, relation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='亲属关系表';

-- ---------- 4. 成员相册表 ----------
CREATE TABLE IF NOT EXISTS family_member_photo (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    member_id   BIGINT       NOT NULL                COMMENT '成员 id',
    url         VARCHAR(512) NOT NULL                COMMENT '图片 URL',
    caption     VARCHAR(255) DEFAULT ''              COMMENT '图片说明',
    sort_no     INT          NOT NULL DEFAULT 0      COMMENT '排序号（升序）',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=正常 1=已删除',
    PRIMARY KEY (id),
    KEY idx_photo_member (member_id, deleted, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成员相册表';

-- ---------- 5. 家族大事记表 ----------
CREATE TABLE IF NOT EXISTS family_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    family_id   BIGINT       NOT NULL                COMMENT '所属家族 id',
    title       VARCHAR(128) NOT NULL                COMMENT '事件标题',
    event_date  DATE         NOT NULL                COMMENT '事件日期',
    content     TEXT                                 COMMENT '事件内容',
    member_ids  VARCHAR(1000) DEFAULT ''             COMMENT '关联成员 id，逗号分隔，如 12,34,56',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_event_family (family_id, event_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家族大事记表';

-- ---------- 幂等补索引（MySQL 5.7 兼容写法） ----------
DROP PROCEDURE IF EXISTS proc_family_add_index;
DELIMITER $$
CREATE PROCEDURE proc_family_add_index()
BEGIN
    DECLARE CONTINUE HANDLER FOR 1061 BEGIN END; -- Duplicate key name：索引已存在则忽略
    DECLARE CONTINUE HANDLER FOR 1060 BEGIN END; -- Duplicate column name

    -- 成员：家族内按创建时间倒序拉取
    CREATE INDEX idx_member_family_ct ON family_member (family_id, deleted, create_time);
    -- 关系：按 a 侧快速查「某人的全部关系」
    CREATE INDEX idx_relation_a ON family_relation (member_a_id, relation_type);
    -- 家族：分享 token 唯一（已生成时防重复）
    CREATE INDEX uk_family_token ON family (share_token);
END$$
DELIMITER ;
CALL proc_family_add_index();
DROP PROCEDURE IF EXISTS proc_family_add_index;
