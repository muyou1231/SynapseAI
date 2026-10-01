package com.synapseai.mapper;

import com.synapseai.entity.FamilyEvent;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** 家族大事记数据访问 */
@Mapper
public interface FamilyEventMapper {

    @Insert("INSERT INTO family_event(family_id, title, event_date, content, member_ids, create_time) " +
            "VALUES(#{familyId}, #{title}, #{eventDate}, #{content}, #{memberIds}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FamilyEvent event);

    @Select("SELECT * FROM family_event WHERE id = #{id}")
    FamilyEvent selectById(@Param("id") Long id);

    /** 家族大事记：按事件日期倒序（最近的在前），同日按 id 倒序 */
    @Select("SELECT * FROM family_event WHERE family_id = #{familyId} ORDER BY event_date DESC, id DESC")
    List<FamilyEvent> listByFamily(@Param("familyId") Long familyId);

    @Update("UPDATE family_event SET title = #{title}, event_date = #{eventDate}, content = #{content}, " +
            "member_ids = #{memberIds} WHERE id = #{id}")
    int update(FamilyEvent event);

    @Delete("DELETE FROM family_event WHERE id = #{id}")
    int delete(@Param("id") Long id);

    @Delete("DELETE FROM family_event WHERE family_id = #{familyId}")
    int deleteByFamily(@Param("familyId") Long familyId);
}
