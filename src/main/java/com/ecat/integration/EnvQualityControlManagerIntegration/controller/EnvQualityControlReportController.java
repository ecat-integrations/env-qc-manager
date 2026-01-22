package com.ecat.integration.EnvQualityControlManagerIntegration.controller;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlReportService;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.util.List;


/**
 * EnvQualityControlReportController
 * 
 * @author caohongbo
 * @version 1.0
 */

/**
 * 环境质量控制报表Controller
 *
 * @author yourname
 * @date 2025-06-26
 */
@RestController
@RequestMapping("/quality_control/report")
public class EnvQualityControlReportController extends BaseController
{
    @Autowired
    private IEnvQualityControlReportService envQualityControlReportService;

    /**
     * 查询环境质量控制报表列表
     */
    // @PreAuthorize("@ss.hasPermi('quality_control:report:list')")
    @GetMapping("/list")
    public TableDataInfo list(EnvQualityControlReport envQualityControlReport)
    {
        startPage();
        List<EnvQualityControlReport> list = envQualityControlReportService.findPage(envQualityControlReport);
        return getDataTable(list);
    }

    /**
     * 导出环境质量控制报表列表
     */
    // @PreAuthorize("@ss.hasPermi('quality_control:report:export')")
    @Log(title = "质量控制报表", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, EnvQualityControlReport envQualityControlReport)
    {
        List<EnvQualityControlReport> list = envQualityControlReportService.findPage(envQualityControlReport);
        ExcelUtil<EnvQualityControlReport> util = new ExcelUtil<>(EnvQualityControlReport.class);
        util.exportExcel(response, list, "质量控制报表数据");
    }

    /**
     * 获取质量控制报表详细信息
     */
    // @PreAuthorize("@ss.hasPermi('quality_control:report:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(envQualityControlReportService.getDetailById(id));
    }

    /**
     * 新增质量控制报表
     */
    // @PreAuthorize("@ss.hasPermi('quality_control:report:add')")
    @Log(title = "质量控制报表", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody EnvQualityControlReport envQualityControlReport)
    {
        // 设置默认值
        if (envQualityControlReport.getIsDiscarded() == null) {
            envQualityControlReport.setIsDiscarded(false);
        }

        envQualityControlReport.setCreatedBy(getUsername());
        if (envQualityControlReport.getUpdatedBy() == null) {
            envQualityControlReport.setUpdatedBy(envQualityControlReport.getCreatedBy());
        }

        return toAjax(envQualityControlReportService.save(envQualityControlReport));
    }

    /**
     * 修改质量控制报表
     */
    // @PreAuthorize("@ss.hasPermi('quality_control:report:edit')")
    @Log(title = "质量控制报表", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody EnvQualityControlReport envQualityControlReport)
    {
        envQualityControlReport.setUpdatedBy(getUsername());
        return toAjax(envQualityControlReportService.updateById(envQualityControlReport));
    }

    /**
     * 删除质量控制报表
     */
    // @PreAuthorize("@ss.hasPermi('quality_control:report:remove')")
    @Log(title = "质量控制报表", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable List<Long> ids)
    {
        return toAjax(envQualityControlReportService.batchDiscard(ids, true));
    }
}
