package com.ecat.integration.EnvQualityControlManagerIntegration.mapper;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 质控任务计划 Mapper 接口（qcm_plan，FR-04-05）。
 *
 * @author coffee
 */
public interface QcmPlanMapper {

    /**
     * 新增计划（useGeneratedKeys 回填 id）。
     */
    int insert(QcmPlan plan);

    /**
     * 按主键查询计划。
     */
    QcmPlan selectById(Long id);

    /**
     * 条件查询计划列表（status 精确 / qcType 精确 / planName 模糊），按 create_time desc。
     */
    List<QcmPlan> selectList(QcmPlan plan);

    /**
     * 修改计划（非空字段动态 SET）。
     */
    int update(QcmPlan plan);

    /**
     * 编辑保存的全量覆盖（非 id/status/create 侧的所有列无条件 SET，可空列置 NULL）：
     * 编辑语义是整行替换（FR-01-19），动态 SET 会让「清空浓度/有效期」残留旧值。
     */
    int updateAll(QcmPlan plan);

    /**
     * 更新计划状态（ACTIVE / PAUSED / FINISHED 状态机落库）。
     */
    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("updatedBy") String updatedBy);

    /**
     * 调度器维护触发时刻：写 next_fire_time（lastFireTime 非空时一并回写 last_fire_time）。
     */
    int updateNextFireTime(@Param("id") Long id,
                           @Param("nextFireTime") Instant nextFireTime,
                           @Param("lastFireTime") Instant lastFireTime);

    /**
     * 编排器触发后只回写 last_fire_time（非 ONCE 计划的 next_fire_time 由调度器自理，不许旁路覆盖）。
     */
    int updateLastFireTime(@Param("id") Long id,
                           @Param("lastFireTime") Instant lastFireTime,
                           @Param("updatedBy") String updatedBy);

    /**
     * 取 ACTIVE 且 next_fire_time 非空的最早一行（next_fire_time 升序 limit 1）；
     * 调度器 rearm 的唯一目标行，无候选返回 null（空转零唤醒）。
     */
    QcmPlan selectEarliestActive();

    /**
     * 按主键删除计划（记录不级联删除，靠 record_snapshot 溯源）。
     */
    int deleteById(Long id);
}
