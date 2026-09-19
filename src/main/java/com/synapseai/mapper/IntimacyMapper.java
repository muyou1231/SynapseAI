package com.synapseai.mapper;

import com.synapseai.entity.Intimacy;
import org.apache.ibatis.annotations.*;

@Mapper
public interface IntimacyMapper {

    @Insert("INSERT INTO intimacy(user_a, user_b, exp, level) VALUES(#{userA}, #{userB}, #{exp}, #{level})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Intimacy intimacy);

    /** 调用方须保证 a &lt; b（服务层统一排序后再查），避免同一对好友存两条反向记录 */
    @Select("SELECT * FROM intimacy WHERE user_a = #{a} AND user_b = #{b}")
    Intimacy findByPair(@Param("a") Long a, @Param("b") Long b);

    @Update("UPDATE intimacy SET exp = #{exp}, level = #{level} WHERE user_a = #{userA} AND user_b = #{userB}")
    int updateByPair(Intimacy intimacy);
}
