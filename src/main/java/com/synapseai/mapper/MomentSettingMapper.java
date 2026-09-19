package com.synapseai.mapper;

import com.synapseai.entity.MomentSetting;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MomentSettingMapper {

    @Select("SELECT * FROM moment_setting WHERE user_id = #{userId} LIMIT 1")
    MomentSetting findByUser(@Param("userId") Long userId);

    @Insert("INSERT INTO moment_setting(user_id, visibility) " +
            "VALUES(#{userId}, #{visibility}) " +
            "ON DUPLICATE KEY UPDATE visibility = #{visibility}")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(MomentSetting setting);
}
