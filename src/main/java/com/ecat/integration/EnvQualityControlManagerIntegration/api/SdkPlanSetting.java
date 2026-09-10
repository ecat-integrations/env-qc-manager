package com.ecat.integration.EnvQualityControlManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 质控计划的当前设置（对外 SDK 出参）：外部集成仅凭本对象即可获知一条质控计划的调度语义与
 * 检测项，无需接触 qcm 内部 domain / schedule 类型。
 *
 * <p>只承载中性字段（调度小时/分钟/星期/月日、检测项、质控类型 code、启停状态）；具体协议字段名
 * 映射（如某省协议「质控任务当前设置」的 Time/Days/Pollutant/TaskType）由调用方自行转换，
 * SDK 不掺协议语义。</p>
 *
 * <p>调度字段与 qcm 内部 ScheduleSpec 同构：DAILY 用 hour/minute；WEEKLY 另用 weekdays
 * （1=周一..7=周日）；MONTHLY 另用 monthDays（1..31）；ONCE 用 onceAt。未涉及的集合为 null。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class SdkPlanSetting {

    /** 计划 ID */
    long planId;

    /** 计划名称 */
    String planName;

    /**
     * 质控类型 code（zero_check / span_check / multi_check / precision_check /
     * accuracy_check / conversion_check / audit_span_check / multi_zero_check）。
     */
    String qcType;

    /** 检测项（仪器代码列表，如 ["SO2","NO2","CO","O3"]） */
    List<String> instruments;

    /** 调度类型：DAILY / WEEKLY / MONTHLY / ONCE */
    String scheduleType;

    /** 调度小时（0-23）；ONCE 以 onceAt 为准，本值为配置残留可忽略 */
    int hour;

    /** 调度分钟（0-59） */
    int minute;

    /** 每周触发的星期（1=周一..7=周日）；仅 WEEKLY 非空 */
    Set<Integer> weekdays;

    /** 每月触发的日（1..31）；仅 MONTHLY 非空 */
    Set<Integer> monthDays;

    /** 一次性触发时刻；仅 ONCE 非空 */
    Instant onceAt;

    /** 计划状态：ACTIVE / PAUSED / FINISHED */
    String status;

    /** 是否启用（status==ACTIVE） */
    boolean enabled;
}
