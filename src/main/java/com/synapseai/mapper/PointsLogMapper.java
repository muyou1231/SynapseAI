package com.synapseai.mapper;

import com.synapseai.entity.PointsLog;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PointsLogMapper {

    @Insert("INSERT INTO points_log(user_id, change_val, reason, ref_id, create_time) " +
            "VALUES(#{userId}, #{changeVal}, #{reason}, #{refId}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PointsLog log);

    /** 积分明细（最近 N 条，倒序） */
    @Select("SELECT * FROM points_log WHERE user_id = #{userId} ORDER BY create_time DESC LIMIT #{limit}")
    List<PointsLog> findRecent(@Param("userId") Long userId, @Param("limit") int limit);
}
