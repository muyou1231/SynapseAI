package com.synapseai.mapper;

import com.synapseai.entity.Conversation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ConversationMapper {

    /** 删除会话：标记 deleted=1；若尚无记录则插入一行 deleted=1。利用唯一键幂等。 */
    @Insert("INSERT INTO conversation(user_id, target_type, target_id, deleted) " +
            "VALUES(#{userId}, #{targetType}, #{targetId}, 1) " +
            "ON DUPLICATE KEY UPDATE deleted = 1")
    int markDeleted(@Param("userId") Long userId,
                    @Param("targetType") String targetType,
                    @Param("targetId") Long targetId);

    /** 恢复会话：标记 deleted=0（确保存在）；从通讯录/群列表重新打开会话时调用。 */
    @Insert("INSERT INTO conversation(user_id, target_type, target_id, deleted) " +
            "VALUES(#{userId}, #{targetType}, #{targetId}, 0) " +
            "ON DUPLICATE KEY UPDATE deleted = 0")
    int restore(@Param("userId") Long userId,
                @Param("targetType") String targetType,
                @Param("targetId") Long targetId);

    /** 返回该用户所有已删除(deleted=1)的会话的 (target_type, target_id)，用于会话列表过滤 */
    @Select("SELECT target_type, target_id FROM conversation WHERE user_id = #{uid} AND deleted = 1")
    List<Map<String, Object>> deletedTargets(@Param("uid") Long uid);
}
