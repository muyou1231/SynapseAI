package com.synapseai.mapper;

import com.synapseai.entity.AiAssistant;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AiAssistantMapper {

    /** 查询全部 AI 助手配置（按 id 升序，id=1 为默认助手） */
    @Select("SELECT * FROM ai_assistant ORDER BY id ASC")
    List<AiAssistant> selectAll();

    /** 按 id 查询单个助手配置 */
    @Select("SELECT * FROM ai_assistant WHERE id = #{id}")
    AiAssistant selectById(@Param("id") Integer id);

    /** 按关联 user id 查询（判断某 user 是否为 AI 助手账户） */
    @Select("SELECT * FROM ai_assistant WHERE user_id = #{userId}")
    AiAssistant selectByUserId(@Param("userId") Long userId);

    /** 查询当前默认助手（is_default=1）；无则取 id 最小者兜底（兼容历史数据） */
    @Select("SELECT * FROM ai_assistant WHERE is_default = 1 LIMIT 1")
    AiAssistant selectDefault();

    /** 将所有助手重置为「非默认」 */
    @Update("UPDATE ai_assistant SET is_default = 0 WHERE is_default = 1")
    int clearDefaults();

    /** 将指定 id 设为默认助手 */
    @Update("UPDATE ai_assistant SET is_default = 1 WHERE id = #{id}")
    int setDefault(@Param("id") Integer id);

    /** 查询除指定 id 外 id 最小的助手（用于删除默认后自动切换） */
    @Select("SELECT * FROM ai_assistant WHERE id <> #{excludeId} ORDER BY id ASC LIMIT 1")
    AiAssistant selectFirstExcept(@Param("excludeId") Integer excludeId);

    /** 查询 id 最小的助手（无默认时兜底） */
    @Select("SELECT * FROM ai_assistant ORDER BY id ASC LIMIT 1")
    AiAssistant selectFirst();

    /** 新增助手配置（管理员新建 AI 助手时写入） */
    @Insert("INSERT INTO ai_assistant(user_id, name, avatar, enabled, status, is_default, prompt, signature, create_time) " +
            "VALUES(#{userId}, #{name}, #{avatar}, #{enabled}, #{status}, #{isDefault}, #{prompt}, #{signature}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AiAssistant cfg);

    /** 更新助手配置（管理员调试用：启用/禁用、状态、人设、展示名、头像、签名、默认标记） */
    @Update("<script>" +
            "UPDATE ai_assistant SET " +
            "<if test='name != null'>name = #{name}, </if>" +
            "<if test='avatar != null'>avatar = #{avatar}, </if>" +
            "<if test='enabled != null'>enabled = #{enabled}, </if>" +
            "<if test='status != null'>status = #{status}, </if>" +
            "<if test='isDefault != null'>is_default = #{isDefault}, </if>" +
            "<if test='prompt != null'>prompt = #{prompt}, </if>" +
            "<if test='signature != null'>signature = #{signature}, </if>" +
            "id = id WHERE id = #{id}" +
            "</script>")
    int updateConfig(AiAssistant cfg);

    /** 删除助手配置（管理员删除 AI 助手时） */
    @Delete("DELETE FROM ai_assistant WHERE id = #{id}")
    int deleteById(@Param("id") Integer id);
}
