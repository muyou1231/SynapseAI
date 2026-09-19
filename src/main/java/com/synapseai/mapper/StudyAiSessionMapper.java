package com.synapseai.mapper;

import com.synapseai.entity.StudyAiSession;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 学习空间 AI 会话 Mapper（显式 SQL，不继承 BaseMapper） */
@Mapper
public interface StudyAiSessionMapper {

    @Insert("INSERT INTO study_ai_session (user_id, title) VALUES (#{userId}, #{title})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(StudyAiSession s);

    @Select("SELECT * FROM study_ai_session WHERE user_id = #{userId} ORDER BY create_time DESC")
    List<StudyAiSession> selectByUser(@Param("userId") Long userId);

    @Select("SELECT * FROM study_ai_session WHERE id = #{id} AND user_id = #{userId} LIMIT 1")
    StudyAiSession selectByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    @Delete("DELETE FROM study_ai_session WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM study_chat WHERE user_id = #{userId} AND session_id = #{sessionId}")
    int countMessages(@Param("userId") Long userId, @Param("sessionId") Long sessionId);
}
