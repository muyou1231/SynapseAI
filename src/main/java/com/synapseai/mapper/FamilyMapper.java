package com.synapseai.mapper;

import com.synapseai.entity.Family;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 家族表数据访问。
 * <p>
 * 按项目统一约定：不继承 MyBatis-Plus 的 BaseMapper，全部显式编写 SQL 注解，
 * 便于精确控制索引命中与逻辑删除条件。
 */
@Mapper
public interface FamilyMapper {

    @Insert("INSERT INTO family(name, intro, cover_url, user_id, visibility, share_token, version, create_time, update_time) " +
            "VALUES(#{name}, #{intro}, #{coverUrl}, #{userId}, #{visibility}, #{shareToken}, 0, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Family family);

    @Select("SELECT * FROM family WHERE id = #{id} AND deleted = 0")
    Family selectById(@Param("id") Long id);

    /** 按分享 token 查询（只读分享入口） */
    @Select("SELECT * FROM family WHERE share_token = #{token} AND deleted = 0 LIMIT 1")
    Family selectByShareToken(@Param("token") String token);

    /**
     * 我的家族列表：我创建的 + 我作为成员被登记的家族，按更新时间倒序。
     * 用 EXISTS 子查询避免 JOIN 产生重复行（一个人可能在同一个家族只登记一次，但仍以防万一）。
     */
    @Select("SELECT f.*, " +
            "  (SELECT COUNT(*) FROM family_member m WHERE m.family_id = f.id AND m.deleted = 0) AS member_count " +
            "FROM family f " +
            "WHERE f.deleted = 0 AND (f.user_id = #{userId} " +
            "   OR EXISTS(SELECT 1 FROM family_member m2 WHERE m2.family_id = f.id AND m2.deleted = 0 AND m2.user_id = #{userId})) " +
            "ORDER BY f.update_time DESC")
    List<Family> listMine(@Param("userId") Long userId);

    /** 公开家族（visibility=2）列表 */
    @Select("SELECT f.*, " +
            "  (SELECT COUNT(*) FROM family_member m WHERE m.family_id = f.id AND m.deleted = 0) AS member_count " +
            "FROM family f WHERE f.deleted = 0 AND f.visibility = 2 ORDER BY f.update_time DESC LIMIT #{limit}")
    List<Family> listPublic(@Param("limit") Integer limit);

    /**
     * 更新基础信息。带乐观锁：version 匹配才更新，并把 version + 1。
     * 返回影响行数，0 表示版本冲突（他人已修改）。
     */
    @Update("UPDATE family SET name = #{name}, intro = #{intro}, cover_url = #{coverUrl}, " +
            "visibility = #{visibility}, version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND deleted = 0 AND version = #{version}")
    int updateWithVersion(Family family);

    /** 仅更新分享 token */
    @Update("UPDATE family SET share_token = #{token}, version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND deleted = 0")
    int updateShareToken(@Param("id") Long id, @Param("token") String token);

    /** 逻辑删除家族（成员/关系/相册由 Service 级联清理） */
    @Update("UPDATE family SET deleted = 1, update_time = NOW() WHERE id = #{id} AND deleted = 0")
    int softDelete(@Param("id") Long id);

    /** 统计某用户创建的家族数量（用于创建配额校验） */
    @Select("SELECT COUNT(*) FROM family WHERE user_id = #{userId} AND deleted = 0")
    int countByUser(@Param("userId") Long userId);
}
