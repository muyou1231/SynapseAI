package com.synapseai.mapper;

import com.synapseai.entity.Message;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MessageMapper {

    // ===== 通用 CRUD（显式写出，不依赖 BaseMapper 继承）=====

    @Insert("INSERT INTO message(sender_id, target_type, target_id, `type`, content, urgent, `read`, recalled, deleted, create_time, " +
            "bomb_seconds, bomb_deadline, bomb_status) " +
            "VALUES(#{senderId}, #{targetType}, #{targetId}, #{type}, #{content}, #{urgent}, #{read}, #{recalled}, #{deleted}, #{createTime}, " +
            "#{bombSeconds}, #{bombDeadline}, #{bombStatus})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Message message);

    @Select("SELECT * FROM message WHERE id = #{id}")
    Message selectById(Long id);

    @Update("UPDATE message SET sender_id = #{senderId}, target_type = #{targetType}, " +
            "target_id = #{targetId}, `type` = #{type}, content = #{content}, " +
            "urgent = #{urgent}, `read` = #{read}, recalled = #{recalled}, " +
            "deleted = #{deleted}, create_time = #{createTime}, " +
            "bomb_seconds = #{bombSeconds}, bomb_deadline = #{bombDeadline}, bomb_status = #{bombStatus} " +
            "WHERE id = #{id}")
    int updateById(Message message);

    /** 物理删除（仅管理员/内部使用） */
    @Delete("DELETE FROM message WHERE id = #{id}")
    int deleteById(Long id);

    /** 软删除：将消息标记为已删除（用户主动删除消息时调用） */
    @Update("UPDATE message SET deleted = 1 WHERE id = #{id}")
    int softDelete(@Param("id") Long id);

    // ===== 自定义查询（所有查询都排除已软删除的消息）=====

    /** 单聊历史消息（分页，倒序取 -> 前端再反转为正序显示） */
    @Select("select * from message where target_type = 'USER' " +
            "and deleted = 0 " +
            "and ((target_id = #{a} and sender_id = #{b}) or (target_id = #{b} and sender_id = #{a})) " +
            "order by create_time desc limit #{offset}, #{size}")
    List<Message> findUserMessages(@Param("a") Long a, @Param("b") Long b, @Param("offset") int offset, @Param("size") int size);

    /** 单聊最后一条（排除已删除） */
    @Select("select * from message where target_type = 'USER' " +
            "and deleted = 0 " +
            "and ((target_id = #{a} and sender_id = #{b}) or (target_id = #{b} and sender_id = #{a})) " +
            "order by create_time desc limit 1")
    Message findLastUserMessage(@Param("a") Long a, @Param("b") Long b);

    /** 群聊历史消息（分页，倒序取） */
    @Select("select * from message where target_type = 'GROUP' and target_id = #{groupId} " +
            "and deleted = 0 " +
            "order by create_time desc limit #{offset}, #{size}")
    List<Message> findGroupMessages(@Param("groupId") Long groupId, @Param("offset") int offset, @Param("size") int size);

    /** 群聊最后一条（排除已删除） */
    @Select("select * from message where target_type = 'GROUP' and target_id = #{groupId} " +
            "and deleted = 0 " +
            "order by create_time desc limit 1")
    Message findLastGroupMessage(@Param("groupId") Long groupId);

    /** 自建会话（SESSION）历史消息（分页，倒序取）。仅本人可见：sender_id = uid 且 target_type='SESSION' */
    @Select("select * from message where target_type = 'SESSION' and target_id = #{sessionId} " +
            "and sender_id = #{uid} and deleted = 0 " +
            "order by create_time desc limit #{offset}, #{size}")
    List<Message> findSessionMessages(@Param("uid") Long uid, @Param("sessionId") Long sessionId,
                                      @Param("offset") int offset, @Param("size") int size);

    /** 自建会话最后一条（排除已删除） */
    @Select("select * from message where target_type = 'SESSION' and target_id = #{sessionId} " +
            "and sender_id = #{uid} and deleted = 0 " +
            "order by create_time desc limit 1")
    Message findLastSessionMessage(@Param("uid") Long uid, @Param("sessionId") Long sessionId);

    /** 统计自建会话消息总数 */
    @Select("select count(*) from message where target_type = 'SESSION' and target_id = #{sessionId} " +
            "and sender_id = #{uid} and deleted = 0")
    int countSessionMessages(@Param("uid") Long uid, @Param("sessionId") Long sessionId);

    /** 自建会话断线补拉：返回 id 大于 afterId 的本会话消息（升序） */
    @Select("select * from message where target_type = 'SESSION' and target_id = #{sessionId} " +
            "and sender_id = #{uid} and deleted = 0 and id &gt; #{afterId} " +
            "order by id asc limit 200")
    List<Message> selectSessionAfter(@Param("uid") Long uid, @Param("sessionId") Long sessionId,
                                     @Param("afterId") Long afterId);

    /** 未读消息（单聊，按发送方查，排除已删除） */
    @Select("select * from message where target_type = #{targetType} and target_id = #{targetId} " +
            "and sender_id = #{senderId} and `read` = 0 and deleted = 0")
    List<Message> findUnread(@Param("targetType") String targetType,
                             @Param("targetId") Long targetId, @Param("senderId") Long senderId);

    /** 统计单聊消息总数（用于分页判断是否有更多历史） */
    @Select("select count(*) from message where target_type = 'USER' and deleted = 0 " +
            "and ((target_id = #{a} and sender_id = #{b}) or (target_id = #{b} and sender_id = #{a}))")
    int countUserMessages(@Param("a") Long a, @Param("b") Long b);

    /** 统计群聊消息总数 */
    @Select("select count(*) from message where target_type = 'GROUP' and target_id = #{groupId} and deleted = 0")
    int countGroupMessages(@Param("groupId") Long groupId);

    /** 管理端审计：查询聊天内容（排除已软删除），支持按会话类型/用户/群/关键字过滤，倒序分页 */
    @Select("<script>" +
            "SELECT * FROM message" +
            "<where>" +
            "  deleted = 0" +
            "  <choose>" +
            "    <when test=\"groupId != null\">" +
            "      AND target_type = 'GROUP' AND target_id = #{groupId}" +
            "    </when>" +
            "    <when test=\"peerId != null and userId != null\">" +
            "      AND target_type = 'USER' AND ((sender_id = #{userId} AND target_id = #{peerId}) OR (sender_id = #{peerId} AND target_id = #{userId}))" +
            "    </when>" +
            "    <when test=\"userId != null\">" +
            "      AND target_type = 'USER' AND (sender_id = #{userId} OR target_id = #{userId})" +
            "    </when>" +
            "    <when test=\"targetType != null\">" +
            "      AND target_type = #{targetType}" +
            "    </when>" +
            "  </choose>" +
            "  <if test=\"keyword != null and keyword != ''\">" +
            "    AND content LIKE concat('%', #{keyword}, '%')" +
            "  </if>" +
            "</where>" +
            "ORDER BY create_time DESC LIMIT #{offset}, #{size}" +
            "</script>")
    List<Message> adminFind(@Param("targetType") String targetType,
                            @Param("userId") Long userId,
                            @Param("peerId") Long peerId,
                            @Param("groupId") Long groupId,
                            @Param("keyword") String keyword,
                            @Param("offset") int offset,
                            @Param("size") int size);

    /** 管理端统计：未软删除的消息总数 */
    @Select("SELECT COUNT(*) FROM message WHERE deleted = 0")
    int countNormal();

    /**
     * 用户态聊天记录搜索：仅搜 TEXT 类型（图片/语音内容是其资源 URL，不参与文字匹配，与微信一致），
     * 排除已软删除/已撤回；结果严格限定在「当前用户参与的会话」内——
     * 单聊需是收发双方之一，群聊要求当前用户是群成员（EXISTS 校验，防止越权查看他人群消息）。
     * 支持按指定会话（targetType+targetId）缩小范围，或为空时搜「全部我的会话」。
     */
    @Select("<script>" +
            "SELECT * FROM message" +
            "<where>" +
            "  deleted = 0 AND recalled = 0 AND type = 'TEXT'" +
            "  <choose>" +
            "    <when test=\"targetType != null and targetType == 'USER' and targetId != null\">" +
            "      AND target_type = 'USER' AND ((sender_id = #{uid} AND target_id = #{targetId}) OR (sender_id = #{targetId} AND target_id = #{uid}))" +
            "    </when>" +
            "    <when test=\"targetType != null and targetType == 'GROUP' and targetId != null\">" +
            "      AND target_type = 'GROUP' AND target_id = #{targetId}" +
            "      AND EXISTS(SELECT 1 FROM group_member gm WHERE gm.group_id = message.target_id AND gm.user_id = #{uid})" +
            "    </when>" +
            "    <otherwise>" +
            "      AND ((target_type = 'USER' AND (sender_id = #{uid} OR target_id = #{uid}))" +
            "           OR (target_type = 'GROUP' AND EXISTS(SELECT 1 FROM group_member gm WHERE gm.group_id = message.target_id AND gm.user_id = #{uid})))" +
            "    </otherwise>" +
            "  </choose>" +
            "  <if test=\"keyword != null and keyword != ''\">AND content LIKE concat('%', #{keyword}, '%')</if>" +
            "</where>" +
            "ORDER BY create_time DESC LIMIT #{offset}, #{size}" +
            "</script>")
    List<Message> searchMyMessages(@Param("uid") Long uid,
                                   @Param("keyword") String keyword,
                                   @Param("targetType") String targetType,
                                   @Param("targetId") Long targetId,
                                   @Param("offset") int offset,
                                   @Param("size") int size);

    /**
     * 断线重连后「补拉缺失消息」：返回 id 大于 afterId 的本会话消息（按 id 升序，最多 200 条）。
     * id 为自增主键，可近似代表「断线之后新到达」的消息；前端重连后用已渲染的最大 id 作 afterId，
     * 即可把服务端未缓冲而漏推的消息补齐，无需整页刷新。好友/群成员鉴权由 Controller 统一把关。
     */
    @Select("<script>" +
            "SELECT * FROM message" +
            "<where>" +
            "  deleted = 0 AND id &gt; #{afterId}" +
            "  <choose>" +
            "    <when test=\"targetType == 'USER'\">" +
            "      AND target_type = 'USER' AND ((sender_id = #{uid} AND target_id = #{targetId}) OR (sender_id = #{targetId} AND target_id = #{uid}))" +
            "    </when>" +
            "    <when test=\"targetType == 'GROUP'\">" +
            "      AND target_type = 'GROUP' AND target_id = #{targetId}" +
            "      AND EXISTS(SELECT 1 FROM group_member gm WHERE gm.group_id = message.target_id AND gm.user_id = #{uid})" +
            "    </when>" +
            "  </choose>" +
            "</where>" +
            "ORDER BY id ASC LIMIT 200" +
            "</script>")
    List<Message> selectAfter(@Param("uid") Long uid,
                              @Param("targetType") String targetType,
                              @Param("targetId") Long targetId,
                              @Param("afterId") Long afterId);

    /** ⑩ 消息炸弹：查找“由 fromId 发给 toId 且仍在倒计时中”的最近一条炸弹（toId 回复任何消息时用于拆弹） */
    @Select("SELECT * FROM message WHERE sender_id = #{fromId} AND target_type = 'USER' AND target_id = #{toId} " +
            "AND bomb_status = 'PENDING' AND deleted = 0 ORDER BY create_time DESC LIMIT 1")
    Message findPendingBombFromTo(@Param("fromId") Long fromId, @Param("toId") Long toId);

    /** ⑩ 消息炸弹：定时任务扰描已过期仍在倒计时中的炸弹（待引爆） */
    @Select("SELECT * FROM message WHERE bomb_status = 'PENDING' AND bomb_deadline <= #{now} LIMIT 200")
    List<Message> findExpiredPendingBombs(@Param("now") java.time.LocalDateTime now);
}
