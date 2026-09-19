package com.synapseai.mapper;

import com.synapseai.entity.McpConfig;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MCP 功能配置表 Mapper（显式 SQL，不继承 BaseMapper，与项目其它 Mapper 保持一致）。
 */
@Mapper
public interface McpConfigMapper {

    @Insert("INSERT INTO mcp_config(code, name, description, enabled, config_json, sort, create_time, update_time) " +
            "VALUES(#{code}, #{name}, #{description}, #{enabled}, #{configJson}, #{sort}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(McpConfig c);

    @Select("SELECT * FROM mcp_config WHERE code = #{code}")
    McpConfig selectByCode(@Param("code") String code);

    @Select("SELECT * FROM mcp_config WHERE id = #{id}")
    McpConfig selectById(@Param("id") Long id);

    @Select("SELECT * FROM mcp_config ORDER BY sort ASC, id ASC")
    List<McpConfig> selectAll();

    @Select("SELECT * FROM mcp_config WHERE enabled = 1 ORDER BY sort ASC, id ASC")
    List<McpConfig> selectEnabled();

    @Update("UPDATE mcp_config SET name = #{name}, description = #{description}, enabled = #{enabled}, " +
            "config_json = #{configJson}, sort = #{sort}, update_time = #{updateTime} WHERE id = #{id}")
    int updateById(McpConfig c);

    @Update("UPDATE mcp_config SET enabled = #{enabled}, update_time = #{updateTime} WHERE id = #{id}")
    int updateEnabled(@Param("id") Long id, @Param("enabled") Boolean enabled, @Param("updateTime") LocalDateTime updateTime);

    @Delete("DELETE FROM mcp_config WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
