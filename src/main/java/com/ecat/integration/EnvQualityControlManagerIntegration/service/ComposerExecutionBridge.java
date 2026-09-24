package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvCalibrationComposerIntegration.AbstractCalibrationFlow;
import com.ecat.integration.EnvCalibrationComposerIntegration.CheckResult;
import com.ecat.integration.EnvCalibrationComposerIntegration.EnvCalibrationComposerIntegration;
import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorResultBase;
import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorStoppedException;
import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorType;
import com.ecat.integration.EnvCalibrationComposerIntegration.MissingDevice;
import com.ecat.integration.EnvCalibrationComposerIntegration.MultiZeroCheckResult;
import com.ecat.integration.EnvCalibrationComposerIntegration.PhaseExecutionRecord;
import com.ecat.integration.EnvCalibrationComposerIntegration.PhaseInfo;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ExecutionStatusEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 编排器的 composer 触碰段协作类（字节码隔离边界，非 Spring bean）。
 *
 * 为什么必须是独立类且非 bean：ruoyi 延迟加载体系里，qcm 集成 jar 先于 composer 集成
 * （独立 jar）进共享 loader，且注册 bean 后立即 preInstantiateSingletons——Spring 对 bean
 * 类的 getDeclaredMethods 内省触发 JVM 链接+类型校验，校验器解析字节码校验强制位
 * （StackMapTable 帧/checkcast/方法描述符）点名的类时 composer 尚不可见，NoClassDefFoundError。
 * 因此 {@link QcmExecutionOrchestrator} 作为 bean 类常量池必须零 composer 类引用，全部
 * composer 触碰收敛到本类：本类不是 bean、不被启动期内省，首次真实执行才加载——彼时
 * 执行本身就依赖 composer，其必已就位。
 *
 * 对外方法签名只用 Object/JDK/qcm 类型（bean 侧调用这些方法时，描述符不得把 composer
 * 类名带回 bean 的常量池）；composer 对象在体内强转。owner 参数取编排器的落库协作件
 * （记录服务/快照冻结/停止者暂存），业务语义与搬迁前逐字一致。
 *
 * @author coffee
 */
final class ComposerExecutionBridge {

    private static final Logger log = LoggerFactory.getLogger(ComposerExecutionBridge.class);

    private ComposerExecutionBridge() {
    }

    /** composer 侧忙闸探询（互斥检查）。composerObj = 集成注册表取回的 composer 集成实例。 */
    static boolean isRunning(Object composerObj) {
        return Boolean.TRUE.equals(((EnvCalibrationComposerIntegration) composerObj).isRunning());
    }

    /** 质控类型 → composer 执行器枚举。解析失败语义（未知类型向调用方抛出）与编排器内联时期一致。 */
    static Object executorTypeOf(QualityControlTypeEnum qcEnum) {
        return ExecutorType.getEnum(qcEnum.getClassName());
    }

    /** 启动 composer flow，返回其结果 future（编排器侧只作 Object 透传，勿引具体类型）。 */
    static Object execute(Object composerObj, Object execTypeObj, String gasForComposer,
                          Map<String, Object> flowParams) {
        EnvCalibrationComposerIntegration composer = (EnvCalibrationComposerIntegration) composerObj;
        ExecutorType execType = (ExecutorType) execTypeObj;
        return flowParams.isEmpty()
                ? composer.execute(execType, gasForComposer)
                : composer.execute(execType, gasForComposer, flowParams);
    }

    /** 取当前运行 flow 句柄（编排器存入 executorMap，值类型保持 Object）。 */
    static Object runningExecutor(Object composerObj) {
        return ((EnvCalibrationComposerIntegration) composerObj).getRunningExecutor();
    }

    /** 停止 flow（stopExecution 受理路径；flowObj 为 executorMap 内窥视到的句柄）。 */
    static void stopFlow(Object flowObj) {
        ((AbstractCalibrationFlow) flowObj).stop();
    }

