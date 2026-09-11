package com.ecat.integration.EnvQualityControlManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 一次质控执行的结果（§4.0 五层结构）：全部来自完成时冻结的强类型列与三张子表，
 * 读路径零 JSON 解析、零 live 查询——设备改名/计划编辑/钢瓶更换均不影响历史结果。
 *
 * <p>五层：事件层（定位/去重/审计）→ 过程层（阶段时间线）→ 判定层（数值因子）→
 * 工况+溯源层（标气/仪器/关键参数）→ 结论层（兜底人读）。
 * 快照缺失项如实为 null/空列表，不伪造（严格模式）。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class SdkExecutionResult {

    // ===== 事件层 =====

    /** 记录 ID */
    long recordId;

    /** 批次标识（一次触发 1..N 条同值） */
    String batchId;

    /** 触发请求标识（trigger 受理时生成；受理→轮询→结果三段同一标识） */
    String triggerRequestId;

    /** 关联计划 ID（SDK 直接触发无计划时为 null） */
    Long planId;

    /** 质控类型（历史遗留行不在词汇域内时如实为 null） */
    SdkQcType qcType;

    /** 受检仪器 */
    SdkInstrument instrument;

    /** 触发源 */
    SdkTriggerSource triggerSource;

    /** 触发者（REMOTE=来源名 / MANUAL=用户名 / SCHEDULED=system） */
    String triggerUser;

    /** 执行开始时间 */
    Instant startTime;

    /** 执行结束时间（未结束时为 null） */
    Instant endTime;

    /** 执行状态码（qcm_record.execution_status int 编码，与 {@link #executionStatusName} 同值异形） */
    int executionStatus;

    /** 执行状态（中文展示由调用方自行 switch） */
    SdkExecutionStatus executionStatusName;

    /** 结构化失败原因（无结构化原因的行——运行中/成功/启动期失败——为 null，人读细节走 resultEvaluation） */
    SdkFailureReason failureReason;

    // ===== 过程层 =====

    /** 阶段时间线（qcm_record_phase 子表，seq 升序；无记录时为空列表） */
    List<SdkRecordDetail.PhaseTimeline> phaseTimelines;

    // ===== 判定层 =====

    /** 判定数值因子（标量 + 多点序列 + 拟合曲线；快照缺失项如实 null） */
    Judgement judgement;

    // ===== 工况+溯源层 =====

    /** 关键参数快照（qcm_record_key_param 子表，seq 升序；无记录时为空列表） */
    List<KeyParameter> keyParameters;

    /** 采样窗口起（快照缺失为 null） */
    Instant samplingStartTime;

    /** 采样窗口止（快照缺失为 null） */
    Instant samplingEndTime;

    /** 仪器名称（执行完成时冻结当时值，此后设备改名不影响历史） */
    String instrumentName;

    /** 仪器编号（执行完成时冻结当时值） */
    String instrumentNo;

    /** 标气来源（执行完成时冻结当时配置；配置面未录入时为 null 如实呈现） */
    String gasSource;

    /** 标气编号（执行完成时冻结当时配置） */
    String gasNo;

    /** 标气浓度（执行完成时冻结当时配置） */
    BigDecimal gasConcentration;

    /** 满量程（执行完成时冻结当时值） */
    BigDecimal fullScale;

    /** 执行体类型（composer ExecutorType className，与 qc_type 业务枚举正交） */
    String flowType;

    /** 排障关联标识（recordId@startMillis，可机器关联回 composer 日志） */
    String flowExecutionRef;

    // ===== 结论层 =====

    /** 结果评定（兜底人读） */
    String resultEvaluation;

    /** 判定层：标量 + 多点序列 + 拟合曲线（全部为完成时冻结值）。 */
    @Value
    @Builder
    public static class Judgement {

        /** 计算值（漂移/计算值） */
        BigDecimal resultValue;

        /** 标准值（单点标气浓度） */
        BigDecimal stdValue;

        /** 仪器读数（单点） */
        BigDecimal deviceValue;

        /** 核查通过限 */
        BigDecimal checkPassLimit;

        /** 校准通过限 */
        BigDecimal checkCalibLimit;

        /** 是否通过（快照缺失为 null） */
        Boolean pass;

        /** 多点/精密度序列：各点标准值（qcm_record_point 子表，seq 升序；无记录时为空列表） */
        List<BigDecimal> stdValues;

        /** 多点/精密度序列：各点仪器读数（与 stdValues 等长对齐） */
        List<BigDecimal> deviceValues;

        /** 拟合斜率（多点线性） */
        BigDecimal slope;

        /** 拟合截距（多点线性） */
        BigDecimal intercept;

        /** 相关系数（多点/精密度） */
        BigDecimal correlation;
    }

    /** 工况层关键参数（qcm_record_key_param 行）；unit 可 null（快照行可能把单位并入 value 呈现）。 */
    @Value
    @Builder
    public static class KeyParameter {

        /** 参数名 */
        String name;

        /** 参数值（字符串呈现；数值语义由 name+unit 界定） */
        String value;

        /** 参数单位（快照未携带时为 null） */
        String unit;

        /** 参考范围（人读） */
        String refRange;
    }
}
