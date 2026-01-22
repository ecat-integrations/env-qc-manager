package com.ecat.integration.EnvQualityControlManagerIntegration.service.impl;

import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.EcatCore;
import com.ecat.integration.EnvDeviceCalibrationIntegration.AuditCheckExecuteParam;
import com.ecat.integration.EnvDeviceCalibrationIntegration.EnvDeviceCalibrationIntegration;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlCustomService;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.EnvQualityControlCustomTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

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

    private AuditCheckExecuteParam params;
    private EnvDeviceCalibrationIntegration integration;
    private DeviceRegistry deviceRegistry;
    private ExecutorService executor;
    private Map<String, Object> configMap;
    private EnvQualityControlCustomTask envQualityControlCustomTask = new EnvQualityControlCustomTask();

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
            envQualityControlCustomTask.callExecuteImpl(parameters);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

}
