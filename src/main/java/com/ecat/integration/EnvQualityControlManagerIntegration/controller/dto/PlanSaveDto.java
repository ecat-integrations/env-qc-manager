package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 计划创建 / 编辑共用请求体（FR-01-30 全表校验归 {@code PlanParamValidator}，本 DTO 只承载字段）。
 *
 * <p>id 为空 = 创建（状态机落 ACTIVE，FR-01-15）；非空 = 编辑（限 ACTIVE/PAUSED，FR-01-19）。
 * 时刻字段用 ISO-8601 字符串承载（解析失败由 Validator 逐条报错，不静默修正）。</p>
 *
 * @author coffee
 */
@Data
public class PlanSaveDto {

    /** 计划 ID；空 = 创建 */
    private Long id;

    /** 计划名称（非空 ≤100） */
    private String planName;

    /** 质控类型 name（QualityControlTypeEnum，如 zero_check） */
    private String qcType;

    /** 仪器代码列表（单仪器类型恒 1 台；multi 预留多台） */
    private List<String> instruments;

    /** 调度类型：DAILY / WEEKLY / MONTHLY / ONCE */
    private String scheduleType;

    /** 时（0-23） */
    private Integer hour;

    /** 分（0-59） */
    private Integer minute;

    /** WEEKLY：星期几（1=周一..7=周日），非空 */
    private List<Integer> weekdays;

    /** MONTHLY：每月几号（1-31），非空 */
    private List<Integer> monthDays;

    /** ONCE 模式：IMMEDIATE（立刻）/ SCHEDULED（指定时刻） */
    private String onceMode;

    /** ONCE·SCHEDULED 指定时刻（ISO-8601，须晚于当前时刻，FR-01-13） */
    private String onceAt;

    /** 标气浓度 ppb（span_check / audit_span_check 必填 >0） */
    private BigDecimal concentrationPpb;

    /** 标气流量 L/min（zero_check 拒绝提供；其余可空或 ∈(0,50]） */
    private BigDecimal flowRateLpm;

    /** 量程百分比序列（0~1 升序 ≥2 项；空 = composer 默认序列，FR-01-33） */
    private List<Float> pointPercents;

    /** 阶段时长覆盖（稀疏；key 白名单见 01 §6 参数表 14 键，值正整数秒） */
    private Map<String, Object> durationOverrides;

    /** 有效期起（ISO-8601，可空） */
    private String planStartTime;

    /** 有效期止（ISO-8601，可空；两者齐供时 start &lt; end，FR-01-42） */
    private String planEndTime;
}
