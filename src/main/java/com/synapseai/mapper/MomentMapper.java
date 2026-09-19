package com.synapseai.mapper;

import com.synapseai.entity.Moment;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MomentMapper {

    /**
     * 发布动态。逐条可见范围(visibility)由前端传入：PUBLIC/FRIENDS/PRIVATE/PARTIAL。
     */
    @Insert("INSERT INTO moment(user_id, content, images, visibility, allow_list, deny_list, expire_time, status, ai_review, ai_suggestion, manual_review) " +
            "VALUES(#{userId}, #{content}, #{images}, #{visibility}, #{allowList}, #{denyList}, NULL, #{status}, #{aiReview}, #{aiSuggestion}, #{manualReview})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Moment moment);

    /**
     * 信息流候选集：本人 + 好友发布的所有动态。
     * 细粒度可见性过滤（全局设置的 visibility/allowList/denyList/expireTime）
     * 在 Service 层对每条动态按作者的全局设置判定。
     */
    @Select("<script>" +
            "SELECT * FROM moment WHERE user_id IN " +
            "<foreach collection='authorIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "ORDER BY create_time DESC LIMIT #{limit}" +
            "</script>")
    List<Moment> selectFeed(@Param("selfId") Long selfId,
                            @Param("authorIds") List<Long> authorIds,
                            @Param("limit") int limit);

    /** 广场（公开信息流）：所有用户发布的动态，按时间倒序；附带评论数、点赞数（子查询，避免 N+1） */
    @Select("SELECT m.*, (SELECT COUNT(*) FROM moment_comment c WHERE c.moment_id = m.id) AS comment_count, " +
            "(SELECT COUNT(*) FROM moment_like l WHERE l.moment_id = m.id) AS like_count " +
            "FROM moment m ORDER BY m.create_time DESC LIMIT #{limit}")
    List<Moment> selectPublicFeed(@Param("limit") int limit);

    @Select("SELECT m.*, (SELECT COUNT(*) FROM moment_comment c WHERE c.moment_id = m.id) AS comment_count, " +
            "(SELECT COUNT(*) FROM moment_like l WHERE l.moment_id = m.id) AS like_count " +
            "FROM moment m WHERE m.user_id = #{userId} ORDER BY m.create_time DESC LIMIT #{limit}")
    List<Moment> selectByUser(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("SELECT * FROM moment WHERE id = #{id}")
    Moment selectById(@Param("id") Long id);

    @Delete("DELETE FROM moment WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /** 修改单条动态的可见范围（仅更新权限相关列，不动 content/images/expireTime） */
    @Update("UPDATE moment SET visibility = #{visibility}, allow_list = #{allowList}, deny_list = #{denyList} " +
            "WHERE id = #{id}")
    int updatePermission(@Param("id") Long id,
                         @Param("visibility") String visibility,
                         @Param("allowList") String allowList,
                         @Param("denyList") String denyList);

    /** 更新动态状态（打回/撤回打回/审核通过）：status + reject_reason */
    @Update("UPDATE moment SET status = #{status}, reject_reason = #{rejectReason} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("rejectReason") String rejectReason);

    /** 重新发布（被打回后修改再发）：更新内容/图片/可见范围，并重置审核状态（status + 清空 reject_reason）。仅作者本人。 */
    @Update("UPDATE moment SET content = #{content}, images = #{images}, visibility = #{visibility}, " +
            "allow_list = #{allowList}, deny_list = #{denyList}, status = #{status}, reject_reason = #{rejectReason} " +
            "WHERE id = #{id} AND user_id = #{userId}")
    int updateContent(@Param("id") Long id,
                      @Param("userId") Long userId,
                      @Param("content") String content,
                      @Param("images") String images,
                      @Param("visibility") String visibility,
                      @Param("allowList") String allowList,
                      @Param("denyList") String denyList,
                      @Param("status") String status,
                      @Param("rejectReason") String rejectReason);

    /** 审核队列：待审核（PENDING，含 AI 审核不通过待决策）与用户已申请人工复审（MANUAL_REVIEWING）的动态，按时间倒序 */
    @Select("SELECT * FROM moment WHERE status = 'PENDING' OR status = 'MANUAL_REVIEWING' ORDER BY create_time DESC")
    List<Moment> selectPending();

    /** AI 审核内容：所有经过 AI 判定的动态（ai_review 非空），含 PASS/NORMAL 与 FAIL/PENDING，按时间倒序 */
    @Select("SELECT * FROM moment WHERE ai_review IS NOT NULL ORDER BY create_time DESC")
    List<Moment> selectAiReviewed();

    /** AI 打回内容：AI 判定不通过（ai_review='FAIL'）的动态，按时间倒序 */
    @Select("SELECT * FROM moment WHERE ai_review = 'FAIL' ORDER BY create_time DESC")
    List<Moment> selectAiFail();

    /** 管理端打回后：清空 AI 审核结论，使该条从「全部 AI 审核内容 / AI 打回」列表移除（转为人工 PENDING 队列） */
    @Update("UPDATE moment SET ai_review = NULL, ai_suggestion = NULL WHERE id = #{id}")
    int clearAiReview(@Param("id") Long id);

    /** 人工打回列表：仅人工打回的内容（status=REJECTED 且 manual_review=1），按时间倒序。
     *  用于「打回列表」tab，只可查看/删除，不可重复打回。 */
    @Select("SELECT * FROM moment WHERE status = 'REJECTED' AND manual_review = 1 ORDER BY create_time DESC")
    List<Moment> selectManualRejected();

    /** 写入 AI 审核结论与建议（AI 审核模式发布/重新发布时调用） */
    @Update("UPDATE moment SET ai_review = #{aiReview}, ai_suggestion = #{aiSuggestion}, status = #{status} WHERE id = #{id}")
    int updateAiReview(@Param("id") Long id,
                       @Param("aiReview") String aiReview,
                       @Param("aiSuggestion") String aiSuggestion,
                       @Param("status") String status);

    /** 用户申请人工复审：置 manual_review=1 且 status=MANUAL_REVIEWING，进入「人工审核中」状态等待管理员复核。
     *  允许原状态为 PENDING（AI 不通过待用户决策）或 REJECTED（被管理员打回）时申请，避免重复提交。 */
    @Update("UPDATE moment SET manual_review = 1, status = 'MANUAL_REVIEWING' " +
            "WHERE id = #{id} AND user_id = #{userId} AND (status = 'PENDING' OR status = 'REJECTED')")
    int applyManualReview(@Param("id") Long id, @Param("userId") Long userId);

    /** 管理端：全部朋友圈动态（按创建时间倒序） */
    @Select("SELECT * FROM moment ORDER BY create_time DESC")
    List<Moment> selectAll();

    @Select("SELECT COUNT(*) FROM moment")
    int count();
}
