package com.synapseai.mapper;

import com.synapseai.entity.Painting;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PaintingMapper {

    /** 插入一件作品（返回自增 id） */
    @Insert("INSERT INTO painting(user_id, title, description, image_url, create_time) " +
            "VALUES(#{userId}, #{title}, #{description}, #{imageUrl}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Painting painting);

    /** 按 id 取单条（校验归属） */
    @Select("SELECT * FROM painting WHERE id = #{id}")
    Painting selectById(Long id);

    /** 我的全部作品（按创建时间倒序，最新在前） */
    @Select("SELECT * FROM painting WHERE user_id = #{userId} ORDER BY create_time DESC, id DESC")
    List<Painting> selectByUserId(@Param("userId") Long userId);

    /** 删除单条（校验归属） */
    @Delete("DELETE FROM painting WHERE id = #{id} AND user_id = #{userId}")
    int deleteById(@Param("userId") Long userId, @Param("id") Long id);
}
