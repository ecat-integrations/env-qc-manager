package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanEstimateDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanEstimateResult;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanSaveDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.dto.BatchResult;

import java.util.List;

/**
 * 质控任务计划服务（FR-01-15..19 状态机 + FR-01-24 立即执行 + FR-01-32 预估）。
 *
 * @author coffee
 */
public interface IQcmPlanService {

    /**
     * 创建 / 编辑保存（dto.id 空 = 创建）：统一经 {@link PlanParamValidator} 校验
     * （FR-03-17 单一事实源），非法抛 {@link IllegalArgumentException}（消息为逐条错误拼接）。
     *
     * <p>创建落 ACTIVE 并计算 next_fire_time（FR-01-15/16）；ONCE·IMMEDIATE 创建即触发一次
     * （MANUAL 链路，FINISHED 收口由编排器负责）。编辑限 ACTIVE/PAUSED，整行覆盖后按新配置
     * 从当前时刻重算 next_fire_time（FR-01-19/43）。每次保存后通知调度器重挂。</p>
     *
     * @return 保存后的计划行（含 next_fire_time 与调度摘要）
     */
    QcmPlan save(PlanSaveDto dto, String caller);

    /**
     * 状态机变更（FR-01-15）：仅 ACTIVE ↔ PAUSED；FINISHED 只读（仅 delete）。
     * 暂停置空 next_fire_time；启用从当前时刻重算（不追补，FR-01-16）。
     */
    void changeStatus(Long id, String targetStatus, String caller);

    /**
     * 物理删除（FR-01-18）：关联批次不终止，执行记录靠 record_snapshot 溯源。
     */
    void delete(Long id, String caller);

    /**
     * 立即执行（FR-01-24 MANUAL 链路）：FINISHED 拒绝；经 {@link PlanRequestAssembler}
     * 与调度触发共用装配，交统一编排器。
     */
    BatchResult runNow(Long id, String caller);

    /**
     * 条件查询（status/qcType/planName，创建时间倒序）。
     */
    List<QcmPlan> selectList(QcmPlan query);

    /**
     * 按主键查询；行不存在抛 {@link IllegalArgumentException}。
     */
    QcmPlan selectById(Long id);

    /**
     * 阶段预估（FR-01-32）：只读、不落库、不触发；经 composer 只读预估能力（同源公式）。
     */
    PlanEstimateResult estimate(PlanEstimateDto dto);

    /**
     * 人性化调度摘要（FR-01-21 四格式，禁 cron）。
     */
    String scheduleSummary(QcmPlan plan);
}
