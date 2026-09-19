package com.synapseai.mapper;

import com.synapseai.entity.GroupMember;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface GroupMemberMapper {

    // ===== 通用 CRUD（显式写出，不依赖 BaseMapper 继承）=====

    @Insert("INSERT INTO group_member(group_id, user_id, role, create_time) " +
            "VALUES(#{groupId}, #{userId}, #{role}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(GroupMember member);

    @Select("SELECT * FROM group_member WHERE id = #{id}")
    GroupMember selectById(Long id);

    @Update("UPDATE group_member SET group_id = #{groupId}, user_id = #{userId}, " +
            "role = #{role}, create_time = #{createTime} WHERE id = #{id}")
    int updateById(GroupMember member);

    @Delete("DELETE FROM group_member WHERE id = #{id}")
    int deleteById(Long id);

    // ===== 自定义查询 =====

    @Select("select * from group_member where group_id = #{groupId}")
    List<GroupMember> findByGroupId(@Param("groupId") Long groupId);

    @Select("select * from group_member where user_id = #{userId}")
    List<GroupMember> findByUserId(@Param("userId") Long userId);

    @Select("select * from group_member where group_id = #{groupId} and user_id = #{userId}")
    GroupMember findByGroupIdAndUserId(@Param("groupId") Long groupId, @Param("userId") Long userId);

    @Select("select exists(select 1 from group_member where group_id = #{groupId} and user_id = #{userId})")
    boolean existsByGroupIdAndUserId(@Param("groupId") Long groupId, @Param("userId") Long userId);

    @Delete("delete from group_member where group_id = #{groupId} and user_id = #{userId}")
    void deleteByGroupIdAndUserId(@Param("groupId") Long groupId, @Param("userId") Long userId);

    @Delete("delete from group_member where group_id = #{groupId}")
    void deleteByGroupId(@Param("groupId") Long groupId);
}
