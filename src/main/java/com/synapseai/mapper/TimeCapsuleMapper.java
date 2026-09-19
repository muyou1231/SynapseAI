package com.synapseai.mapper;

import com.synapseai.entity.TimeCapsule;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TimeCapsuleMapper {

    @Insert("INSERT INTO time_capsule(sender_id, receiver_id, content_enc, open_time, status, create_time) " +
            "VALUES(#{senderId}, #{receiverId}, #{contentEnc}, #{openTime}, #{status}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TimeCapsule capsule);

    @Select("SELECT * FROM time_capsule WHERE id = #{id}")
    TimeCapsule selectById(@Param("id") Long id);

    /** 我写出的全部胶囊（发给自己或好友的都算），按开启日期升序，未开启的排前面更直观 */
    @Select("SELECT * FROM time_capsule WHERE sender_id = #{senderId} ORDER BY open_time ASC")
    List<TimeCapsule> findBySender(@Param("senderId") Long senderId);

    /** 别人写给我的胶囊（receiver_id = 我），按开启日期升序 */
    @Select("SELECT * FROM time_capsule WHERE receiver_id = #{receiverId} ORDER BY open_time ASC")
    List<TimeCapsule> findByReceiver(@Param("receiverId") Long receiverId);

    /** 定时任务扫描：到期仍处于封存状态的胶囊 */
    @Select("SELECT * FROM time_capsule WHERE status = 'SEALED' AND open_time <= #{now} LIMIT 200")
    List<TimeCapsule> findDueForUnlock(@Param("now") LocalDateTime now);

    @Update("UPDATE time_capsule SET status = #{status}, unlocked_time = #{unlockedTime} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("unlockedTime") LocalDateTime unlockedTime);
}
