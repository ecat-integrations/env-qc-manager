package com.ecat.integration.EnvQualityControlManagerIntegration.controller;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServletResponse;
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
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ecat.core.EcatCore;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.QcmRecordExportVo;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.RecordStopDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmRecordService;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.ReportGenerator;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ExecutionStatusEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.JsonUtils;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.PagedExportSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionPhasePayload;
import com.github.pagehelper.PageHelper;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;
import org.springframework.validation.annotation.Validated;

/**
 * 质控记录Controller
 *
 * <p><b>按 id 操作的归属校验说明（G-SEC-4 评估留档）</b>：本系统按单站单租户部署模型设计
 * （一个站点一套 ruoyi + qcm 实例，全站共享同一质控数据域），质控记录不存在跨租户归属维度。
 * 因此按 id 的操作不做归属过滤，id 越权面 = 已登录用户；由 {@code @PreAuthorize}
 * 的功能权限分层（quality_control:records:*）覆盖「谁能操作记录」，即访问控制的边界在功能权限而非数据行归属。
 * 若未来演进为多租户部署，需在此层补充租户归属校验。</p>
 *
 * @author caohongbo
 * @date 2025-05-26
 */
@RestController
@RequestMapping("/quality_control/records")
public class QcmRecordController extends BaseController
{
    @Autowired
    protected IQcmRecordService qcmRecordService;

    @Autowired
    private EcatCore core;

    /**
     * 查询质控记录列表
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:list')")
    @GetMapping("/list")
    public TableDataInfo list(QcmRecord query)
    {
        startPage();
        List<QcmRecord> list = qcmRecordService.selectQcmRecordList(query);
        return getDataTable(list);
    }

    /**
     * 导出质控记录列表（AC-C7：分批取数。记录表含 execution_log 大字段，逐页加载后即时转轻量 VO，
     * 任一时刻内存仅持一页原始记录 + 全量 VO，替代旧「单条 SQL 全表载入」）
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:export')")
    @Log(title = "质控记录", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, QcmRecord query)
    {
        List<QcmRecordExportVo> vos = loadExportRows(query);
        writeExcel(response, vos);
    }

    /**
     * 分页循环拉全量（500 行/页，count 仅首页执行）。VO 与记录 1:1，页大小判定不受影响。
     * 独立方法便于单测 mock service 验证分批查询次数。
     */
    List<QcmRecordExportVo> loadExportRows(QcmRecord query)
    {
        return PagedExportSupport.loadAll((pageNum, pageSize) -> {
            PageHelper.startPage(pageNum, pageSize, pageNum == 1);
            try {
                // 实体 Instant 列在 VO 预格式化为 String（Asia/Shanghai），列集与旧导出一致
                return QcmRecordExportVo.fromList(qcmRecordService.selectQcmRecordList(query));
            } finally {
                PageHelper.clearPage();
            }
        });
    }

    /** Excel 写出独立钩子：单测覆写为空操作，绕开对真实 HttpServletResponse 的依赖。 */
    protected void writeExcel(HttpServletResponse response, List<QcmRecordExportVo> vos)
    {
        ExcelUtil<QcmRecordExportVo> util = new ExcelUtil<QcmRecordExportVo>(QcmRecordExportVo.class);
        util.exportExcel(response, vos, "质控记录数据");
    }

    /**
     * 获取质控记录详细信息
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(qcmRecordService.selectQcmRecordById(id));
    }

    /**
     * 单条成功质控记录的「质控结果」预览数据（与定时任务报表生成器同源 {@link ReportGenerator#buildSingleRecordPreviewPayload}）。
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:query')")
    @GetMapping(value = "/{id}/report_preview")
    public AjaxResult reportPreview(@PathVariable("id") Long id)
    {
        QcmRecord r = qcmRecordService.selectQcmRecordById(id);
        if (r == null) {
            return error("记录不存在");
        }
        if (r.getExecutionStatus() == null || r.getExecutionStatus().intValue() != ExecutionStatusEnum.SUCCESS.getCode().intValue()) {
            return error("仅成功结束的记录可查看质控结果");
        }
        try {
            return success(ReportGenerator.buildSingleRecordPreviewPayload(core, r));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.trim().isEmpty()) {
                msg = e.getClass().getSimpleName();
            }
            return error("生成质控结果预览失败：" + msg);
        }
    }

    /**
     * 单条质控记录执行阶段视图：运行中合并编排器实时阶段；已结束则使用 execution_log 中持久化的 {@code qcPhaseTimelines}。
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:query')")
    @GetMapping(value = "/{id}/execution_phases")
    public AjaxResult executionPhases(@PathVariable("id") Long id)
    {
        QcmRecord r = qcmRecordService.selectQcmRecordById(id);
        if (r == null) {
            return error("记录不存在");
        }
        return success(QualityControlExecutionPhasePayload.build(core, r));
    }

    /**
     * 下载与 {@link #reportPreview(Long)} 同源的质控结果 JSON（便于留档或二次处理）。
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:query')")
    @PostMapping(value = "/{id}/report_export")
    public void reportExport(@PathVariable("id") Long id, HttpServletResponse response) throws IOException
    {
        QcmRecord r = qcmRecordService.selectQcmRecordById(id);
        if (r == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("记录不存在");
            return;
        }
        if (r.getExecutionStatus() == null || r.getExecutionStatus().intValue() != ExecutionStatusEnum.SUCCESS.getCode().intValue()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("仅成功结束的记录可导出质控结果");
            return;
        }
        Map<String, Object> payload;
        try {
            payload = ReportGenerator.buildSingleRecordPreviewPayload(core, r);
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("导出失败：" + e.getMessage());
            return;
        }
        byte[] bytes = JsonUtils.toJsonString(payload).getBytes(StandardCharsets.UTF_8);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"qc_report_" + id + ".json\"");
        response.getOutputStream().write(bytes);
    }

    /**
     * 新增质控记录
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:add')")
    @Log(title = "质控记录", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody QcmRecord record)
    {
        record.setExecutionStatus(ExecutionStatusEnum.WAITING.getCode().intValue());
        record.setCreatedBy(getUsername());
        if (record.getUpdatedBy() == null) {
            record.setUpdatedBy(record.getCreatedBy());
        }
        return toAjax(qcmRecordService.insertQcmRecord(record));
    }

    /**
     * 修改质控记录
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:edit')")
    @Log(title = "质控记录", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody QcmRecord record)
    {
        record.setUpdatedBy(getUsername());
        return toAjax(qcmRecordService.updateQcmRecord(record));
    }

     /**
     * 中止质控记录
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:stop')")
    @Log(title = "质控记录", businessType = BusinessType.UPDATE)
    @PutMapping("/stop")
    public Map<String, Object> stop(@Validated @RequestBody RecordStopDto data)
    {
        return qcmRecordService.stopQcmRecord(data.getId());
    }

    /**
     * 删除质控记录
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:remove')")
    @Log(title = "质控记录", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(qcmRecordService.deleteQcmRecordByIds(ids));
    }
}
