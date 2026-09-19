package com.synapseai.mapper;

import com.synapseai.entity.MomentLike;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MomentLikeMapper {

    /** 点赞（INSERT IGNORE：重复点赞不会报错） */
    @Insert("INSERT IGNORE INTO moment_like(moment_id, user_id) VALUES(#{momentId}, #{userId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MomentLike like);

    /** 取消点赞（用户对某条动态） */
    @Delete("DELETE FROM moment_like WHERE moment_id = #{momentId} AND user_id = #{userId}")
    int deleteByUser(@Param("momentId") Long momentId, @Param("userId") Long userId);

    /** 某条动态的点赞总数 */
    @Select("SELECT COUNT(*) FROM moment_like WHERE moment_id = #{momentId}")
    int countByMoment(@Param("momentId") Long momentId);

    /** 某用户是否对某动态点过赞（>0 表示已赞） */
    @Select("SELECT COUNT(*) FROM moment_like WHERE moment_id = #{momentId} AND user_id = #{userId}")
    int countByUser(@Param("momentId") Long momentId, @Param("userId") Long userId);

    /** 批量查询：某用户在当前动态集合中点过赞的动态 id 列表（用于 feed 批量判定 liked） */
    @Select("<script>" +
            "SELECT moment_id FROM moment_like WHERE user_id = #{userId} AND moment_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<Long> likedMomentIds(@Param("userId") Long userId, @Param("ids") List<Long> ids);

    /** 删除某条动态的全部点赞（动态被删除时级联清理） */
    @Delete("DELETE FROM moment_like WHERE moment_id = #{momentId}")
    int deleteByMoment(@Param("momentId") Long momentId);
}
