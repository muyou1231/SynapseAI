package com.synapseai.mapper;

import com.synapseai.entity.StudyChat;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface StudyChatMapper {

    /** 插入一条对话消息（user 或 ai），返回自增 id */
    @Insert("INSERT INTO study_chat(user_id, role, mode, thread, session_id, content, seq, create_time) " +
            "VALUES(#{userId}, #{role}, #{mode}, #{thread}, #{sessionId}, #{content}, #{seq}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(StudyChat msg);

    /** 按 id 取单条（校验归属，防越权） */
    @Select("SELECT * FROM study_chat WHERE id = #{id}")
    StudyChat selectById(Long id);

    /** 我的某个 AI 会话下的全部对话（按 seq 升序回放，兼容旧数据：thread 缺省归并到 chat） */
    @Select("SELECT * FROM study_chat WHERE user_id = #{userId} AND session_id = #{sessionId} ORDER BY seq ASC, id ASC")
    List<StudyChat> selectBySession(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    /** 我的某个 AI 会话下、某个子线（thread）的对话（按 seq 升序回放）——四类独立记录线 */
    @Select("SELECT * FROM study_chat WHERE user_id = #{userId} AND session_id = #{sessionId} AND thread = #{thread} ORDER BY seq ASC, id ASC")
    List<StudyChat> selectByThread(@Param("userId") Long userId, @Param("sessionId") Long sessionId, @Param("thread") String thread);

    /** 我的全部对话（按 seq 升序回放，兼容改造前的旧数据 session_id=0） */
    @Select("SELECT * FROM study_chat WHERE user_id = #{userId} ORDER BY seq ASC, id ASC")
    List<StudyChat> selectByUserId(@Param("userId") Long userId);

    /** 当前用户某个会话下、某子线的最大 seq（没有则返回 0） */
    @Select("SELECT COALESCE(MAX(seq), 0) FROM study_chat WHERE user_id = #{userId} AND session_id = #{sessionId} AND thread = #{thread}")
    int selectMaxSeq(@Param("userId") Long userId, @Param("sessionId") Long sessionId, @Param("thread") String thread);

    /** 按会话删除全部消息（校验归属） */
    @Delete("DELETE FROM study_chat WHERE user_id = #{userId} AND session_id = #{sessionId}")
    int deleteBySession(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    /** 按会话 + 子线删除消息（清空某一类记录线，校验归属） */
    @Delete("DELETE FROM study_chat WHERE user_id = #{userId} AND session_id = #{sessionId} AND thread = #{thread}")
    int deleteByThread(@Param("userId") Long userId, @Param("sessionId") Long sessionId, @Param("thread") String thread);

    /** 统计某会话下的消息条数（用于会话列表展示，跨全部子线求和） */
    @Select("SELECT COUNT(*) FROM study_chat WHERE user_id = #{userId} AND session_id = #{sessionId}")
    int countBySession(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    /** 流式过程中实时更新某条 AI 回复的内容 */
    @Update("UPDATE study_chat SET content = #{content} WHERE id = #{id}")
    int updateContent(@Param("id") Long id, @Param("content") String content);

    /** 删除单条（校验归属） */
    @Delete("DELETE FROM study_chat WHERE id = #{id} AND user_id = #{userId}")
    int deleteById(@Param("userId") Long userId, @Param("id") Long id);

    /** 清空当前用户的全部对话 */
    @Delete("DELETE FROM study_chat WHERE user_id = #{userId}")
    int deleteAll(@Param("userId") Long userId);
}
