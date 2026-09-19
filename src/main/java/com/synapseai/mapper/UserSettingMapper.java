package com.synapseai.mapper;

import com.synapseai.entity.UserSetting;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserSettingMapper {

    @Insert("INSERT INTO user_setting(user_id, ghost_read) VALUES(#{userId}, #{ghostRead})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserSetting setting);

    @Select("SELECT * FROM user_setting WHERE user_id = #{userId}")
    UserSetting findByUserId(@Param("userId") Long userId);

    @Update("UPDATE user_setting SET ghost_read = #{ghostRead} WHERE user_id = #{userId}")
    int updateGhostRead(@Param("userId") Long userId, @Param("ghostRead") boolean ghostRead);
}
