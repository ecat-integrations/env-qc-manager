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
    /** 拒绝原因：请求 allowQueue=true，但 SDK 触发不支持排队（FR-03-07）；参数完整时本批次已写 FAILED 终态留痕。 */
    String REASON_QUEUE_NOT_SUPPORTED = "QUEUE_NOT_SUPPORTED";
    /** 拒绝原因：请求参数非法（复用计划参数校验器同一套规则，FR-03-17）；参数完整（qcType/instruments 可解析）时本批次已写 FAILED 终态留痕。 */
    String REASON_INVALID_PARAM = "INVALID_PARAM";
    /** 拒绝原因：执行器类型/结果格式化器未就绪（集成尚未完成启动注册）。 */
    String REASON_EXECUTOR_TYPE_NOT_READY = "EXECUTOR_TYPE_NOT_READY";

    /** 受理原因：停止已受理（STOPPING 已置位，设备恢复与终态落库异步完成，终态经 {@link #queryExecution(String)} 轮询）；
     *  本词汇下 accepted=true。 */
    String REASON_STOP_INITIATED = "STOP_INITIATED";
    /** 拒绝原因：目标批次已结算（已终态或已在终止中），本次停止不受理且不改库——已终态行的
     *  end_time/result_evaluation 是执行事实（幂等语义，重复 stop 不产生副作用）。 */
    String REASON_ALREADY_TERMINAL = "ALREADY_TERMINAL";
    /** 拒绝原因：当前没有运行中的质控执行（allRunning 寻址且执行闸空闲）。非故障语义——
     *  「不管在跑什么都停」在没有东西可停时是正常结果。 */
    String REASON_NOTHING_RUNNING = "NOTHING_RUNNING";
    /** 拒绝原因：寻址句柄解析不到任何质控记录（记录不存在或已清理）。 */
    String REASON_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";

    /**
     * 异步触发：立即返回受理/拒绝（毫秒级），不等待执行（FR-03-10）。
     * 执行结果异步落 qcm_record，经 {@link #queryExecution(String)} 轮询获取。
     */
    SdkTriggerReply trigger(SdkTriggerRequest request);

    /**
     * 按句柄停止质控执行（§5）：受理即返回（毫秒级），设备恢复与终态落库异步完成，终态经
     * {@link #queryExecution(String)} 轮询。
     *
     * <p>语义：</p>
     * <ul>
     *   <li>批次粒度——一个批次一个 flow，寻址到批内任一行即停整批（accepted=true 的
     *       batchId/recordIds 回执说明实际停的是什么）；</li>
     *   <li>幂等——批次已终态或已在终止中 → accepted=false + {@link #REASON_ALREADY_TERMINAL}，
     *       不改库（终态行的执行事实不可覆盖）；</li>
     *   <li>全停——{@code allRunning=true} 解析为「当前唯一运行批次」（单飞语义），无运行批次 →
     *       accepted=false + {@link #REASON_NOTHING_RUNNING}（非故障）；</li>
     *   <li>操作者必填——{@link SdkStopRequest#getOperator()} 缺失/空白 →
     *       {@link #REASON_INVALID_PARAM}（无来源的停止不可归属，不落痕）。</li>
     * </ul>
     */
    SdkStopReply stop(SdkStopRequest request);

    /**
     * 当前运行中的执行（§5，queryRunning）：单飞语义下至多 1 项，列表形态防未来并发策略变化。
     * 返回项的 batchId/recordIds/triggerRequestId 即 {@link SdkStopRequest} 的三个寻址句柄——
     * 外部「先看在跑什么再停」的入口。无运行执行时返回空列表（非故障）。
     */
    List<SdkRunningExecution> queryRunning();

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
