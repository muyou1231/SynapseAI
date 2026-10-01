package com.synapseai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 家族创建 / 编辑请求体。
 * <p>
 * 创建时 id 为空；编辑时 id 必填，并需带上 {@code version} 做乐观锁。
 */
public class FamilySaveReq {

    /** 编辑时必填；新增时为空 */
    private Long id;

    @NotBlank(message = "家族名称不能为空")
    @Size(max = 64, message = "家族名称不能超过 64 个字符")
    private String name;

    @Size(max = 500, message = "家族简介不能超过 500 个字符")
    private String intro;

    /** 封面图 URL（由 /api/file/upload 上传后回传） */
    @Size(max = 512, message = "封面图地址过长")
    private String coverUrl;

    /** 可见性：0=私有 1=链接只读 2=公开 */
    @NotNull(message = "可见性不能为空")
    private Integer visibility = 0;

    /** 乐观锁版本号（编辑时必填） */
    private Integer version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIntro() {
        return intro;
    }

    public void setIntro(String intro) {
        this.intro = intro;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public Integer getVisibility() {
        return visibility;
    }

    public void setVisibility(Integer visibility) {
        this.visibility = visibility;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
