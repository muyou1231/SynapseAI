package com.synapseai.mapper;

import com.synapseai.entity.StudyPlan;
import com.synapseai.entity.StudyStat;
import org.apache.ibatis.annotations.*;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface StudyPlanMapper {

    // ===== 通用 CRUD（显式写出，不依赖 BaseMapper 继承）=====

    @Insert("INSERT INTO study_plan(user_id, title, content, checkin_minutes, pomodoro_minutes, checkin_days, last_checkin, create_time) " +
            "VALUES(#{userId}, #{title}, #{content}, #{checkinMinutes}, #{pomodoroMinutes}, #{checkinDays}, #{lastCheckin}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(StudyPlan plan);

    @Select("SELECT * FROM study_plan WHERE id = #{id}")
    StudyPlan selectById(Long id);

    @Delete("DELETE FROM study_plan WHERE id = #{id}")
    int deleteById(Long id);

    /** 我的计划列表（按创建时间倒序，最新在前） */
    @Select("SELECT * FROM study_plan WHERE user_id = #{userId} ORDER BY create_time DESC")
    List<StudyPlan> selectByUserId(@Param("userId") Long userId);

    /** 整体替换计划正文（勾选/编辑/增删步骤后整份落库） */
    @Update("UPDATE study_plan SET content = #{content} WHERE id = #{id}")
    int updateContentById(@Param("id") Long id, @Param("content") String content);

    /** 累加计划的打卡/番茄钟时长与打卡日期 */
    @Update("UPDATE study_plan SET " +
            "checkin_minutes = checkin_minutes + #{checkinMinutes}, " +
            "pomodoro_minutes = pomodoro_minutes + #{pomodoroMinutes}, " +
            "checkin_days = #{checkinDays}, " +
            "last_checkin = #{lastCheckin} " +
            "WHERE id = #{id}")
    int updateStats(@Param("id") Long id,
                    @Param("checkinMinutes") int checkinMinutes,
                    @Param("pomodoroMinutes") int pomodoroMinutes,
                    @Param("checkinDays") String checkinDays,
                    @Param("lastCheckin") java.time.LocalDate lastCheckin);

    /** 今日统计：不存在则插入，存在则累加（upsert，MySQL 5.7 用 ON DUPLICATE KEY UPDATE） */
    @Insert("INSERT INTO study_stats(user_id, stat_date, pomodoro_minutes, checkin_minutes, checkin_count) " +
            "VALUES(#{userId}, #{statDate}, #{pomodoroMinutes}, #{checkinMinutes}, #{checkinCount}) " +
            "ON DUPLICATE KEY UPDATE " +
            "pomodoro_minutes = pomodoro_minutes + #{pomodoroMinutes}, " +
            "checkin_minutes = checkin_minutes + #{checkinMinutes}, " +
            "checkin_count = checkin_count + #{checkinCount}")
    int upsertStat(@Param("userId") Long userId,
                   @Param("statDate") java.time.LocalDate statDate,
                   @Param("pomodoroMinutes") int pomodoroMinutes,
                   @Param("checkinMinutes") int checkinMinutes,
                   @Param("checkinCount") int checkinCount);

    /** 今日统计（按 user_id + 日期） */
    @Select("SELECT * FROM study_stats WHERE user_id = #{userId} AND stat_date = #{statDate}")
    StudyStat selectToday(@Param("userId") Long userId, @Param("statDate") java.time.LocalDate statDate);

    /** 累计统计（全部天求和） */
    @Select("SELECT COALESCE(SUM(pomodoro_minutes),0) AS pomodoro, " +
            "COALESCE(SUM(checkin_minutes),0) AS checkin, " +
            "COALESCE(SUM(checkin_count),0) AS cnt " +
            "FROM study_stats WHERE user_id = #{userId}")
    java.util.Map<String, Object> sumStats(@Param("userId") Long userId);
}
