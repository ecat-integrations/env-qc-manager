package com.ecat.integration.EnvQualityControlManagerIntegration.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 质控执行记录对象 qcm_record（FR-04-08：旧 env_quality_control_records 全列平移 + 批次/溯源四新列）。
 *
 * <p>多仪器零点一次执行 N 行（D5）：各行 parameter 独立、batch_id 同值；
 * record_snapshot 为触发时的计划配置快照 JSON（String 直传），计划删除后记录仍可溯源（FR-04-09）。</p>
 *
 * @author coffee
 */
@Data
public class QcmRecord {

    /**
     * 查询串时间窗解析时区（与前端 el-date-picker 提交的本地时间一致）。
     * 与 EnvQualityControlGenReportTask.ZONE / ReportGenerator.QC_REPORT_ZONE 同值不合并：查询窗、调度窗、报表归日三个域各自独立演进。
     */
    public static final ZoneId QUERY_WINDOW_ZONE = ZoneId.of("Asia/Shanghai");

    /** 前端 el-date-picker value-format="YYYY-MM-DD HH:mm:ss" 提交的时间串格式 */
    public static final DateTimeFormatter QUERY_WINDOW_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 记录 ID */
    private Long id;

    /** 批次标识（UUID）；一次触发 1..N 条同值 */
    private String batchId;

    /** 关联 qcm_plan.id；计划删除不级联，靠 record_snapshot 溯源 */
    private Long planId;

    /** 任务类型 */
    private String taskType;

    /** 质控类型（QualityControlTypeEnum.name） */
    private String qualityControlType;

    /** 参数（仪器代码；多仪器零点各行各自仪器） */
    private String parameter;

    /** 执行开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant startTime;

    /** 执行结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant endTime;

    /** 标准值（标气浓度） */
    private BigDecimal standardValue;

    /** 监测数据（仪器读数） */
    private BigDecimal monitoringData;

    /** 计算值 */
    private BigDecimal calculatedValue;

    /** 执行状态（ExecutionStatusEnum int4 编码） */
    private Integer executionStatus;

    /** 执行日志文本（含 phaseTimelines 阶段时间线，现状机制沿用） */
    private String executionLog;

    /** 结果评定 */
    private String resultEvaluation;

    /** 触发者：MANUAL=用户名 / SCHEDULED=system / REMOTE=displayOperator（PLATFORM 形态为 name@ip[:port]） */
    private String triggerUser;

    /** 结构化失败原因枚举（FR-02-23）；普通失败仍走 execution_log */
    private String failureReason;

    /** 触发时的计划配置快照 JSON（名称/类型/仪器/参数摘要） */
    private String recordSnapshot;

    // ===== §4.0 判定标量（完成时一次性冻结，updateResultSnapshot 写入）=====

    /** 核查通过限（完成时冻结） */
    private BigDecimal checkPassLimit;

    /** 校准通过限（完成时冻结） */
    private BigDecimal checkCalibLimit;

    /** 是否通过（完成时冻结） */
    private Boolean isPass;

    /** 拟合斜率（多点线性，完成时冻结） */
    private BigDecimal slope;

    /** 拟合截距（多点线性，完成时冻结） */
    private BigDecimal intercept;

    /** 相关系数（多点/精密度，完成时冻结） */
    private BigDecimal correlation;

    // ===== §4.0 快照层（完成时冻结当时值，读时零关联）=====

    /** 满量程（完成时冻结当时值） */
    private BigDecimal fullScale;

    /** 仪器名称（完成时冻结当时值，此后设备改名不影响历史） */
    private String instrumentName;

    /** 仪器编号（完成时冻结当时值） */
    private String instrumentNo;

    /** 标气来源（完成时冻结当时值） */
    private String gasSource;

    /** 标气编号（完成时冻结当时值） */
    private String gasNo;

    /** 标气浓度（完成时冻结当时值，钢瓶档案浓度口径） */
    private BigDecimal gasConcentration;

    /** 标气浓度单位（完成时冻结当时值，如 ppm） */
    private String gasConcentrationUnit;

