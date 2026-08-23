package com.ecat.integration.EnvQualityControlManagerIntegration.controller;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmReportService;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.PagedExportSupport;
import com.github.pagehelper.PageHelper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.util.List;


/**
 * 环境质量控制报表Controller
 *
 * <p><b>按 id 操作的归属校验说明（G-SEC-4 评估留档）</b>：本系统按单站单租户部署模型设计
 * （一个站点一套 ruoyi + qcm 实例，全站共享同一质控数据域），报表不存在跨租户归属维度。
 * 因此按 id 的操作不做归属过滤，id 越权面 = 已登录用户；由 {@code @PreAuthorize}
 * 的功能权限分层（quality_control:report:*）覆盖「谁能操作报表」，即访问控制的边界在功能权限而非数据行归属。
 * 若未来演进为多租户部署，需在此层补充租户归属校验。</p>
 *
 * @author caohongbo
 * @date 2025-06-26
 */
@RestController
@RequestMapping("/quality_control/report")
public class QcmReportController extends BaseController
{
    @Autowired
    protected IQcmReportService qcmReportService;

    /**
     * 查询环境质量控制报表列表
     */
    @PreAuthorize("@ss.hasPermi('quality_control:report:list')")
    @GetMapping("/list")
    public TableDataInfo list(QcmReport query)
    {
        startPage();
        List<QcmReport> list = qcmReportService.findPage(query);
        return getDataTable(list);
    }

    /**
     * 导出环境质量控制报表列表（AC-C7：分批取数，每页一条 SQL，替代单条全量载入）
     */
    @PreAuthorize("@ss.hasPermi('quality_control:report:list')")
    @Log(title = "质量控制报表", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, QcmReport query)
    {
        List<QcmReport> list = loadExportRows(query);
        writeExcel(response, list);
    }

    /**
     * 分页循环拉全量（500 行/页）；count 仅首页执行一次。
     * 独立方法便于单测 mock service 验证分批查询次数，不经真实 Excel 写出。
     */
    List<QcmReport> loadExportRows(QcmReport query)
    {
        return PagedExportSupport.loadAll((pageNum, pageSize) -> {
            PageHelper.startPage(pageNum, pageSize, pageNum == 1);
            try {
                return qcmReportService.findPage(query);
            } finally {
                PageHelper.clearPage();
            }
        });
    }

    /** Excel 写出独立钩子：单测覆写为空操作，绕开对真实 HttpServletResponse 的依赖。 */
    protected void writeExcel(HttpServletResponse response, List<QcmReport> list)
    {
        ExcelUtil<QcmReport> util = new ExcelUtil<>(QcmReport.class);
        util.exportExcel(response, list, "质量控制报表数据");
    }

    /**
     * 获取质量控制报表详细信息
     */
    @PreAuthorize("@ss.hasPermi('quality_control:report:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(qcmReportService.getDetailById(id));
    }

    /**
     * 新增质量控制报表
     */
    @PreAuthorize("@ss.hasPermi('quality_control:report:add')")
    @Log(title = "质量控制报表", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody QcmReport report)
    {
        // 设置默认值
        if (report.getIsDiscarded() == null) {
            report.setIsDiscarded(false);
        }

        report.setCreatedBy(getUsername());
        if (report.getUpdatedBy() == null) {
            report.setUpdatedBy(report.getCreatedBy());
        }

        return toAjax(qcmReportService.save(report));
    }

    /**
     * 修改质量控制报表
     */
    @PreAuthorize("@ss.hasPermi('quality_control:report:edit')")
    @Log(title = "质量控制报表", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody QcmReport report)
    {
        report.setUpdatedBy(getUsername());
        return toAjax(qcmReportService.updateById(report));
    }

    /**
     * 删除质量控制报表
     */
    @PreAuthorize("@ss.hasPermi('quality_control:report:remove')")
    @Log(title = "质量控制报表", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable List<Long> ids)
    {
        return toAjax(qcmReportService.batchDiscard(ids, true));
    }
}
