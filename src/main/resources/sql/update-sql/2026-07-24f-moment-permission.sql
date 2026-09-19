-- 朋友圈权限增强：部分可见(allow_list) / 不给谁看(deny_list) / 限时可见(expire_time)
-- 执行方式（库已存在旧表时单独跑本脚本即可）：
--   USE synapse_ai;  source 2026-07-24f-moment-permission.sql;

USE synapse_ai;
SET NAMES utf8mb4;

ALTER TABLE moment
    ADD COLUMN allow_list  TEXT         NULL COMMENT 'JSON 数组，部分可见(PARTIAL)模式下的可见好友 id' AFTER visibility,
    ADD COLUMN deny_list   TEXT         NULL COMMENT 'JSON 数组，不给谁看的好友 id' AFTER allow_list,
    ADD COLUMN expire_time DATETIME     NULL COMMENT '可见截止时间；超过后仅作者可见（限时可见）' AFTER deny_list;
