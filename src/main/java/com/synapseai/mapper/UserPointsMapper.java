package com.synapseai.mapper;

import com.synapseai.entity.UserPoints;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserPointsMapper {

    @Insert("INSERT INTO user_points(user_id, points, level) VALUES(#{userId}, #{points}, #{level})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserPoints points);

    @Select("SELECT * FROM user_points WHERE user_id = #{userId}")
    UserPoints findByUserId(@Param("userId") Long userId);

    @Update("UPDATE user_points SET points = #{points}, level = #{level} WHERE user_id = #{userId}")
    int updateByUserId(UserPoints points);

    /** 积分排行榜（可用于未来的"排行榜"入口，本批先不暴露接口，预留） */
    @Select("SELECT * FROM user_points ORDER BY points DESC LIMIT #{limit}")
    java.util.List<UserPoints> topN(@Param("limit") int limit);
}
