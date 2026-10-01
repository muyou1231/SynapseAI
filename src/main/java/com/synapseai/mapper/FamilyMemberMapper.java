package com.synapseai.mapper;

import com.synapseai.entity.FamilyMember;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** 家族成员数据访问 */
@Mapper
public interface FamilyMemberMapper {

    @Insert("INSERT INTO family_member(family_id, user_id, name, avatar_url, gender, birth_date, death_date, " +
            "bio, occupation, hometown, phone, address, remark, version, create_time, update_time) " +
            "VALUES(#{familyId}, #{userId}, #{name}, #{avatarUrl}, #{gender}, #{birthDate}, #{deathDate}, " +
            "#{bio}, #{occupation}, #{hometown}, #{phone}, #{address}, #{remark}, 0, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FamilyMember member);

    @Select("SELECT * FROM family_member WHERE id = #{id} AND deleted = 0")
    FamilyMember selectById(@Param("id") Long id);

    /** 家族内全部在册成员，按创建时间升序（保证树构建结果稳定） */
    @Select("SELECT * FROM family_member WHERE family_id = #{familyId} AND deleted = 0 ORDER BY id ASC")
    List<FamilyMember> listByFamily(@Param("familyId") Long familyId);

    /** 分页取成员（超大家族懒加载时后端仍一次性构图，此接口供成员管理列表使用） */
    @Select("SELECT * FROM family_member WHERE family_id = #{familyId} AND deleted = 0 ORDER BY id ASC LIMIT #{offset}, #{size}")
    List<FamilyMember> pageByFamily(@Param("familyId") Long familyId, @Param("offset") Integer offset, @Param("size") Integer size);

    /**
     * 更新成员信息（乐观锁）。
     * 返回 0 表示 version 已被他人改动，Service 层据此返回冲突提示。
     */
    @Update("UPDATE family_member SET name = #{name}, avatar_url = #{avatarUrl}, gender = #{gender}, " +
            "birth_date = #{birthDate}, death_date = #{deathDate}, bio = #{bio}, occupation = #{occupation}, " +
            "hometown = #{hometown}, phone = #{phone}, address = #{address}, remark = #{remark}, " +
            "version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND deleted = 0 AND version = #{version}")
    int updateWithVersion(FamilyMember member);

    /** 仅更新头像（上传头像场景，不参与乐观锁竞争，避免与信息编辑互相覆盖） */
    @Update("UPDATE family_member SET avatar_url = #{avatarUrl}, update_time = NOW() WHERE id = #{id} AND deleted = 0")
    int updateAvatar(@Param("id") Long id, @Param("avatarUrl") String avatarUrl);

    @Update("UPDATE family_member SET deleted = 1, update_time = NOW() WHERE id = #{id} AND deleted = 0")
    int softDelete(@Param("id") Long id);

    /** 家族内姓名模糊搜索（供树内高亮定位） */
    @Select("SELECT * FROM family_member WHERE family_id = #{familyId} AND deleted = 0 " +
            "AND name LIKE CONCAT('%', #{keyword}, '%') ORDER BY id ASC LIMIT #{limit}")
    List<FamilyMember> searchInFamily(@Param("familyId") Long familyId, @Param("keyword") String keyword, @Param("limit") Integer limit);

    /**
     * 全局姓名模糊搜索：只搜「我创建/我参与的家族」以及公开家族，避免越权看到他人私有家族。
     */
    @Select("SELECT m.* FROM family_member m JOIN family f ON f.id = m.family_id " +
            "WHERE m.deleted = 0 AND f.deleted = 0 AND m.name LIKE CONCAT('%', #{keyword}, '%') " +
            "AND (f.user_id = #{userId} OR f.visibility = 2 " +
            "     OR EXISTS(SELECT 1 FROM family_member x WHERE x.family_id = f.id AND x.deleted = 0 AND x.user_id = #{userId})) " +
            "ORDER BY m.id DESC LIMIT #{limit}")
    List<FamilyMember> searchGlobal(@Param("userId") Long userId, @Param("keyword") String keyword, @Param("limit") Integer limit);

    @Select("SELECT COUNT(*) FROM family_member WHERE family_id = #{familyId} AND deleted = 0")
    int countByFamily(@Param("familyId") Long familyId);

    /**
     * 判断某用户是否已作为成员登记在该家族中。
     * 用于写权限判定：家族成员本人（已绑定账号）也可编辑族谱，而不只是创建人。
     */
    @Select("SELECT COUNT(*) FROM family_member WHERE family_id = #{familyId} AND user_id = #{userId} AND deleted = 0")
    int countMemberUser(@Param("familyId") Long familyId, @Param("userId") Long userId);

    /** 按 id 批量查（大事记关联成员姓名回显） */
    @Select("<script>SELECT * FROM family_member WHERE deleted = 0 AND id IN " +
            "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach></script>")
    List<FamilyMember> listByIds(@Param("ids") List<Long> ids);
}
