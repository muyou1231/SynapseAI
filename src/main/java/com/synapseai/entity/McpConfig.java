package com.synapseai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * MCP 功能配置表：管理端可在此增删改查并启停各项 MCP 功能。
 * code 为功能唯一编码（如 message_sender），前端 /api/mcp/functions 只返回 enabled=1 的功能，
 * 据此决定界面上展示哪些「MCP 功能」入口。
 */
@TableName("mcp_config")
public class McpConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 功能唯一编码，如 message_sender */
    private String code;

    /** 功能显示名 */
    private String name;

    /** 功能简介 */
    private String description;

    /** 是否启用：0=停用 1=启用 */
    private Boolean enabled = false;

    /** JSON 配置（各功能自定义，如 message_sender 的默认文案/限制等） */
    private String configJson;

    /** 排序（升序，越小越靠前） */
    private Integer sort = 0;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
