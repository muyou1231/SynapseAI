package com.synapseai.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReviewConfigMapper {

    /** 读取全局审核模式（AUTO=自动审核 / MANUAL=人工审核），单行 id=1 */
    @Select("SELECT mode FROM review_config WHERE id = 1")
    String getMode();

    /** 更新全局审核模式 */
    @Update("UPDATE review_config SET mode = #{mode} WHERE id = 1")
    int setMode(@Param("mode") String mode);
}
