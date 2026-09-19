package com.synapseai.mapper;

import com.synapseai.entity.Pin;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PinMapper {

    // ===== 通用 CRUD（显式写出，不依赖 BaseMapper 继承）=====

    @Insert("INSERT INTO pin(user_id, target_type, target_id, create_time) " +
            "VALUES(#{userId}, #{targetType}, #{targetId}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Pin pin);

    @Select("SELECT * FROM pin WHERE id = #{id}")
    Pin selectById(Long id);

    @Update("UPDATE pin SET user_id = #{userId}, target_type = #{targetType}, " +
            "target_id = #{targetId}, create_time = #{createTime} WHERE id = #{id}")
    int updateById(Pin pin);

    @Delete("DELETE FROM pin WHERE id = #{id}")
    int deleteById(Long id);

    // ===== 自定义查询 =====

    @Select("select exists(select 1 from pin where user_id = #{userId} and target_type = #{targetType} and target_id = #{targetId})")
    boolean existsByUserIdAndTargetTypeAndTargetId(@Param("userId") Long userId, @Param("targetType") String targetType, @Param("targetId") Long targetId);

    @Delete("delete from pin where user_id = #{userId} and target_type = #{targetType} and target_id = #{targetId}")
    void deleteByUserIdAndTargetTypeAndTargetId(@Param("userId") Long userId, @Param("targetType") String targetType, @Param("targetId") Long targetId);
}
