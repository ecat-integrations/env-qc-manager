package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.EcatCore;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.GasInfoSaveDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.GasTraceRowDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.CylinderArchiveSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto.GasSettingAddDto;
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds.EntryId;
import com.ecat.integration.EnvQualityControlManagerIntegration.logic.LogicDeviceBindingIds.GasKey;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.LogicDeviceReportSupport;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import com.ecat.integration.logicdevice.LogicState.ILogicAttribute;
import com.ruoyi.common.core.domain.AjaxResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 钢瓶标气浓度读写业务：从 GasSettingController 下沉的设备解析与浓度读写逻辑。
 *
 * <p>浓度读写目标为站点校准仪 / 标准气逻辑设备上由 {@link LogicDevice#getAttrDefs()} 解析出的浓度属性。</p>
 *
 * @author coffee
 */
@Slf4j
@Service
public class GasSettingService {

    private static final List<String> GAS_LABELS = Arrays.asList("SO2", "NO", "CO", "O3");

    @Autowired
    private EcatCore core;

    /** 气体名称闭集（与 GasSetting 前端卡片词汇一致）；闭集外属非法请求，硬拒。 */
    private static final List<String> GAS_INFO_CODES = Arrays.asList("SO2", "NO2", "CO", "O3");

    /**
     * 标气溯源行列表（恒 4 行 SO2/NO2/CO/O3）：直读 airstation 钢瓶逻辑设备档案属性
     * （方案 A 2026-08-23，真相源=设备属性非 qcm 自建表）；O3 无钢瓶供应三要素 null。
     */
    public List<GasTraceRowDto> listGasInfo() {
        List<GasTraceRowDto> rows = new ArrayList<>();
        for (String name : GAS_INFO_CODES) {
            CylinderArchiveSupport.GasTrace trace = CylinderArchiveSupport.readArchiveByName(core, name);
            rows.add(GasTraceRowDto.builder()
                    .gasCode(name)
                    .gasSource(trace == null ? null : trace.gasSource)
                    .gasNo(trace == null ? null : trace.cylinderId)
                    .gasConcentration(trace == null ? null : trace.concentration)
                    .unit(trace == null ? null : trace.concentrationUnit)
                    .build());
        }
        return rows;
    }

    /**
     * 换瓶登记：转写来源/编号到 airstation 钢瓶逻辑设备档案属性（与手写 attr 编辑同通道，
     * 同持久化机制；qcm 不落自有表）。gasCode 闭集外 400；O3 无钢瓶 400。
     */
    public AjaxResult saveGasInfo(GasInfoSaveDto dto, String username) {
        String name = dto.getGasCode() != null ? dto.getGasCode().trim() : null;
        if (name == null || !GAS_INFO_CODES.contains(name)) {
            return AjaxResult.error(400, "gasCode 非法，合法值: " + GAS_INFO_CODES);
        }
        if ("O3".equals(name)) {
            return AjaxResult.error(400, "O3 为发生器供气无钢瓶档案，不支持溯源登记");
        }
        boolean ok = CylinderArchiveSupport.writeTraceByName(core, name,
                trimToNull(dto.getGasSource()), trimToNull(dto.getGasNo()));
        if (!ok) {
            return AjaxResult.error(500, "钢瓶档案写入失败（设备未注册或属性写入被拒）");
        }
        return AjaxResult.success("保存成功");
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 查询四种标气（SO2/NO/CO/O3）的浓度设置行：优先校准仪上的钢瓶属性，
     * 否则回落标准气逻辑设备的浓度属性；支持按 deviceId（逻辑 ID 或映射物理 ID）过滤。
     */
    public List<Map<String, Object>> listGasSettings(String deviceId) {
        DeviceRegistry deviceRegistry = core.getDeviceRegistry();

        LogicDevice calibrator = (LogicDevice) LogicDeviceReportSupport.airstationDevice(core, EntryId.Station.CALIBRATOR);

        List<Map<String, Object>> settingsList = new ArrayList<>();
        for (String gasLabel : GAS_LABELS) {
            String attrId = LogicDeviceReportSupport.resolveCalibratorCylinderAttrId(core, gasLabel);
            LogicDevice targetLd;
            if (attrId != null) {
                targetLd = calibrator;
            } else {
                targetLd = (LogicDevice) LogicDeviceReportSupport.airstationDevice(core, EntryId.Station.standardGas(GasKey.O3));
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
                    log.warn("读取校准仪浓度属性失败: logicDeviceId={}, attrId={}", targetLd.getId(), attrId, e);
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
                    log.warn("读取物理设备浓度属性失败: physId={}, attrId={}", physId, attrId, e);
                    setting.put("concentration", "设备读取异常");
                    setting.put("unit", "");
                }
            } else {
                setting.put("concentration", "获取失败");
                setting.put("unit", "");
            }
            settingsList.add(setting);
        }
        return settingsList;
    }

    /**
     * 设置钢瓶气浓度：先做数值范围校验（ppb 数值且 &gt;0），非法直接 400；
     * 再定位目标逻辑设备（校准仪优先，否则标准气钢瓶）写入浓度属性。
     */
    public AjaxResult setGasConcentration(GasSettingAddDto dto) {
        Double concentrationPpb;
        try {
            concentrationPpb = parseConcentrationPpb(dto.getValue());
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(400, e.getMessage());
        }

        String attrId = dto.getId();
        LogicDevice calibrator = (LogicDevice) LogicDeviceReportSupport.airstationDevice(core, EntryId.Station.CALIBRATOR);

        LogicDevice target;
        if (calibrator != null && LogicDeviceReportSupport.attributeDefinedOn(calibrator, attrId)) {
            target = resolveCalibrator(dto.getDeviceId());
        } else {
            target = resolveStandardGasCylinder(dto.getDeviceId());
        }

        if (target == null) {
            return AjaxResult.error(400, "未找到对应逻辑设备");
        }

        ILogicAttribute<?> attr = target.getAttrMap() != null
                ? target.getAttrMap().get(attrId)
                : null;
        if (attr == null) {
            return AjaxResult.error(400, "逻辑设备上无该属性: " + attrId);
        }

        try {
            attr.setDisplayValue(dto.getValue()).join();
        } catch (Exception e) {
            log.warn("设置标气浓度失败: logicDeviceId={}, attrId={}, value={}",
                    target.getId(), attrId, dto.getValue(), e);
            return AjaxResult.error(500, "设置标气浓度失败");
        }
        return AjaxResult.success("设置成功");
    }

    /**
     * 校验并解析标气浓度（ppb）：必须是可解析数值且 &gt;0。
     *
     * @throws IllegalArgumentException 非数值或非正数时（message 直接作为 400 响应文案）
     */
    static Double parseConcentrationPpb(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("标气浓度不能为空");
        }
        double v;
        try {
            v = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("标气浓度必须为数值: " + raw);
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalArgumentException("标气浓度必须为有效数值: " + raw);
        }
        if (v <= 0) {
            throw new IllegalArgumentException("标气浓度必须大于0: " + raw);
        }
        return v;
    }

    private LogicDevice resolveCalibrator(String requestDeviceId) {
        LogicDevice calibrator = (LogicDevice) LogicDeviceReportSupport.airstationDevice(core, EntryId.Station.CALIBRATOR);
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
        LogicDevice direct = (LogicDevice) LogicDeviceReportSupport.airstationDevice(core, requestDeviceId);
        if (direct != null && LogicDeviceReportSupport.resolveStdGasConcentrationAttrId(direct) != null) {
            return direct;
        }
        for (String inst : Arrays.asList(GasKey.SO2, GasKey.NO, GasKey.CO, GasKey.O3, "no2")) {
            LogicDevice cyl = (LogicDevice) LogicDeviceReportSupport.airstationDevice(core, EntryId.Station.standardGas(inst));
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