    /** 采样窗口起（完成时冻结） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant samplingStartTime;

    /** 采样窗口止（完成时冻结） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant samplingEndTime;

    // ===== §4.0.1 执行标识与 flow 关联 =====

    /** 触发请求标识（trigger 受理时生成返回调用方；SDK 受理→轮询→结果三段同一标识） */
    private String triggerRequestId;

    /** 执行体类型（composer ExecutorType className，与 qc_type 业务枚举正交） */
    private String flowType;

    /** 排障关联标识（编排器启动时生成 recordId@startMillis，composer 日志可机器关联回 record） */
    private String flowExecutionRef;

    /** 创建人 */
    private String createdBy;

    /** 更新人 */
    private String updatedBy;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant createTime;

    /** 更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant updateTime;

    // ===== 非数据库查询扩展字段（列表时间窗筛选，替代旧 BaseEntity.params 通道）=====

    /** 查询窗：end_time 起 */
    private Instant beginEndTime;

    /** 查询窗：end_time 止 */
    private Instant endEndTime;

    /** 查询窗：start_time 起（records 页「开始时间」daterange 经 params 通道提交） */
    private Instant beginStartTime;

    /** 查询窗：start_time 止 */
    private Instant endStartTime;

    /**
     * 兼容旧 REST 契约的查询串通道（前端以 {@code params[beginEndTime]=...&params[endEndTime]=...}
     * 或 {@code params[beginStartTime]=...&params[endStartTime]=...} 提交时间窗）。
     * 识别 beginEndTime/endEndTime（end_time 窗）与 beginStartTime/endStartTime（start_time 窗）
     * 四键并解析为 Instant 写入直字段；其余键值原样留存但不参与 SQL。
     * 严格模式：格式非法抛 IllegalArgumentException，不猜默认值。
     */
    private Map<String, Object> params;

    /**
     * 解析查询时间窗串（{@code yyyy-MM-dd HH:mm:ss}，Asia/Shanghai 本地时间）为 Instant。
     *
     * @param key   参数名（用于异常信息定位）
     * @param value 时间串
     * @return Instant；value 为 null/空串时返回 null（该键未提交）
     * @throws IllegalArgumentException 格式非法时
     */
    public static Instant parseQueryWindow(String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String s = value.trim();
        try {
            LocalDateTime ldt = LocalDateTime.parse(s, QUERY_WINDOW_FORMATTER);
            return ldt.atZone(QUERY_WINDOW_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "params[" + key + "] 时间格式非法，期望 yyyy-MM-dd HH:mm:ss，实际: " + s, e);
        }
    }

    public void setParams(Map<String, Object> params) {
        // 仅存原始 map：Spring 对 params[key] 的查询绑定会自动生长 Map 并直接塞值，
        // setParams 只在生长时被以空 map 调用一次，之后逐键绕过本 setter——解析逻辑放这里永远跑不到。
        // 时间窗解析由 controller 入口显式调 {@link #resolveQueryWindows()} 完成。
        this.params = params;
    }

    /**
     * 把 params 通道的时间窗串解析为 Instant 直字段（beginEndTime/endEndTime 走 end_time 窗、
     * beginStartTime/endStartTime 走 start_time 窗）。controller 在 list/export 入口调用。
     *
     * @throws IllegalArgumentException 时间格式非法（严格模式，不猜默认值）
     */
    public void resolveQueryWindows() {
        if (params == null) {
            return;
        }
        Object begin = params.get("beginEndTime");
        Object end = params.get("endEndTime");
        Object beginStart = params.get("beginStartTime");
        Object endStart = params.get("endStartTime");
        if (begin != null) {
            this.beginEndTime = parseQueryWindow("beginEndTime", begin.toString());
        }
        if (end != null) {
            this.endEndTime = parseQueryWindow("endEndTime", end.toString());
        }
        if (beginStart != null) {
            this.beginStartTime = parseQueryWindow("beginStartTime", beginStart.toString());
        }
        if (endStart != null) {
            this.endStartTime = parseQueryWindow("endStartTime", endStart.toString());
        }
    }
}
