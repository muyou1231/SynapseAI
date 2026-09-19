package com.synapseai.mapper;

import com.synapseai.entity.UrgentMute;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface UrgentMuteMapper {

    // ===== 通用 CRUD（显式写出，不依赖 BaseMapper 继承）=====

    @Insert("INSERT INTO urgent_mute(user_id, peer_id) " +
            "VALUES(#{userId}, #{peerId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UrgentMute mute);

    @Select("SELECT * FROM urgent_mute WHERE id = #{id}")
    UrgentMute selectById(Long id);

    @Update("UPDATE urgent_mute SET user_id = #{userId}, peer_id = #{peerId} WHERE id = #{id}")
    int updateById(UrgentMute mute);

    @Delete("DELETE FROM urgent_mute WHERE id = #{id}")
    int deleteById(Long id);

    // ===== 自定义查询 =====

    @Select("select exists(select 1 from urgent_mute where user_id = #{userId} and peer_id = #{peerId})")
    boolean existsByUserIdAndPeerId(@Param("userId") Long userId, @Param("peerId") Long peerId);

    @Delete("delete from urgent_mute where user_id = #{userId} and peer_id = #{peerId}")
    void deleteByUserIdAndPeerId(@Param("userId") Long userId, @Param("peerId") Long peerId);

    @Select("select * from urgent_mute where user_id = #{userId}")
    List<UrgentMute> findByUserId(@Param("userId") Long userId);
}
