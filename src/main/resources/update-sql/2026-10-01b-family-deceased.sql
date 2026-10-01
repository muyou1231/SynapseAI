-- 家族树成员：新增「是否已逝世」标记
-- 背景：原先用 death_date IS NULL 表示在世，无法表达「已逝世但逝世日期未知」。
--       现引入 deceased 标记：0=在世；1=已逝世（death_date 可为空，表示日期未知）。
-- 执行库：synapse_ai
USE synapse_ai;

-- MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，用存储过程包裹 CONTINUE HANDLER 实现幂等
DROP PROCEDURE IF EXISTS sp_add_family_member_deceased;
DELIMITER $$
CREATE PROCEDURE sp_add_family_member_deceased()
BEGIN
    DECLARE CONTINUE HANDLER FOR 1060 BEGIN END; -- 1060: Duplicate column name
    ALTER TABLE family_member
        ADD COLUMN deceased TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否已逝世：0=在世，1=已逝世（death_date 为空表示逝世日期未知）';
END$$
DELIMITER ;
CALL sp_add_family_member_deceased();
DROP PROCEDURE IF EXISTS sp_add_family_member_deceased;

-- 回填：历史上填写过逝世日期的一律视为已逝世
UPDATE family_member SET deceased = 1 WHERE death_date IS NOT NULL;

-- 在世成员不应残留逝世日期（历史脏数据兜底清理）
UPDATE family_member SET death_date = NULL WHERE deceased = 0 AND death_date IS NOT NULL;
