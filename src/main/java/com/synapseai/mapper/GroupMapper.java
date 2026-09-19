package com.synapseai.mapper;

import com.synapseai.entity.ChatGroup;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface GroupMapper {

    // ===== 通用 CRUD（显式写出，不依赖 BaseMapper 继承）=====

    @Insert("INSERT INTO chat_group(name, avatar, owner_id, deleted, create_time) " +
            "VALUES(#{name}, #{avatar}, #{ownerId}, #{deleted}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChatGroup group);

    @Select("SELECT * FROM chat_group WHERE id = #{id}")
    ChatGroup selectById(Long id);

    @Update("UPDATE chat_group SET name = #{name}, avatar = #{avatar}, " +
            "owner_id = #{ownerId}, deleted = #{deleted}, create_time = #{createTime} WHERE id = #{id}")
    int updateById(ChatGroup group);

    @Delete("DELETE FROM chat_group WHERE id = #{id}")
    int deleteById(Long id);

    /** 管理端：全部群聊（按创建时间倒序） */
    @Select("SELECT * FROM chat_group ORDER BY create_time DESC")
    List<ChatGroup> selectAll();

    @Select("SELECT COUNT(*) FROM chat_group")
    int count();
}
