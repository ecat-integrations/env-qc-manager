package com.ecat.integration.EnvQualityControlManagerIntegration.controller;

import com.ecat.core.Config.ParameterMappingResolver;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ecat.core.EcatCore;
import java.util.*;

/**
 * 钢气瓶设置
 * 
 * @date 2025-05-26
 */
@RestController
@RequestMapping("/quality_control/gas_setting")
public class GasSettingController extends BaseController
{
    @Autowired
    private EcatCore core;

    private final ParameterMappingResolver parameterMappingResolver = ParameterMappingResolver.getInstance();

    /**
     * 查询钢气瓶设置列表
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:list')")
    @GetMapping("/list")
    public TableDataInfo list(@RequestParam(value = "deviceId", required = false) String deviceId)
    {

        DeviceRegistry deviceRegistry = core.getDeviceRegistry();
        List<ParameterMappingResolver.DeviceAttributeMapping> mappings =
                parameterMappingResolver.getParameterMappings("std_gas_concentration",
                        devId -> core.getDeviceRegistry().getDeviceByID(devId));
        Set<String> allowedGases = new HashSet<>(Arrays.asList("SO2", "NO", "CO"));

        List<Map<String, Object>> settingsList = new ArrayList<>();
        for (ParameterMappingResolver.DeviceAttributeMapping mapping : mappings) {
            if (!mapping.getGases().isEmpty()) {
                boolean match = mapping.getGases().stream().anyMatch(allowedGases::contains);
                if (!match) {
                    continue;
                }
            } else if (deviceId == null) {
                continue;
            }

            String targetDeviceId = mapping.getDeviceId();
            if (deviceId != null && targetDeviceId != null && !deviceId.equals(targetDeviceId)) {
                continue;
            }
            if (targetDeviceId == null) {
                targetDeviceId = deviceId;
            }

            if (targetDeviceId == null) {
                continue;
            }

            DeviceBase device = targetDeviceId != null ? deviceRegistry.getDeviceByID(targetDeviceId) : null;
            Map<String, Object> setting = new HashMap<>();
            setting.put("id", mapping.getAttributeId());
            setting.put("deviceId", targetDeviceId);
            setting.put("gases", new ArrayList<>(mapping.getGases()));
            setting.put("name", buildSettingName(mapping));

            if (device != null && device.getAttrs() != null && device.getAttrs().get(mapping.getAttributeId()) != null) {
                try {
                    setting.put("concentration", device.getAttrs().get(mapping.getAttributeId()).getDisplayValue());
                } catch (Exception e) {
                    setting.put("concentration", "设备读取异常");
                }
                try {
                    setting.put("unit", device.getAttrs().get(mapping.getAttributeId()).getDisplayUnitStr());
                } catch (Exception e) {
                    setting.put("unit", "");
                }
            } else {
                setting.put("concentration", "获取失败");
                setting.put("unit", "");
            }
            settingsList.add(setting);
        }

        startPage(); // 启用分页
        return getDataTable(settingsList);
    }


    /**
     * 设置钢瓶气浓度
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:add')")
    @Log(title = "设置钢瓶气浓度", businessType = BusinessType.UPDATE)
    @PostMapping
    public Map<String,Object> add(@RequestBody Map<String,Object> data)
    {
        DeviceRegistry deviceRegistry = core.getDeviceRegistry();
        Map<String,Object> result = new HashMap<>();
        result.put("code",200);
        result.put("msg","设置成功");
        String id = data.get("id").toString();
        String value = data.get("value").toString();
        String requestDeviceId = data.get("deviceId") != null ? data.get("deviceId").toString() : null;
        Set<String> allowedGases = new HashSet<>(Arrays.asList("SO2", "NO", "CO"));

        List<ParameterMappingResolver.DeviceAttributeMapping> activeMappings =
                parameterMappingResolver.getParameterMappings("std_gas_concentration",
                        devId -> core.getDeviceRegistry().getDeviceByID(devId));

        Optional<ParameterMappingResolver.DeviceAttributeMapping> mappingOpt = activeMappings
                .stream()
                .filter(mapping -> mapping.getAttributeId().equals(id)
                        && (mapping.getGases().isEmpty() || mapping.getGases().stream().anyMatch(allowedGases::contains))
                        && (requestDeviceId == null || (mapping.getDeviceId() != null && mapping.getDeviceId().equals(requestDeviceId))))
                .findFirst();

        if (!mappingOpt.isPresent() && requestDeviceId == null) {
            mappingOpt = activeMappings
                    .stream()
                    .filter(mapping -> mapping.getAttributeId().equals(id)
                            && (mapping.getGases().isEmpty() || mapping.getGases().stream().anyMatch(allowedGases::contains)))
                    .findFirst();
        }

        if (!mappingOpt.isPresent()) {
            result.put("code",400);
            result.put("msg","未找到标气浓度设置");
        }
        else {
            ParameterMappingResolver.DeviceAttributeMapping mapping = mappingOpt.get();
            String targetDeviceId = mapping.getDeviceId() != null ? mapping.getDeviceId() : requestDeviceId;

            if (targetDeviceId == null) {
                result.put("code",400);
                result.put("msg","缺少设备ID");
                return result;
            }

            DeviceBase device = deviceRegistry.getDeviceByID(targetDeviceId);
            if (device == null || device.getAttrs() == null || device.getAttrs().get(id) == null) {
                result.put("code",400);
                result.put("msg","设备或属性不存在");
                return result;
            }

            try {
                device.getAttrs().get(id).setDisplayValue(value);
            } catch (Exception e) {
                result.put("code",500);
                result.put("msg","设置标气浓度失败");
            }
        }
        return result;
    }

    private String buildSettingName(ParameterMappingResolver.DeviceAttributeMapping mapping) {
        if (!mapping.getGases().isEmpty()) {
            String gas = mapping.getGases().iterator().next();
            return gas + "标气浓度";
        }
        return mapping.getAttributeId();
    }

}
