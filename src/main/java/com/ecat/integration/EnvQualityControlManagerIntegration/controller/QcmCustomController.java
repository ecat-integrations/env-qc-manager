package com.ecat.integration.EnvQualityControlManagerIntegration.controller;

import com.ecat.core.EcatCore;
import com.ecat.integration.EnvCalibrationComposerIntegration.EnvCalibrationComposerIntegration;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.AuditSpanCheckDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmCustomService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


// 实际业务接口
/**
 * QcmCustomController
 *
 * @author caohongbo
 * @version 1.0
 * @description
 */
@RestController
@RequestMapping("/quality_control/custom")
public class QcmCustomController {

    @Autowired
    private EcatCore core;

    @Autowired
    private IQcmCustomService qcmCustomService;

    /**
     * 立即执行自定义质控任务
     */
    @PreAuthorize("@ss.hasPermi('quality_control:custom:audit_span_check')")
    @Log(title = "质控管理", businessType = BusinessType.OTHER)
    @PostMapping("/audit_span_check")
    public AjaxResult executeAuditCheck(@Validated @RequestBody AuditSpanCheckDto queryParams) {
        boolean isSuccess = qcmCustomService.executeCustomAuditCheck(
                queryParams.getGas(), queryParams.getGenGasTime(), queryParams.getReadDataCount(),
                queryParams.getReadDataSpan(), queryParams.getGenGasConc(),
                queryParams.getStdGasInPortName(), queryParams.getTargetFlowLpm());
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
