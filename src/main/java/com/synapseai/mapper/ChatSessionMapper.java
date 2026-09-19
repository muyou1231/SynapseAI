package com.synapseai.mapper;

import com.synapseai.entity.ChatSession;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatSessionMapper {

    /** 创建会话（返回自增 id） */
    @Insert("INSERT INTO chat_session(user_id, title) VALUES(#{userId}, #{title})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChatSession session);

    /** 该用户的所有会话，按创建时间倒序（最新的在最前） */
    @Select("SELECT * FROM chat_session WHERE user_id = #{uid} ORDER BY create_time DESC, id DESC")
    List<ChatSession> selectByUser(@Param("uid") Long uid);

    /** 按 id 查询（同时校验归属，防止越权） */
    @Select("SELECT * FROM chat_session WHERE id = #{id} AND user_id = #{uid}")
    ChatSession selectByIdAndUser(@Param("id") Long id, @Param("uid") Long uid);

    /** 删除会话（仅本人可删） */
    @Delete("DELETE FROM chat_session WHERE id = #{id} AND user_id = #{uid}")
    int deleteByIdAndUser(@Param("id") Long id, @Param("uid") Long uid);
}
