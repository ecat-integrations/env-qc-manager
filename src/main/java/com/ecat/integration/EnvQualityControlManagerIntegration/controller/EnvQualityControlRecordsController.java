package com.ecat.integration.EnvQualityControlManagerIntegration.controller;

import java.util.HashMap;
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
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlRecords;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlRecordsService;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.ReportGenerator;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ExecutionStatusEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.JsonUtils;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlExecutionPhasePayload;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.core.page.TableDataInfo;

/**
 * 质控记录Controller
 * 
 * @author caohongbo
 * @date 2025-05-26
 */
@RestController
@RequestMapping("/quality_control/records")
public class EnvQualityControlRecordsController extends BaseController
{
    @Autowired
    private IEnvQualityControlRecordsService envQualityControlRecordsService;

    @Autowired
    private EcatCore core;

    /**
     * 查询质控记录列表
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:list')")
    @GetMapping("/list")
    public TableDataInfo list(EnvQualityControlRecords envQualityControlRecords)
    {
        startPage();
        List<EnvQualityControlRecords> list = envQualityControlRecordsService.selectEnvQualityControlRecordsList(envQualityControlRecords);
        return getDataTable(list);
    }

    /**
     * 导出质控记录列表
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:export')")
    @Log(title = "质控记录", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, EnvQualityControlRecords envQualityControlRecords)
    {
        List<EnvQualityControlRecords> list = envQualityControlRecordsService.selectEnvQualityControlRecordsList(envQualityControlRecords);
        ExcelUtil<EnvQualityControlRecords> util = new ExcelUtil<EnvQualityControlRecords>(EnvQualityControlRecords.class);
        util.exportExcel(response, list, "质控记录数据");
    }

    /**
     * 获取质控记录详细信息
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(envQualityControlRecordsService.selectEnvQualityControlRecordsById(id));
    }

    /**
     * 单条成功质控记录的「质控结果」预览数据（与定时任务报表生成器同源 {@link ReportGenerator#buildSingleRecordPreviewPayload}）。
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:query')")
    @GetMapping(value = "/{id}/report_preview")
    public AjaxResult reportPreview(@PathVariable("id") Long id)
    {
        EnvQualityControlRecords r = envQualityControlRecordsService.selectEnvQualityControlRecordsById(id);
        if (r == null) {
            return error("记录不存在");
        }
        if (!Objects.equals(r.getExecutionStatus(), ExecutionStatusEnum.SUCCESS.getCode())) {
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
        EnvQualityControlRecords r = envQualityControlRecordsService.selectEnvQualityControlRecordsById(id);
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
        EnvQualityControlRecords r = envQualityControlRecordsService.selectEnvQualityControlRecordsById(id);
        if (r == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("记录不存在");
            return;
        }
        if (!Objects.equals(r.getExecutionStatus(), ExecutionStatusEnum.SUCCESS.getCode())) {
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
    public AjaxResult add(@RequestBody EnvQualityControlRecords envQualityControlRecords)
    {
        long initialExecutionStatus = 0L;
        envQualityControlRecords.setExecutionStatus(initialExecutionStatus);
        envQualityControlRecords.setCreatedBy(getUsername());
        if (envQualityControlRecords.getUpdatedBy() == null) {
            envQualityControlRecords.setUpdatedBy(envQualityControlRecords.getCreatedBy());
        }
        return toAjax(envQualityControlRecordsService.insertEnvQualityControlRecords(envQualityControlRecords));
    }

    /**
     * 修改质控记录
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:edit')")
    @Log(title = "质控记录", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody EnvQualityControlRecords envQualityControlRecords)
    {
        envQualityControlRecords.setUpdatedBy(getUsername());
        return toAjax(envQualityControlRecordsService.updateEnvQualityControlRecords(envQualityControlRecords));
    }

     /**
     * 中止质控记录
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:stop')")
    @Log(title = "质控记录", businessType = BusinessType.UPDATE)
    @PutMapping("/stop")
    public Map<String, Object> stop(@RequestBody Map<String, Object> data)
    {
        Map<String, Object> result = new HashMap<>();
        if(!data.containsKey("id")){
            result.put("code", 400);
            result.put("msg", "id不能为空");
            return result;
        }
        Long id = Long.valueOf(data.get("id").toString());
        result = envQualityControlRecordsService.stopEnvQualityControlRecords(id);
        return result;
    }

    /**
     * 删除质控记录
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:remove')")
    @Log(title = "质控记录", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(envQualityControlRecordsService.deleteEnvQualityControlRecordsByIds(ids));
    }
}
