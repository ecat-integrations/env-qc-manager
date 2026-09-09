package com.ecat.integration.EnvQualityControlManagerIntegration.api;

import java.util.List;

/**
 * 质控对外 Java SDK（需求 03）：外部集成仅凭本包三类调用即可触发质控、轮询批次、生成报告级明细，
 * 不接触 qcm 内部 service / mapper / composer 类型。
 *
 * <p>本包契约铁律：零第三方依赖（仅 java.* / lombok 编译期注解，SdkApiZeroDependencyGuardTest 守卫），
 * 状态与枚举一律用 String 常量承载，不 import qcm 内部枚举——外部类加载器无需可见 qcm 其余类。</p>
 *
 * <p>获取方式：{@code core.getIntegrationRegistry().getIntegration("integration-env-qc-manager")
 * .getQualityControlSdk()}。</p>
 */
public interface QualityControlSdk {

    /** 拒绝原因：执行器被互斥闸占用（批次已写 FAILED 终态留痕，D10）。 */
    String REASON_BUSY_CONFLICT = "BUSY_CONFLICT";
    /** 拒绝原因：请求 allowQueue=true，但 SDK 触发不支持排队（FR-03-07，不写任何记录）。 */
    String REASON_QUEUE_NOT_SUPPORTED = "QUEUE_NOT_SUPPORTED";
    /** 拒绝原因：请求参数非法（复用计划参数校验器同一套规则，FR-03-17）。 */
    String REASON_INVALID_PARAM = "INVALID_PARAM";
    /** 拒绝原因：执行器类型/结果格式化器未就绪（集成尚未完成启动注册）。 */
    String REASON_EXECUTOR_TYPE_NOT_READY = "EXECUTOR_TYPE_NOT_READY";

    /**
     * 异步触发：立即返回受理/拒绝（毫秒级），不等待执行（FR-03-10）。
     * 执行结果异步落 qcm_record，经 {@link #queryExecution(String)} 轮询获取。
     */
    SdkTriggerReply trigger(SdkTriggerRequest request);

    /**
     * 批次状态与各记录摘要（轮询，FR-03-11/12）；批次终态 = 批次内全部记录到达终态
     * （成功/失败；等待中/执行中/手动中止中均为非终态）。
     *
     * @throws IllegalArgumentException batchId 为空或批次不存在
     */
    SdkBatchState queryExecution(String batchId);

    /**
     * 单条记录全量明细：外部仅凭 SDK 可生成报告（D15 / FR-03-14/15），
     * 含 execution_log 解析出的阶段时间线与触发时快照。
     *
     * @throws IllegalArgumentException 记录不存在
     */
    SdkRecordDetail getRecordDetail(long recordId);

    /**
     * 按过滤条件查询执行结果快照（§4.0/§4.1）：读强类型列+三子表，零 JSON 解析、零 live 查询，
     * 不依赖报告生成任务跑过（queryResults 即补数）。结果按 start_time 降序。
     *
     * @throws IllegalArgumentException filter 为 null 或全空（防全表扫）；或窗口命中超过上限
     *         （500 行，消息带命中行数，提示收窄时间窗）
     */
    List<SdkExecutionResult> queryResults(ResultFilter filter);

    /**
     * 单条执行结果快照（getRecordDetail 的强类型升级，五层结构同构）。
     *
     * @throws IllegalArgumentException 记录不存在
     */
    SdkExecutionResult getExecutionResult(long recordId);

    /**
     * 查询质控计划的当前设置（「质控任务当前配置」类上报用）：返回中性调度/检测项字段，
     * 协议字段名映射（Time/Days/Pollutant/TaskType 等）由调用方负责，SDK 不掺协议语义。
     *
     * <p>调度配置解析失败的个别计划会被跳过（不整批失败）；无匹配时返回空列表。</p>
     *
     * @param statusFilter 计划状态过滤（ACTIVE/PAUSED/FINISHED，精确）；为空/空白时返回全部
     *                     非 FINISHED 计划（ACTIVE+PAUSED——已终结的一次性计划不属「当前设置」）
     */
    List<SdkPlanSetting> queryPlans(String statusFilter);
}
