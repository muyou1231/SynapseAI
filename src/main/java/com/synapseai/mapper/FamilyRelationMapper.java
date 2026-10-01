package com.synapseai.mapper;

import com.synapseai.entity.FamilyRelation;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 亲属关系数据访问。
 * <p>
 * 语义：(member_a_id, member_b_id, relation_type) = 「a 是 b 的 relation_type」。
 */
@Mapper
public interface FamilyRelationMapper {

    @Insert("INSERT INTO family_relation(family_id, member_a_id, member_b_id, relation_type, relation_desc, create_time) " +
            "VALUES(#{familyId}, #{memberAId}, #{memberBId}, #{relationType}, #{relationDesc}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FamilyRelation relation);

    /** 某成员参与的全部关系（作为 a 或 b 都要查出来） */
    @Select("SELECT * FROM family_relation WHERE (member_a_id = #{memberId} OR member_b_id = #{memberId}) " +
            "ORDER BY id ASC")
    List<FamilyRelation> listByMember(@Param("memberId") Long memberId);

    /** 家族内全部关系，树构建时一次性拉取（500 人家族约千余行，内存构图足够） */
    @Select("SELECT * FROM family_relation WHERE family_id = #{familyId} ORDER BY id ASC")
    List<FamilyRelation> listByFamily(@Param("familyId") Long familyId);

    /** 精确查询两成员间的某类关系（避免重复插入，唯一索引兜底前的业务判断） */
    @Select("SELECT COUNT(*) FROM family_relation WHERE member_a_id = #{aId} AND member_b_id = #{bId} " +
            "AND relation_type = #{type}")
    int countRelation(@Param("aId") Long aId, @Param("bId") Long bId, @Param("type") String type);

    /** 变更关系类型（如 SPOUSE → EX_SPOUSE 标记离异），双向同时改 */
    @Update("UPDATE family_relation SET relation_type = #{newType} WHERE member_a_id = #{aId} " +
            "AND member_b_id = #{bId} AND relation_type = #{oldType}")
    int updateType(@Param("aId") Long aId, @Param("bId") Long bId,
                   @Param("oldType") String oldType, @Param("newType") String newType);

    /** 解除关系：删除两人之间的全部关系行（夫妻双向两条、父母子女反向两条都会清掉） */
    @Delete("DELETE FROM family_relation WHERE (member_a_id = #{aId} AND member_b_id = #{bId}) " +
            "OR (member_a_id = #{bId} AND member_b_id = #{aId})")
    int deleteBetween(@Param("aId") Long aId, @Param("bId") Long bId);

    /** 删除成员时级联清理其全部关系 */
    @Delete("DELETE FROM family_relation WHERE member_a_id = #{memberId} OR member_b_id = #{memberId}")
    int deleteByMember(@Param("memberId") Long memberId);

    /** 删除家族时级联清理 */
    @Delete("DELETE FROM family_relation WHERE family_id = #{familyId}")
    int deleteByFamily(@Param("familyId") Long familyId);

    /**
     * 递归查询某成员的全部后代 id（含自己）。
     * <p>
     * <b>MySQL 8.0+</b> 可用 WITH RECURSIVE；MySQL 5.7 执行会抛 SQL 语法错误，
     * 由 {@link com.synapseai.service.FamilyService} 捕获后降级为 Java 内存 BFS。
     * <p>
     * 递归方向：从自己出发，沿「我是对方的父母」这条边向下走：
     * (a,b,FATHER/MOTHER/STEP_*) 表示 a 是 b 的父母，因此 b 是 a 的后代。
     */
    @Select("WITH RECURSIVE sub(id) AS ( " +
            "  SELECT #{memberId} " +
            "  UNION ALL " +
            "  SELECT r.member_b_id FROM family_relation r JOIN sub s ON r.member_a_id = s.id " +
            "  WHERE r.relation_type IN ('FATHER','MOTHER','STEP_FATHER','STEP_MOTHER') " +
            ") SELECT id FROM sub")
    List<Long> findDescendantIds(@Param("memberId") Long memberId);

    /**
     * 递归查询某成员的全部祖先 id（含自己），用于防环校验：
     * 若「待设为父母的目标人」已出现在「自己」的后代集合中，则该操作会成环。
     */
    @Select("WITH RECURSIVE anc(id) AS ( " +
            "  SELECT #{memberId} " +
            "  UNION ALL " +
            "  SELECT r.member_a_id FROM family_relation r JOIN anc a ON r.member_b_id = a.id " +
            "  WHERE r.relation_type IN ('FATHER','MOTHER','STEP_FATHER','STEP_MOTHER') " +
            ") SELECT id FROM anc")
    List<Long> findAncestorIds(@Param("memberId") Long memberId);
}
