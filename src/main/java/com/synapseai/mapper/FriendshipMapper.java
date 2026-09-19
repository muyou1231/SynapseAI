package com.synapseai.mapper;

import com.synapseai.entity.Friendship;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface FriendshipMapper {

    // ===== 通用 CRUD（显式写出，不依赖 BaseMapper 继承）=====

    @Insert("INSERT INTO friendship(user_id, friend_id, status, remark, create_time) " +
            "VALUES(#{userId}, #{friendId}, #{status}, #{remark}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Friendship friendship);

    @Select("SELECT * FROM friendship WHERE id = #{id}")
    Friendship selectById(Long id);

    @Update("UPDATE friendship SET user_id = #{userId}, friend_id = #{friendId}, " +
            "status = #{status}, remark = #{remark}, create_time = #{createTime} WHERE id = #{id}")
    int updateById(Friendship friendship);

    @Delete("DELETE FROM friendship WHERE id = #{id}")
    int deleteById(Long id);

    // ===== 自定义查询 =====

    @Select("select * from friendship where user_id = #{userId} and status = #{status}")
    List<Friendship> findByUserIdAndStatus(@Param("userId") Long userId, @Param("status") String status);

    @Select("select * from friendship where friend_id = #{friendId} and status = #{status}")
    List<Friendship> findByFriendIdAndStatus(@Param("friendId") Long friendId, @Param("status") String status);

    @Select("select * from friendship where user_id = #{userId} and friend_id = #{friendId}")
    Friendship findByUserIdAndFriendId(@Param("userId") Long userId, @Param("friendId") Long friendId);

    @Select("<script>" +
            "select * from friendship where user_id = #{userId} and status in " +
            "<foreach collection='statuses' item='s' open='(' separator=',' close=')'>#{s}</foreach>" +
            "</script>")
    List<Friendship> findByUserIdAndStatusIn(@Param("userId") Long userId, @Param("statuses") List<String> statuses);

    @Select("select exists(select 1 from friendship where user_id = #{userId} and friend_id = #{friendId})")
    boolean existsByUserIdAndFriendId(@Param("userId") Long userId, @Param("friendId") Long friendId);

    @Select("select exists(select 1 from friendship where user_id = #{userId} and friend_id = #{friendId} and status = #{status})")
    boolean existsByUserIdAndFriendIdAndStatus(@Param("userId") Long userId, @Param("friendId") Long friendId, @Param("status") String status);

    @Select("select * from friendship where status = 'ACCEPTED' and (user_id = #{uid} or friend_id = #{uid})")
    List<Friendship> findAcceptedByUser(@Param("uid") Long uid);

    @Delete("delete from friendship where user_id = #{userId} and friend_id = #{friendId}")
    void deleteByUserIdAndFriendId(@Param("userId") Long userId, @Param("friendId") Long friendId);

    @Select("SELECT COUNT(*) FROM friendship")
    int count();

    /** 保存好友备注（仅更新 remark 列，不影响其它字段） */
    @Update("UPDATE friendship SET remark = #{remark} WHERE user_id = #{userId} AND friend_id = #{friendId}")
    int updateRemark(@Param("userId") Long userId, @Param("friendId") Long friendId, @Param("remark") String remark);

    /** 在「我(uid)已添加的用户」中按 备注 / 账号 / 用户名 模糊检索，返回命中的好友 userId 列表。
     *  仅搜索 ACCEPTED/BLOCKED（即我自己添加/拉黑的），不搜待处理申请，也不泄露陌生人。 */
    @Select("<script>" +
            "SELECT f.friend_id FROM friendship f " +
            "JOIN user u ON u.id = f.friend_id " +
            "WHERE f.user_id = #{uid} AND f.status IN ('ACCEPTED','BLOCKED') " +
            "AND (f.remark LIKE concat('%',#{kw},'%') OR u.account LIKE concat('%',#{kw},'%') OR u.username LIKE concat('%',#{kw},'%'))" +
            "<if test='limit != null'> LIMIT #{limit}</if>" +
            "</script>")
    List<Long> searchMyFriendIds(@Param("uid") Long uid, @Param("kw") String kw, @Param("limit") Integer limit);
}
