package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.core.EcatCore;
import com.ecat.integration.EnvCalibrationComposerIntegration.ExecutorResultBase;

import java.util.Map;

/**
 * 执行结果 → execution_log JSON 的格式化缝（编排器回调用）：
 * 两套 constructResult 语义（标准质控/人工核查）暂不合并（5.2 再收口 R2），
 * 编排器按 qualityControlType code 路由到对应实现。
 */
public interface QcResultFormatter {

    /** 是否负责该质控类型 code（QualityControlTypeEnum.code） */
    boolean supports(String qualityControlTypeCode);

    /**
     * 格式化正常/异常执行结果（含关键参数快照抓取）。
     *
     * @param qcRecordId 小于 0 时不写入关键参数快照（失败路径等）
     */
    String format(EcatCore core, Map<String, Object> params, ExecutorResultBase result,
                  String qualityControlTypeCode, long qcRecordId);

    /**
     * 格式化裸 ExecutorResultBase（停止/启动失败等无具体结果对象的桩结果）：
     * 标准质控的 constructResult 对零点/跨度会 (CheckResult) 强转失败，桩结果走本方法。
     */
    String formatStub(EcatCore core, Map<String, Object> params, ExecutorResultBase stub,
                      String qualityControlTypeCode, long qcRecordId);
}