    /**
     * 启动失败桩结果落库（格式化器走 stub 通道，避免零点/跨度 (CheckResult) 强转失败）。
     */
    static void persistStubTerminal(QcmExecutionOrchestrator owner, List<QcmRecord> records,
                                    QcResultFormatter formatter, Map<String, Object> logParams,
                                    QualityControlTypeEnum qcEnum, String cleanMessage) {
        String message = "校准任务过程异常 " + cleanMessage;
        for (QcmRecord record : records) {
            ExecutorResultBase stub = new ExecutorResultBase(false, true);
            stub.setErrorMessage(cleanMessage);
            record.setExecutionLog(formatter.formatStub(owner.core, logParams, stub, qcEnum.getCode(), record.getId()));
            record.setResultEvaluation(message);
            record.setEndTime(Instant.now());
            record.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode().intValue());
            owner.recordService.updateQcmRecord(record);
        }
    }

    /**
     * 结果回调：thenAccept/exceptionally 平移自编排器（异常分支不再重抛，回调链约定不变），
     * 批次 N 行逐行落终态。futureRaw/子结果等以 Object + 体内强转形态保留（自编排器内联
     * 时期为 bean 字节码隔离所迫；本类无该约束，保留原形以最小化回归面）。
     */
    @SuppressWarnings("unchecked")
    static void attachResultCallback(QcmExecutionOrchestrator owner, Object futureRaw,
                                     List<QcmRecord> records, QcResultFormatter formatter,
                                     Map<String, Object> logParams, QualityControlTypeEnum qcEnum,
                                     Map<Long, Object> executorMap, long flowStartMillis) {
        CompletableFuture<ExecutorResultBase> future = (CompletableFuture<ExecutorResultBase>) futureRaw;
        final boolean auditLikeFailureOnNotPass = qcEnum == QualityControlTypeEnum.AUDIT_SPAN_CHECK;
        future.thenAccept((Object resultObj) -> {
            ExecutorResultBase result = (ExecutorResultBase) resultObj;
            log.info("Calibration result " + result.toString());
            // 降级运行缺失标记：
            // 缺监测仪=人工视检数据无效；缺校准仪=产气人工操作。执行时序照常走完，只影响留痕语义
            List<MissingDevice> missingDevices = result.getMissingDevices();
            final boolean targetAnalyzerMissing =
                    missingDevices != null && missingDevices.contains(MissingDevice.TARGET_ANALYZER);
            final boolean calibratorMissing =
                    missingDevices != null && missingDevices.contains(MissingDevice.CALIBRATOR);
            // 停止者暂存须在回调执行时（而非本方法装配时）读取：stop 发生在受理之后的任意时刻，
            // 读后清保证本批终态回调只消费一次（thenAccept 体抛异常会再进 exceptionally，取到 null 不重复换算）
            final String stopOperator = owner.takeStopOperator(records);
            // multi 信封逐气分发（composer 契约：flow 级失败无信封走异常通道，正常完成必携 N 气
            // 子结果）：先整体解析再逐行落库——任一行缺子结果即契约破裂，先于任何行落终态抛出，
            // 整批走通用异常通道 FAILED，杜绝「部分行已 SUCCESS、部分行无终态」的半分发状态
            final Map<Long, CheckResult> gasSubResults = qcEnum == QualityControlTypeEnum.MULTI_ZERO_CHECK
                    && result instanceof MultiZeroCheckResult
                    ? resolveGasSubResults(records, ((MultiZeroCheckResult) result).getGasResults())
                    : null;
            for (QcmRecord record : records) {
                if (gasSubResults != null) {
                    dispatchMultiGasRow(owner, record, gasSubResults.get(record.getId()),
                            result.getPhaseRecords(), formatter, logParams, qcEnum, executorMap,
                            flowStartMillis, targetAnalyzerMissing, calibratorMissing);
                    continue;
                }
                // 编排器已在 result 上附带 phaseRecords，须入库，勿先 throw 否则 exceptionally 无法拿到 result
                if (result.isException()) {
                    // 裸结果（ExecutorResultBase 精确类型，非 CheckResult 子类，如零点/跨度收到的裸异常完成）
                    // 走 stub 落库：format 通道按 CheckResult 强转会 ClassCastException，phaseRecords 也会丢
                    String resultContentJson = isBareExecutorResultOutcome(result)
                            ? formatter.formatStub(owner.core, logParams, result,
                                    record.getQualityControlType(), record.getId())
                            : formatter.format(owner.core, logParams, result,
                            record.getQualityControlType(), record.getId());
                    String eval = result.getErrorMessage() != null && !result.getErrorMessage().isEmpty()
                            ? result.getErrorMessage()
                            : result.getResultMessage();
                    // 停止者留痕：composer 把停止异常经 handle 转成结果对象完成 future，
                    // 本分支才是被停的真实落库通道；仅「原文案 + 本批有停止者暂存」双条件成立才换算，
                    // 普通执行失败（同分支落库）不得冒名成被停
                    boolean stoppedByOperator = stopOperator != null && QcmExecutionOrchestrator.COMPOSER_STOP_EVALUATION.equals(eval);
                    if (stoppedByOperator) {
                        eval = stopEvaluation(stopOperator);
                        // 行内留痕矩阵 §7：updated_by 同落 displayOperator，
                        // 压掉创建期残留在 record 对象上、会被 update 原样回写的旧 updatedBy
                        record.setUpdatedBy(stopOperator);
                    }
                    record.setExecutionLog(resultContentJson);
                    record.setResultEvaluation(eval != null ? eval : "校准过程异常");
                    record.setEndTime(owner.recordService.resolveTerminalEndTime(record.getId()));
                    record.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode().intValue());
                    owner.recordService.updateQcmRecord(record);
                    // 冻结晚于通用 update：停止行必须把操作者穿透进冻结层，否则 updated_by 被覆写回系统账号
                    owner.freezeSnapshotFromLog(record, resultContentJson, qcEnum, flowStartMillis,
                            stoppedByOperator ? stopOperator : null);
                    executorMap.remove(record.getId());
                    continue;
                }
                String resultContentJson = formatter.format(owner.core, logParams, result,
                        record.getQualityControlType(), record.getId());
                String message;
                ExecutionStatusEnum status;
                if (result.isPass()) {
                    log.info("Calibration task completed successfully.");
                    // 标准质控沿用旧语义：完成即 SUCCESS（未通过也 SUCCESS）；人工核查：未通过 FAILED
                    message = auditLikeFailureOnNotPass
                            ? "校准任务成功完成 " + result.getResultMessage()
                            : "校准任务完成，且已通过 " + result.getResultMessage();
                    status = ExecutionStatusEnum.SUCCESS;
                } else {
                    log.error("Calibration task not pass: " + result.getResultMessage());
                    message = auditLikeFailureOnNotPass
                            ? "校准任务未通过 " + result.getResultMessage()
                            : "校准任务完成，但未通过 " + result.getResultMessage();
                    status = auditLikeFailureOnNotPass ? ExecutionStatusEnum.FAILED : ExecutionStatusEnum.SUCCESS;
                }
                if (targetAnalyzerMissing || calibratorMissing) {
                    // 降级运行：流程已按完整时序执行完毕（人工操作/人工视检节拍），终态一律 SUCCESS
                    // 留痕进报告——FAILED 行会被报告过滤，降级执行不能缺席报告（含人工核查未通过的场景）。
                    // 两者并存记 TARGET_ANALYZER_MISSING：闭域枚举不记组合串，数据无效比产气跳过更根本
                    //（完整缺失集在 execution_log.statusMap.missingDevices，供详情/备注解释）
                    status = ExecutionStatusEnum.SUCCESS;
                    record.setFailureReason(targetAnalyzerMissing
                            ? QcmExecutionOrchestrator.TARGET_ANALYZER_MISSING_REASON : QcmExecutionOrchestrator.CALIBRATOR_MISSING_REASON);
                    message = message + degradedRunSuffix(targetAnalyzerMissing, calibratorMissing);
                }
                record.setEndTime(Instant.now());
                record.setExecutionLog(resultContentJson);
                record.setResultEvaluation(message);
                record.setExecutionStatus(status.getCode().intValue());
                owner.recordService.updateQcmRecord(record);
                owner.freezeSnapshotFromLog(record, resultContentJson, qcEnum, flowStartMillis, null);
                executorMap.remove(record.getId());
            }
        }).exceptionally(ex -> {
            Throwable cause = ex;
            if (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            final String stopOperator = owner.takeStopOperator(records);
            if (cause instanceof ExecutorStoppedException) {
                ExecutorStoppedException stopped = (ExecutorStoppedException) cause;
                for (QcmRecord record : records) {
                    ExecutorResultBase stopResult = stopped.toResult();
                    if (stopOperator != null) {
                        // 停止者留痕：异常分支可辨「确为被停」，直接换算来源文案；
                        // updated_by 同步落 displayOperator（行内留痕矩阵 §7）
                        stopResult.setErrorMessage(stopEvaluation(stopOperator));
                        record.setUpdatedBy(stopOperator);
                    }
                    AbstractCalibrationFlow flow = (AbstractCalibrationFlow) executorMap.remove(record.getId());
                    stopResult.setPhaseRecords(phaseRecordsOf(flow));
                    String resultContentJson = formatter.formatStub(owner.core, logParams, stopResult,
                            record.getQualityControlType(), record.getId());
                    record.setExecutionLog(resultContentJson);
                    record.setResultEvaluation(stopResult.getErrorMessage());
                    record.setEndTime(owner.recordService.resolveTerminalEndTime(record.getId()));
                    record.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode().intValue());
                    owner.recordService.updateQcmRecord(record);
                    // 冻结晚于通用 update：停止行把操作者穿透进冻结层（行内留痕矩阵 §7）
                    owner.freezeSnapshotFromLog(record, resultContentJson, qcEnum, flowStartMillis, stopOperator);
                }
                return null;
            }
            // 异常不 throw 进 future，一律落库终态 FAILED + execution_log 留因
            // 带 full stack：thenAccept 内部抛出的异常会包成 CompletionException 进本分支，
            // 只打 message 无法定位（2026-08-23 失败分支 NPE 无栈排查实证）
            log.error("Calibration task executed exception", ex);
            String message = "校准任务过程异常 " + QcmExecutionOrchestrator.cleanExceptionMessage(cause);
            for (QcmRecord record : records) {
                ExecutorResultBase stub = new ExecutorResultBase(false, true);
                stub.setErrorMessage(QcmExecutionOrchestrator.cleanExceptionMessage(cause));
                AbstractCalibrationFlow flow = (AbstractCalibrationFlow) executorMap.remove(record.getId());
                stub.setPhaseRecords(phaseRecordsOf(flow));
                String resultContentJson = formatter.formatStub(owner.core, logParams, stub,
                        record.getQualityControlType(), record.getId());
                record.setExecutionLog(resultContentJson);
                record.setResultEvaluation(message);
                record.setEndTime(Instant.now());
                record.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode().intValue());
                owner.recordService.updateQcmRecord(record);
                owner.freezeSnapshotFromLog(record, resultContentJson, qcEnum, flowStartMillis, null);
            }
            return null;
        });
    }

    /**
     * multi 信封子结果前置解析：record.parameter（受理时定格的气种代码）→ 参数名 → composer
     * gas key → 信封子结果，键为 recordId。任一行解析不出（代码不可反查 / 信封缺该气）都是
     * composer 契约破裂——先于任何行落终态抛 IllegalStateException，整批走通用异常通道。
     */
    private static Map<Long, CheckResult> resolveGasSubResults(List<QcmRecord> records,
                                                               Map<String, CheckResult> gasResultsByGasKey) {
        Map<Long, CheckResult> resolved = new LinkedHashMap<>();
        for (QcmRecord record : records) {
            String parameterName = ParameterEnum.getNameByCode(record.getParameter());
            if (parameterName == null) {
                throw new IllegalStateException("multi 逐气分发失败：气种代码无法反查参数名 recordId="
                        + record.getId() + " parameter=" + record.getParameter());
            }
            String gasKey = LogicDeviceBindingIds.composerGasKeyFromParameterName(parameterName);
            CheckResult sub = gasResultsByGasKey.get(gasKey);
            if (sub == null) {
                throw new IllegalStateException("multi 信封缺少该气子结果（composer 契约破裂）recordId="
                        + record.getId() + " gasKey=" + gasKey);
            }
            resolved.put(record.getId(), sub);
        }
        return resolved;
    }

    /**
     * multi 单行台账分发：该气子结果独立判终态——出局线（status==null && isException，勿用
     * 枚举比较判定）走 stub 落 FAILED+原因，他行互不拖累；正常线按 isPass 落 SUCCESS（标准
     * 质控语义：完成即 SUCCESS，未通过也 SUCCESS）。子结果共享信封级 phaseRecords（时间线
     * 唯一事实源），logParams 的 parameter/gas 指向本行气种。信封级降级缺失标记对批内各行
     * 同真，沿用单结果路径的留痕语义。
     */
    private static void dispatchMultiGasRow(QcmExecutionOrchestrator owner, QcmRecord record, CheckResult sub,
                                            List<PhaseExecutionRecord> sharedPhaseRecords, QcResultFormatter formatter,
                                            Map<String, Object> logParams, QualityControlTypeEnum qcEnum,
                                            Map<Long, Object> executorMap, long flowStartMillis,
                                            boolean targetAnalyzerMissing, boolean calibratorMissing) {
        sub.setPhaseRecords(sharedPhaseRecords);
        Map<String, Object> rowParams = new LinkedHashMap<>(logParams);
        String parameterName = ParameterEnum.getNameByCode(record.getParameter());
        rowParams.put("parameter", parameterName);
        rowParams.put("gas", parameterName);
        if (sub.getStatus() == null && sub.isException()) {
            // 线级出局：该气 flow 线异常收场（无判定数据），FAILED+原因留痕；原因缺失给固定说明
            String reason = sub.getErrorMessage();
            String stubJson = formatter.formatStub(owner.core, rowParams, sub,
                    record.getQualityControlType(), record.getId());
            record.setExecutionLog(stubJson);
            record.setResultEvaluation(reason != null && !reason.isEmpty() ? reason : "该气线执行出局，无判定数据");
            record.setEndTime(Instant.now());
            record.setExecutionStatus(ExecutionStatusEnum.FAILED.getCode().intValue());
            owner.recordService.updateQcmRecord(record);
            owner.freezeSnapshotFromLog(record, stubJson, qcEnum, flowStartMillis, null);
            executorMap.remove(record.getId());
            return;
        }
        String resultContentJson = formatter.format(owner.core, rowParams, sub,
                record.getQualityControlType(), record.getId());
        String message = sub.isPass()
                ? "校准任务完成，且已通过 " + sub.getResultMessage()
                : "校准任务完成，但未通过 " + sub.getResultMessage();
        if (targetAnalyzerMissing || calibratorMissing) {
            // 降级运行留痕与单结果路径同构：终态 SUCCESS 进报告，结构化原因+文案后缀
            record.setFailureReason(targetAnalyzerMissing
                    ? QcmExecutionOrchestrator.TARGET_ANALYZER_MISSING_REASON : QcmExecutionOrchestrator.CALIBRATOR_MISSING_REASON);
            message = message + degradedRunSuffix(targetAnalyzerMissing, calibratorMissing);
        }
        record.setEndTime(Instant.now());
        record.setExecutionLog(resultContentJson);
        record.setResultEvaluation(message);
        record.setExecutionStatus(ExecutionStatusEnum.SUCCESS.getCode().intValue());
        owner.recordService.updateQcmRecord(record);
        owner.freezeSnapshotFromLog(record, resultContentJson, qcEnum, flowStartMillis, null);
        executorMap.remove(record.getId());
    }

    /** 裸结果判定（平移自旧 Task）：精确 ExecutorResultBase 类型（非 CheckResult 等子类）。 */
    private static boolean isBareExecutorResultOutcome(Object resultObj) {
        if (!(resultObj instanceof ExecutorResultBase)) { return false; }
        ExecutorResultBase result = (ExecutorResultBase) resultObj;
        return result != null && result.getClass() == ExecutorResultBase.class;
    }

    /** flow 阶段时间线 → PhaseExecutionRecord 列表（stop/异常落库留因用）；flow 为 null 返回空表。 */
    private static List<PhaseExecutionRecord> phaseRecordsOf(Object flowObj) {
        List<PhaseExecutionRecord> recs = new ArrayList<>();
        if (flowObj == null) {
            return recs;
        }
        AbstractCalibrationFlow flow = (AbstractCalibrationFlow) flowObj;
        for (PhaseInfo pi : flow.getExecutorPhases()) {
            if (pi == null) {
                continue;
            }
            recs.add(new PhaseExecutionRecord(
                    pi.getId(), pi.getDisplayName(), pi.getStartInstant(), pi.getEndInstant(),
                    pi.getEstimatedSeconds()));
        }
        return recs;
    }

    /** 停止终态文案（行内留痕矩阵 §7）：把操作者带进「被停」语义，来源可辨。 */
    private static String stopEvaluation(String displayOperator) {
        return "流程被 " + displayOperator + " 手动终止";
    }

    /**
     * 降级运行评定后缀（拼进 result_evaluation，人工操作/人工视检措辞，非故障语义）：
     * 缺设备是部署形态不是设备故障，文案按「本次由人工承担哪段操作」表述。
     */
    private static String degradedRunSuffix(boolean targetAnalyzerMissing, boolean calibratorMissing) {
        StringBuilder sb = new StringBuilder();
        if (targetAnalyzerMissing) {
            sb.append("；监测仪器未配置，人工视检（监测数据无效）");
        }
        if (calibratorMissing) {
            sb.append("；校准仪未配置，产气由人工操作");
        }
        return sb.toString();
    }
}
