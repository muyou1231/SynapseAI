package com.synapseai.mapper;

import com.synapseai.entity.MomentComment;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MomentCommentMapper {

    @Insert("INSERT INTO moment_comment(moment_id, user_id, parent_id, content, images, create_time) " +
            "VALUES(#{momentId}, #{userId}, #{parentId}, #{content}, #{images}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MomentComment comment);

    @Select("SELECT * FROM moment_comment WHERE moment_id = #{momentId} ORDER BY create_time ASC, id ASC")
    List<MomentComment> selectByMoment(Long momentId);

    @Select("SELECT * FROM moment_comment WHERE id = #{id}")
    MomentComment selectById(Long id);

    @Delete("DELETE FROM moment_comment WHERE id = #{id}")
    int deleteById(Long id);
}
