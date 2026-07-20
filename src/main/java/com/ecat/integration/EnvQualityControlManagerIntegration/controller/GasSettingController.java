package com.ecat.integration.EnvQualityControlManagerIntegration.controller;

import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.EcatCore;
import com.ecat.core.LogicDevice.LogicDevice;
import com.ecat.core.LogicState.ILogicAttribute;
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds.EntryId;
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds.GasKey;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.LogicDeviceReportSupport;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 钢瓶标气浓度：读写在站点校准仪 / 标准气逻辑设备上由 {@link LogicDevice#getAttrDefs()} 解析出的浓度属性。
 *
 * @date 2025-05-26
 */
@RestController
@RequestMapping("/quality_control/gas_setting")
public class GasSettingController extends BaseController {

    private static final List<String> GAS_LABELS = Arrays.asList("SO2", "NO", "CO", "O3");

    @Autowired
    private EcatCore core;

    /**
     * 查询钢气瓶设置列表
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:list')")
    @GetMapping("/list")
    public TableDataInfo list(@RequestParam(value = "deviceId", required = false) String deviceId) {
        DeviceRegistry deviceRegistry = core.getDeviceRegistry();

        LogicDevice calibrator = (LogicDevice) LogicDeviceReportSupport.airstationDevice(core,EntryId.Station.CALIBRATOR);

        List<Map<String, Object>> settingsList = new ArrayList<>();
        for (String gasLabel : GAS_LABELS) {
            String attrId = LogicDeviceReportSupport.resolveCalibratorCylinderAttrId(core, gasLabel);
            LogicDevice targetLd;
            if (attrId != null) {
                targetLd = calibrator;
            } else {
                targetLd = (LogicDevice) LogicDeviceReportSupport.airstationDevice(core,EntryId.Station.standardGas(GasKey.O3));
                attrId = LogicDeviceReportSupport.resolveStdGasConcentrationAttrId(targetLd);
            }

            if (targetLd == null || attrId == null) {
                continue;
            }

            String physId = LogicDeviceReportSupport.getFirstMappedPhysicalDeviceId(targetLd);
            if (deviceId != null && !deviceId.equals(targetLd.getId())
                    && (physId == null || !deviceId.equals(physId))) {
                continue;
            }

            ILogicAttribute<?> attr = targetLd.getAttrMap() != null
                    ? targetLd.getAttrMap().get(attrId)
                    : null;

            Map<String, Object> setting = new HashMap<>();
            setting.put("id", attrId);
            setting.put("deviceId", physId != null ? physId : targetLd.getId());
            setting.put("logicDeviceId", targetLd.getId());
            setting.put("gases", Collections.singletonList(gasLabel));
            setting.put("name", gasLabel + "标气浓度");

            DeviceBase physical = physId != null ? deviceRegistry.getDeviceByID(physId) : null;
            if (attr != null) {
                try {
                    setting.put("concentration", attr.getDisplayValue());
                    setting.put("unit", attr.getDisplayUnitStr());
                } catch (Exception e) {
                    setting.put("concentration", "设备读取异常");
                    setting.put("unit", "");
                }
            } else if (physical != null && physical.getAttrs() != null
                    && physical.getAttrs().get(attrId) != null) {
                try {
                    setting.put("concentration",
                            physical.getAttrs().get(attrId).getDisplayValue());
                    setting.put("unit",
                            physical.getAttrs().get(attrId).getDisplayUnitStr());
                } catch (Exception e) {
                    setting.put("concentration", "设备读取异常");
                    setting.put("unit", "");
                }
            } else {
                setting.put("concentration", "获取失败");
                setting.put("unit", "");
            }
            settingsList.add(setting);
        }

        startPage();
        return getDataTable(settingsList);
    }

    /**
     * 设置钢瓶气浓度
     */
    @PreAuthorize("@ss.hasPermi('quality_control:records:add')")
    @Log(title = "设置钢瓶气浓度", businessType = BusinessType.UPDATE)
    @PostMapping
    public Map<String, Object> add(@RequestBody Map<String, Object> data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("msg", "设置成功");

        String id = data.get("id").toString();
        String value = data.get("value").toString();
        String requestDeviceId = data.get("deviceId") != null ? data.get("deviceId").toString() : null;

        LogicDevice calibrator = (LogicDevice) LogicDeviceReportSupport.airstationDevice(core,EntryId.Station.CALIBRATOR);

        LogicDevice target;
        if (calibrator != null && LogicDeviceReportSupport.attributeDefinedOn(calibrator, id)) {
            target = resolveCalibrator(requestDeviceId);
        } else {
            target = resolveStandardGasCylinder(requestDeviceId);
        }

        if (target == null) {
            result.put("code", 400);
            result.put("msg", "未找到对应逻辑设备");
            return result;
        }

        ILogicAttribute<?> attr = target.getAttrMap() != null
                ? target.getAttrMap().get(id)
                : null;
        if (attr == null) {
            result.put("code", 400);
            result.put("msg", "逻辑设备上无该属性: " + id);
            return result;
        }

        try {
            attr.setDisplayValue(value).join();
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "设置标气浓度失败");
        }
        return result;
    }

    private LogicDevice resolveCalibrator(String requestDeviceId) {
        LogicDevice calibrator = (LogicDevice) LogicDeviceReportSupport.airstationDevice(core,EntryId.Station.CALIBRATOR);
        if (calibrator == null) {
            return null;
        }
        if (requestDeviceId == null) {
            return calibrator;
        }
        if (requestDeviceId.equals(calibrator.getId())) {
            return calibrator;
        }
        String phys = LogicDeviceReportSupport.getFirstMappedPhysicalDeviceId(calibrator);
        if (requestDeviceId.equals(phys)) {
            return calibrator;
        }
        return null;
    }

    private LogicDevice resolveStandardGasCylinder(String requestDeviceId) {
        if (requestDeviceId == null) {
            return null;
        }
        LogicDevice direct = (LogicDevice) LogicDeviceReportSupport.airstationDevice(core,requestDeviceId);
        if (direct != null && LogicDeviceReportSupport.resolveStdGasConcentrationAttrId(direct) != null) {
            return direct;
        }
        for (String inst : Arrays.asList(GasKey.SO2, GasKey.NO, GasKey.CO, GasKey.O3, "no2")) {
            LogicDevice cyl = (LogicDevice) LogicDeviceReportSupport.airstationDevice(core,EntryId.Station.standardGas(inst));
            if (cyl == null) {
                continue;
            }
            String phys = LogicDeviceReportSupport.getFirstMappedPhysicalDeviceId(cyl);
            if (requestDeviceId.equals(cyl.getId()) || requestDeviceId.equals(phys)) {
                return cyl;
            }
        }
        return null;
    }
}
