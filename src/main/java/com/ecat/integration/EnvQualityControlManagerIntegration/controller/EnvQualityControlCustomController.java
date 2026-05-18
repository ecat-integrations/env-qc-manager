package com.ecat.integration.EnvQualityControlManagerIntegration.controller;

import com.ecat.core.EcatCore;
import com.ecat.integration.EnvCalibrationComposerIntegration.EnvCalibrationComposerIntegration;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlCustomService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

// 实际业务接口
/**
 * EnvQualityControlCustomController
 *
 * @author caohongbo
 * @version 1.0
 * @description
 */
@RestController
@RequestMapping("/quality_control/custom")
public class EnvQualityControlCustomController {

    @Autowired
    private EcatCore core;

    @Autowired
    private IEnvQualityControlCustomService envQualityControlCustomService;

    /**
     * 立即执行自定义质控任务
     */
    @PreAuthorize("@ss.hasPermi('quality_control:custom:audit_span_check')")
    @Log(title = "质控管理", businessType = BusinessType.OTHER)
    @PostMapping("/audit_span_check")
    public AjaxResult executeAuditCheck(@RequestBody Map<String,  Object> queryParams) {
        String gas = (String) queryParams.get("gas");
        int genGasTime = (int) queryParams.get("genGasTime");
        int readDataCount = (int) queryParams.get("readDataCount");
        int readDataSpan = (int) queryParams.get("readDataSpan");
        String genGasConcString = queryParams.get("genGasConc") + "";
        float genGasConc = Float.parseFloat(genGasConcString);
        String stdGasInPortName = (String) queryParams.get("stdGasInPortName");
        Double targetFlowLpm = null;
        Object tf = queryParams.get("targetFlowLpm");
        if (tf instanceof Number) {
            targetFlowLpm = ((Number) tf).doubleValue();
        } else if (tf != null) {
            try {
                targetFlowLpm = Double.parseDouble(tf.toString().trim());
            } catch (NumberFormatException ignored) {
                targetFlowLpm = null;
            }
        }

        boolean isSuccess = envQualityControlCustomService.executeCustomAuditCheck(
                gas, genGasTime, readDataCount, readDataSpan, genGasConc, stdGasInPortName, targetFlowLpm);
        if (isSuccess) {
            return AjaxResult.success("", isSuccess);
        } else {
            return AjaxResult.error("执行失败", isSuccess);
        }
    }

    /**
     * 查询是否有空闲的执行器
     */
    @PreAuthorize("@ss.hasPermi('quality_control:custom:is_executor_free')")
    @Log(title = "质控管理", businessType = BusinessType.OTHER)
    @GetMapping("/is_executor_free")
    public AjaxResult isCalibTaskRunning() {
        EnvCalibrationComposerIntegration integration = (EnvCalibrationComposerIntegration) core.getIntegrationRegistry().getIntegration("integration-env-calibration-composer");
        boolean isRunning = Boolean.TRUE.equals(integration.isRunning());
        if (isRunning) {
            return AjaxResult.warn("有执行中的任务正在执行");
        } else {
            return AjaxResult.success("");
        }
    }

}
