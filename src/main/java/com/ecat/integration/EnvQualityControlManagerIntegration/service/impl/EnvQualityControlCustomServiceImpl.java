package com.ecat.integration.EnvQualityControlManagerIntegration.service.impl;

import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IIntegrationTaskManagement;
import com.ecat.core.Task.Task;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlCustomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * EnvQualityControlCustomServiceImpl
 *
 * @author caohongbo
 * @version 1.0
 * @description
 */

@Service
public class EnvQualityControlCustomServiceImpl implements IEnvQualityControlCustomService {

    @Autowired
    private EcatCore core;
    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    /**
     * 立即更换滤膜
     *
     * @param gas 气体类型
     * @param genGasTime 通气时间，单位秒
     * @param readDataCount 读取数据的次数
     * @param readDataSpan 读取数据间隔，单位秒，比如60秒读取一次
     * @param genGasConc 生成气体浓度，单位ppm
     *
     * @return 结果
     */
    @Override
    public boolean executeCustomAuditCheck(String gas, int genGasTime, int readDataCount, int readDataSpan, float genGasConc, String stdGasInPortName) {

        Map<String,  Object> parameters = new HashMap<>();
        parameters.put("triggerType", "1");  // 触发类型
        parameters.put("qualityControlType", "audit_span_check");  // 质控类型
        parameters.put("core", core);
        parameters.put("gas", gas);  // 气体类型
        parameters.put("genGasTime", genGasTime);  // 通气时间
        parameters.put("readDataSpan", readDataSpan);  // 读取间隔数据
        parameters.put("readDataCount", readDataCount);  // 读取数据次数
        parameters.put("genGasConc", genGasConc);  // 数据浓度
        parameters.put("stdGasInPortName", stdGasInPortName);  // 标气入口

        // 调用原版的执行任务的代码
        try {
            log.info("executeCustomAuditCheck, gas: {}, genGasTime: {}, readDataCount: {}, readDataSpan: {}, genGasConc: {}, stdGasInPortName: {}", gas, genGasTime, readDataCount, readDataSpan, genGasConc, stdGasInPortName);
            IIntegrationTaskManagement envQualityControlCustomTask = (IIntegrationTaskManagement) core.getIntegrationRegistry()
                    .getIntegration("integration-env-quality-control-manager");
            Task wantedTask = envQualityControlCustomTask.getTaskExecutor().getTask("EnvQualityControlCustomTask");
            wantedTask.execute(parameters);
            log.info("executeCustomAuditCheck, gas: {}, genGasTime: {}, readDataCount: {}, readDataSpan: {}, genGasConc: {}, stdGasInPortName: {}, execute success", gas, genGasTime, readDataCount, readDataSpan, genGasConc, stdGasInPortName);
            return true;
        } catch (RuntimeException e) {
            log.error("executeCustomAuditCheck, gas: {}, genGasTime: {}, readDataCount: {}, readDataSpan: {}, genGasConc: {}, stdGasInPortName: {}, execute failed, error: {}", gas, genGasTime, readDataCount, readDataSpan, genGasConc, stdGasInPortName, e.getMessage());
            return false;
        }
    }

}
