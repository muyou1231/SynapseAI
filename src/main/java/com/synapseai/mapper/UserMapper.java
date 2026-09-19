package com.synapseai.mapper;

import com.synapseai.entity.User;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface UserMapper {

    // ===== 通用 CRUD（显式写出，不依赖 BaseMapper 继承）=====

    @Insert("INSERT INTO user(username, nickname, password, account, avatar, email, role, create_time) " +
            "VALUES(#{username}, #{nickname}, #{password}, #{account}, #{avatar}, #{email}, #{role}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    @Select("SELECT * FROM user WHERE id = #{id}")
    User selectById(Long id);

    @Update("UPDATE user SET username = #{username}, nickname = #{nickname}, " +
            "password = #{password}, account = #{account}, avatar = #{avatar}, " +
            "create_time = #{createTime} WHERE id = #{id}")
    int updateById(User user);

    @Delete("DELETE FROM user WHERE id = #{id}")
    int deleteById(Long id);

    /** 只更新资料相关字段（不触碰密码/账号/用户名/注册时间），用于「更新个人信息」 */
    @Update("UPDATE user SET nickname = #{nickname}, avatar = #{avatar}, " +
            "gender = #{gender}, age = #{age}, birthday = #{birthday}, " +
            "religion = #{religion}, education = #{education}, " +
            "signature = #{signature}, location = #{location}, hobbies = #{hobbies}, " +
            "email = #{email} " +
            "WHERE id = #{id}")
    int updateProfile(User user);

    // ===== 自定义查询 =====

    @Select("select * from user where username = #{username}")
    User findByUsername(@Param("username") String username);

    @Select("select exists(select 1 from user where username = #{username})")
    boolean existsByUsername(@Param("username") String username);

    @Select("select exists(select 1 from user where account = #{account})")
    boolean existsByAccount(@Param("account") String account);

    @Select("select * from user where account = #{account}")
    User findByAccount(@Param("account") String account);

    /** 仅更新在线状态（登录置 true / 退出置 false），不触碰其它字段 */
    @Update("UPDATE user SET online = #{online} WHERE id = #{id}")
    int updateOnline(@Param("id") Long id, @Param("online") Boolean online);

    @Select("<script>" +
            "select * from user where username like concat('%',#{username},'%') " +
            "or nickname like concat('%',#{nickname},'%') " +
            "or account like concat('%',#{account},'%')" +
            "</script>")
    List<User> findByUsernameContainingOrNicknameContainingOrAccountContaining(
            @Param("username") String username, @Param("nickname") String nickname, @Param("account") String account);

    /** 全部用户（按注册时间倒序），供管理端用户列表使用 */
    @Select("SELECT * FROM user ORDER BY create_time DESC")
    List<User> selectAll();

    /** 按角色查询用户（如 role='AI' 列出全部 AI 助手账户） */
    @Select("SELECT * FROM user WHERE role = #{role} ORDER BY create_time DESC")
    List<User> findByRole(@Param("role") String role);

    /** 统计指定角色的用户数量（如是否已存在 AI 助手账户） */
    @Select("SELECT COUNT(*) FROM user WHERE role = #{role}")
    int countByRole(@Param("role") String role);

    /** 仅更新昵称（AI 助手展示名与 user 表同步时使用，不触碰密码/账号等） */
    @Update("UPDATE user SET nickname = #{nickname}, signature = #{signature} WHERE id = #{id}")
    int updateNicknameAndSignature(@Param("id") Long id, @Param("nickname") String nickname, @Param("signature") String signature);

    /** 仅更新头像（AI 助手头像与 user 表同步时使用） */
    @Update("UPDATE user SET avatar = #{avatar} WHERE id = #{id}")
    int updateAvatar(@Param("id") Long id, @Param("avatar") String avatar);

    /** 仅更新账号（AI 助手账户账号，管理员可手填/生成，长度 >=10 且唯一） */
    @Update("UPDATE user SET account = #{account} WHERE id = #{id}")
    int updateAccount(@Param("id") Long id, @Param("account") String account);

    /** 全部用户 id（用于启动时给存量用户批量添加助手好友） */
    @Select("SELECT id FROM user")
    List<Long> selectAllIds();

    /** 按绑定邮箱查询单个用户（用于邮箱验证码登录等单值场景） */
    @Select("SELECT * FROM user WHERE email = #{email} LIMIT 1")
    User findByEmail(@Param("email") String email);

    /** 按绑定邮箱查询全部账号（一个邮箱最多 3 个账号，登录时需按密码匹配定位具体账号） */
    @Select("SELECT * FROM user WHERE email = #{email}")
    List<User> findByEmailList(@Param("email") String email);

    /** 统计某邮箱已注册账号数（用于「一个邮箱最多 3 个账号」限制） */
    @Select("SELECT COUNT(*) FROM user WHERE email = #{email}")
    int countByEmail(@Param("email") String email);

    /** 仅更新冻结状态（冻结/解冻账号） */
    @Update("UPDATE user SET frozen = #{frozen} WHERE id = #{id}")
    int updateFrozen(@Param("id") Long id, @Param("frozen") Boolean frozen);

    /** 仅更新角色（管理员初始化/角色调整时使用） */
    @Update("UPDATE user SET role = #{role} WHERE id = #{id}")
    int updateRole(@Param("id") Long id, @Param("role") String role);

    @Select("SELECT COUNT(*) FROM user")
    int count();

    @Select("SELECT COUNT(*) FROM user WHERE online = 1")
    int countOnline();

    @Select("SELECT COUNT(*) FROM user WHERE frozen = 1")
    int countFrozen();

    /** 仅更新密码（重置/改密场景，不触碰其它字段） */
    @Update("UPDATE user SET password = #{password} WHERE id = #{id}")
    int updatePasswordById(@Param("id") Long id, @Param("password") String password);

    /** 仅更新绑定邮箱（绑定 / 换绑场景，不触碰其它字段） */
    @Update("UPDATE user SET email = #{email} WHERE id = #{id}")
    int updateEmailById(@Param("id") Long id, @Param("email") String email);
}
