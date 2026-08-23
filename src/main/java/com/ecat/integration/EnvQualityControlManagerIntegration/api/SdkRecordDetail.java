package com.ecat.integration.EnvQualityControlManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 单条记录报告级明细（D15 清单逐字段，FR-03-14/15）：外部仅凭本对象即可生成质控报告，
 * 含 execution_log 解析出的阶段时间线与触发时参数快照。
 */
@Value
@Builder
public class SdkRecordDetail {

    /** 记录 ID */
    long recordId;

    /** 批次标识 */
    String batchId;

    /** 关联计划 ID（SDK 直接触发无计划时为 null） */
    Long planId;

    /** 质控类型（zero_check 等，与 qcm_plan.qc_type 同词汇） */
    String qcType;

    /** 仪器代码 */
    String instrument;

    /** 触发源（SCHEDULED/MANUAL/REMOTE） */
    String triggerSource;

    /** 触发者（REMOTE=来源名 / MANUAL=用户名 / SCHEDULED=system） */
    String triggerUser;

    /** 执行开始时间 */
    Instant startTime;

    /** 执行结束时间（未结束时为 null） */
    Instant endTime;

    /** 执行状态码（qcm_record.execution_status int 编码） */
    int executionStatus;

    /** 执行状态展示名（等待中/执行中/成功/失败/手动中止） */
    String executionStatusName;

    /** 结构化失败原因（如 EXECUTOR_BUSY_CONFLICT；非失败场景为 null） */
    String failureReason;

    /** 监测数据（仪器读数） */
    BigDecimal monitoringData;

    /** 标准值（标气浓度） */
    BigDecimal standardValue;

    /** 计算值 */
    BigDecimal calculatedValue;

    /** 结果评定（成功通过/未通过/失败原因文字） */
    String resultEvaluation;

    /** 阶段时间线（execution_log 的 qcPhaseTimelines 解析；无记录时为空列表） */
    List<PhaseTimeline> phaseTimelines;

    /** 触发时参数快照（record_snapshot JSON 解析为 Map；无快照时为空 Map） */
    Map<String, Object> recordSnapshot;

    /** 阶段时间线条目：预估时长 + 实际起止（按需跳过的相位无结束时间）。 */
    @Value
    @Builder
    public static class PhaseTimeline {

        /** 阶段代码（如 calibration / READ_SAMPLES） */
        String phaseId;

        /** 阶段展示名（如 读数） */
        String phaseName;

        /** 预估时长秒 */
        Long estimatedSeconds;

        /** 实际开始时间（历史行缺失时为 null） */
        Instant start;

        /** 实际结束时间（按需跳过/进行中的相位为 null） */
        Instant end;
    }
}
