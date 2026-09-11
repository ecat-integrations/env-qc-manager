package com.ecat.integration.EnvQualityControlManagerIntegration.api;

import java.util.List;

/**
 * 质控对外 Java SDK（需求 03）：外部集成仅凭本包三类调用即可触发质控、轮询批次、生成报告级明细，
 * 不接触 qcm 内部 service / mapper / composer 类型。
 *
 * <p>本包契约铁律：零第三方依赖（仅 java.* / lombok 编译期注解，SdkApiZeroDependencyGuardTest 守卫），
 * 不 import qcm 内部类型——外部类加载器无需可见 qcm 其余类。词汇契约：闭域词汇（仪器/质控类型/
 * 触发源/执行状态/受理原因/计划状态/失败原因/时长键/调度类型）一律用本包枚举，编译期穷尽、
 * 存储形态互译由实现收口；开放文本与标识（batchId/triggerRequestId/triggerUser/评定与消息文案/
 * 单位串等）保持 String。</p>
 *
 * <p>获取方式：{@code core.getIntegrationRegistry().getIntegration("integration-env-qc-manager")
 * .getQualityControlSdk()}。</p>
 *
 * @author coffee
 */
public interface QualityControlSdk {

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
     *   <li>幂等——批次已终态或已在终止中 → accepted=false + {@link SdkReason#ALREADY_TERMINAL}，
     *       不改库（终态行的执行事实不可覆盖）；</li>
     *   <li>全停——{@code allRunning=true} 解析为「当前唯一运行批次」（单飞语义），无运行批次 →
     *       accepted=false + {@link SdkReason#NOTHING_RUNNING}（非故障）；</li>
     *   <li>操作者必填——{@link SdkStopRequest#getOperator()} 缺失/空白 →
     *       {@link SdkReason#INVALID_PARAM}（无来源的停止不可归属，不落痕）。</li>
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
     * @param statusFilter 计划状态过滤（精确）；为 null 时返回全部非 FINISHED 计划
     *                     （ACTIVE+PAUSED——已终结的一次性计划不属「当前设置」）
     */
    List<SdkPlanSetting> queryPlans(SdkPlanStatus statusFilter);
}
