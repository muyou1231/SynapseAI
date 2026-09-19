package com.synapseai.mapper;

import com.synapseai.entity.Notice;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface NoticeMapper {

    @Insert("INSERT INTO notice(title, content, sender_id, target_type, target_ids, duration_minutes, expire_at, category, read_wait_seconds, ref_id) " +
            "VALUES(#{title}, #{content}, #{senderId}, #{targetType}, #{targetIds}, #{durationMinutes}, #{expireAt}, #{category}, #{readWaitSeconds}, #{refId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Notice notice);

    @Select("SELECT * FROM notice WHERE id = #{id}")
    Notice selectById(@Param("id") Long id);

    @Delete("DELETE FROM notice WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /** 撤销通知时，一并清理该通知的所有已读记录 */
    @Delete("DELETE FROM notice_read WHERE notice_id = #{id}")
    int deleteReadByNoticeId(@Param("id") Long id);

    /** 管理端：全部通知（按发布时间倒序） */
    @Select("SELECT * FROM notice ORDER BY create_time DESC")
    List<Notice> selectAll();

    /** 管理端通知管理页：仅显示管理员发布(AADMIN)的通知，系统自动生成的用户通知(COMMENT)不在此列 */
    @Select("SELECT * FROM notice WHERE category = 'ADMIN' ORDER BY create_time DESC")
    List<Notice> selectAdminList();

    /** 用户端通知中心：面向该用户的全部通知（含已过期），按发布时间倒序。
     *  注意：通知永久保留，用户可随时查看历史；是否弹窗由 selectPending 的窗口约束决定。 */
    @Select("<script>" +
            "SELECT * FROM notice " +
            "WHERE (target_type = 'ALL' OR (target_type = 'SPECIFIED' AND FIND_IN_SET(#{uid}, target_ids))) " +
            "ORDER BY create_time DESC" +
            "</script>")
    List<Notice> selectForUser(@Param("uid") Long uid);

    /** 待弹窗：未过期、面向该用户、且未被该用户读过 的通知 */
    @Select("<script>" +
            "SELECT * FROM notice WHERE (expire_at IS NULL OR expire_at &gt; NOW()) " +
            "AND (target_type = 'ALL' OR (target_type = 'SPECIFIED' AND FIND_IN_SET(#{uid}, target_ids))) " +
            "AND id NOT IN (SELECT notice_id FROM notice_read WHERE user_id = #{uid}) " +
            "ORDER BY create_time DESC" +
            "</script>")
    List<Notice> selectPending(@Param("uid") Long uid);

    /** 某用户已读的通知 id 列表 */
    @Select("SELECT notice_id FROM notice_read WHERE user_id = #{userId}")
    List<Long> selectReadNoticeIds(@Param("userId") Long userId);

    /** 标记已读（忽略重复，唯一键冲突不报错） */
    @Insert("INSERT IGNORE INTO notice_read(user_id, notice_id) VALUES(#{userId}, #{noticeId})")
    int insertRead(@Param("userId") Long userId, @Param("noticeId") Long noticeId);
}
