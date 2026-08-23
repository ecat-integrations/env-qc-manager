package com.ecat.integration.EnvQualityControlManagerIntegration.domain;

import lombok.Data;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/**
 * 质控任务计划对象 qcm_plan（FR-04-05，调度模型换代：不存 cron 表达式，schedule_config 承载调度语义）。
 *
 * <p>jsonb 字段（instruments/schedule_config/duration_overrides/point_percents）用 String 直传，
 * JSON 序列化/解析归 service 层；时间字段一律 {@link Instant}（timestamptz，FR-04-03）。</p>
 *
 * @author coffee
 */
@Data
public class QcmPlan {

    /** 计划 ID */
    private Long id;

    /** 计划名称 */
    private String planName;

    /** 质控类型 code（QualityControlTypeEnum.name，snake_case） */
    private String qcType;

    /** 仪器代码数组 JSON（如 ["SO2","NO2","CO","O3"]）；单仪器类型长度恒 1 */
    private String instruments;

    /** 调度类型：DAILY / WEEKLY / MONTHLY / ONCE */
    private String scheduleType;

    /** 调度配置 JSON {hour, minute, weekdays[], monthDays[], onceMode, onceAt}；按 schedule_type 取相关字段 */
    private String scheduleConfig;

    /** 标气浓度 ppb；仅需要绝对浓度的类型（跨度/人工核查），零点类 NULL */
    private BigDecimal concentrationPpb;

    /** 标气流量 L/min；零点类 NULL */
    private BigDecimal flowRateLpm;

    /** 用户覆盖的时长参数 JSON（稀疏，仅存改过项） */
    private String durationOverrides;

    /** 线性/准确度的量程百分比序列 JSON（0~1 小数）；NULL=走默认序列 */
    private String pointPercents;

    /** 计划有效期起（沿用原表 plan_start_time 设计，D18 尊重原表不无故减列）；NULL=立即生效 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant planStartTime;

    /** 计划有效期止（沿用原表 plan_end_time 设计）；NULL=长期有效。窗口外调度不触发 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant planEndTime;

    /** 计划状态：ACTIVE / PAUSED / FINISHED */
    private String status;

    /** 下次触发时刻（调度器维护；PAUSED 置 NULL） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant nextFireTime;

    /** 上次触发时刻 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant lastFireTime;

    /** 创建人（ruoyi 用户名） */
    private String createdBy;

    /** 更新人（ruoyi 用户名） */
    private String updatedBy;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant createTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant updateTime;

    /**
     * 调度摘要文案（FR-01-21 四格式，禁 cron）：非表列——resultMap 不含该列，
     * 由 service 在列表/详情出参时解析 schedule_config 填充，仅用于展示。
     */
    private String scheduleSummary;
}
