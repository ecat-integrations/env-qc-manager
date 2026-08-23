package com.ecat.integration.EnvQualityControlManagerIntegration.controller;

import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.GasInfoSaveDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.GasSettingAddDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.GasSettingService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 钢瓶标气浓度：读写在站点校准仪 / 标准气逻辑设备上的浓度属性。
 * 设备解析与读写业务在 {@link GasSettingService}，本类只做 参数→service→响应 组装。
 *
 * @date 2025-05-26
 */
@RestController
@RequestMapping("/quality_control/gas_setting")
public class GasSettingController extends BaseController {

    @Autowired
    private GasSettingService gasSettingService;

    /**
     * 查询钢气瓶设置列表
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:list')")
    @GetMapping("/list")
    public TableDataInfo list(@RequestParam(value = "deviceId", required = false) String deviceId) {
        return getDataTable(gasSettingService.listGasSettings(deviceId));
    }

    /**
     * 设置钢瓶气浓度（浓度 ppb 数值校验在 service：非法 400 带 message）
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:add')")
    @Log(title = "设置钢瓶气浓度", businessType = BusinessType.UPDATE)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody GasSettingAddDto data) {
        return gasSettingService.setGasConcentration(data);
    }

    /**
     * 标气溯源行列表（方案 A：直读 airstation 钢瓶档案，恒 4 行）
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:list')")
    @GetMapping("/info")
    public AjaxResult gasInfoList() {
        return success(gasSettingService.listGasInfo());
    }

    /**
     * 换瓶登记（转写 airstation 钢瓶档案属性）；gasCode 闭集校验在 service
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:add')")
    @Log(title = "保存标气溯源配置", businessType = BusinessType.UPDATE)
    @PutMapping("/info")
    public AjaxResult saveGasInfo(@Validated @RequestBody GasInfoSaveDto dto) {
        return gasSettingService.saveGasInfo(dto, getUsername());
    }
}
