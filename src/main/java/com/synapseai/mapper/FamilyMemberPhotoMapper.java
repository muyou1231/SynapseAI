package com.synapseai.mapper;

import com.synapseai.entity.FamilyMemberPhoto;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** 成员相册数据访问 */
@Mapper
public interface FamilyMemberPhotoMapper {

    @Insert("INSERT INTO family_member_photo(member_id, url, caption, sort_no, create_time) " +
            "VALUES(#{memberId}, #{url}, #{caption}, #{sortNo}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FamilyMemberPhoto photo);

    @Select("SELECT * FROM family_member_photo WHERE id = #{id} AND deleted = 0")
    FamilyMemberPhoto selectById(@Param("id") Long id);

    /** 成员相册：按排序号升序、同序号按时间升序 */
    @Select("SELECT * FROM family_member_photo WHERE member_id = #{memberId} AND deleted = 0 " +
            "ORDER BY sort_no ASC, id ASC")
    List<FamilyMemberPhoto> listByMember(@Param("memberId") Long memberId);

    @Update("UPDATE family_member_photo SET deleted = 1 WHERE id = #{id} AND deleted = 0")
    int softDelete(@Param("id") Long id);

    /** 删除成员时级联清理相册 */
    @Update("UPDATE family_member_photo SET deleted = 1 WHERE member_id = #{memberId} AND deleted = 0")
    int softDeleteByMember(@Param("memberId") Long memberId);

    /** 取当前最大排序号，新增图片时追加到末尾 */
    @Select("SELECT IFNULL(MAX(sort_no), 0) FROM family_member_photo WHERE member_id = #{memberId} AND deleted = 0")
    int maxSortNo(@Param("memberId") Long memberId);

    @Select("SELECT COUNT(*) FROM family_member_photo WHERE member_id = #{memberId} AND deleted = 0")
    int countByMember(@Param("memberId") Long memberId);
}
