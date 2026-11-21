package com.datagov.control.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datagov.control.entity.AutonomyRateLimitEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

@Mapper
public interface AuActionCounterMapper extends BaseMapper<AutonomyRateLimitEntity> {

    @Insert("""
            INSERT INTO au_action_execution_counter
                (id, workspace_id, action_code, counter_day, execution_count, updated_at)
            VALUES (#{id}, #{workspaceId}, #{actionCode}, #{day}, 1, now())
            ON CONFLICT (workspace_id, action_code, counter_day)
            DO UPDATE SET execution_count = au_action_execution_counter.execution_count + 1,
                          updated_at = now()
            """)
    int increment(@Param("id") String id,
                  @Param("workspaceId") String workspaceId,
                  @Param("actionCode") String actionCode,
                  @Param("day") LocalDate day);

    @Select("""
            SELECT execution_count
              FROM au_action_execution_counter
             WHERE workspace_id = #{workspaceId}
               AND action_code = #{actionCode}
               AND counter_day = #{day}
            """)
    Integer currentCount(@Param("workspaceId") String workspaceId,
                         @Param("actionCode") String actionCode,
                         @Param("day") LocalDate day);

    @Update("""
            UPDATE au_action_execution_counter
               SET execution_count = GREATEST(0, execution_count - 1),
                   updated_at = now()
             WHERE workspace_id = #{workspaceId}
               AND action_code = #{actionCode}
               AND counter_day = #{day}
            """)
    int decrement(@Param("workspaceId") String workspaceId,
                  @Param("actionCode") String actionCode,
                  @Param("day") LocalDate day);
}
