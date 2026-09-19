package com.synapseai.mapper;

import com.synapseai.entity.UserPrivacy;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface UserPrivacyMapper {

    // ===== 通用 CRUD（显式写出，不依赖 BaseMapper 继承）=====

    @Insert("INSERT INTO user_privacy(user_id, field, visibility, create_time) " +
            "VALUES(#{userId}, #{field}, #{visibility}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserPrivacy privacy);

    @Select("SELECT * FROM user_privacy WHERE id = #{id}")
    UserPrivacy selectById(Long id);

    @Update("UPDATE user_privacy SET user_id = #{userId}, field = #{field}, " +
            "visibility = #{visibility}, create_time = #{createTime} WHERE id = #{id}")
    int updateById(UserPrivacy privacy);

    @Delete("DELETE FROM user_privacy WHERE id = #{id}")
    int deleteById(Long id);

    // ===== 自定义查询 =====

    /** 不存在则插入，存在则更新可见范围（依赖 (user_id, field) 唯一键） */
    @Insert("INSERT INTO user_privacy(user_id, field, visibility) VALUES(#{userId}, #{field}, #{visibility}) " +
            "ON DUPLICATE KEY UPDATE visibility = #{visibility}")
    int upsert(@Param("userId") Long userId, @Param("field") String field, @Param("visibility") String visibility);

    @Select("SELECT * FROM user_privacy WHERE user_id = #{userId}")
    List<UserPrivacy> selectByUserId(@Param("userId") Long userId);
}
