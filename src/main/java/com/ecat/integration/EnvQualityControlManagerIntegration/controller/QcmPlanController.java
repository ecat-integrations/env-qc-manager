package com.ecat.integration.EnvQualityControlManagerIntegration.controller;

import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanEstimateDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.PlanSaveDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmPlanService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 质控任务计划Controller（05 §2 端点与权限串 / FR-05-10 caller 经 SecurityUtils → service）。
 *
 * <p>校验失败（IllegalArgumentException）转 error（消息为 Validator 逐条错误拼接，前端逐条展示，
 * FR-01-30 不静默修正）。</p>
 *
 * @author coffee
 */
@RestController
@RequestMapping("/quality_control/plan")
public class QcmPlanController extends BaseController {

    @Autowired
    private IQcmPlanService qcmPlanService;

    /**
     * 查询计划列表（status/qcType/planName 筛选，FR-01-25；出参含调度摘要/next/last）
     */
    @PreAuthorize("@ss.hasPermi('quality_control:plan:list')")
    @GetMapping("/list")
    public TableDataInfo list(QcmPlan query) {
        startPage();
        List<QcmPlan> list = qcmPlanService.selectList(query);
        return getDataTable(list);
    }

    /**
     * 获取计划详细信息（含调度摘要与 next_fire_time）
     */
    @PreAuthorize("@ss.hasPermi('quality_control:plan:query')")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        try {
            return success(qcmPlanService.selectById(id));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 新增计划（dto.id 空；ONCE·IMMEDIATE 创建即触发）
     */
    @PreAuthorize("@ss.hasPermi('quality_control:plan:add')")
    @Log(title = "质控任务计划", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody PlanSaveDto dto) {
        try {
            return success(qcmPlanService.save(dto, getUsername()));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 编辑计划（限 ACTIVE/PAUSED，整行覆盖后重算 next）
     */
    @PreAuthorize("@ss.hasPermi('quality_control:plan:edit')")
    @Log(title = "质控任务计划", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody PlanSaveDto dto) {
        try {
            return success(qcmPlanService.save(dto, getUsername()));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 启用 / 暂停（FR-01-15 状态机；生命周期开关归入 edit 权限）
     */
    @PreAuthorize("@ss.hasPermi('quality_control:plan:edit')")
    @Log(title = "质控任务计划", businessType = BusinessType.UPDATE)
    @PutMapping("/status/{id}/{action}")
    public AjaxResult changeStatus(@PathVariable("id") Long id, @PathVariable("action") String action) {
        String target = "enable".equals(action) ? "ACTIVE" : "pause".equals(action) ? "PAUSED" : null;
        if (target == null) {
            return error("状态动作必须是 enable 或 pause");
        }
        try {
            qcmPlanService.changeStatus(id, target, getUsername());
            return success();
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 删除计划（物理删除，FR-01-18）
     */
    @PreAuthorize("@ss.hasPermi('quality_control:plan:remove')")
    @Log(title = "质控任务计划", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids) {
        try {
            for (Long id : ids) {
                qcmPlanService.delete(id, getUsername());
            }
            return success();
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 立即执行（FR-01-24 MANUAL 链路；返回 BatchResult 摘要：受理/互斥拒绝 + 批次与记录 id）
     */
    @PreAuthorize("@ss.hasPermi('quality_control:plan:run')")
    @Log(title = "质控任务计划", businessType = BusinessType.OTHER)
    @PostMapping("/run/{id}")
    public AjaxResult run(@PathVariable("id") Long id) {
        try {
            return success(qcmPlanService.runNow(id, getUsername()));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 阶段预估（FR-01-32：只读、不落库、不触发；composer 只读能力，同源公式）
     */
    @PreAuthorize("@ss.hasPermi('quality_control:plan:query')")
    @PostMapping("/estimate")
    public AjaxResult estimate(@RequestBody PlanEstimateDto dto) {
        try {
            return success(qcmPlanService.estimate(dto));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }
}
