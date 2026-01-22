package com.ecat.integration.EnvQualityControlManagerIntegration.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlRecords;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlRecordsService;
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
