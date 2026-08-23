package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import com.ecat.core.EcatCore;
import com.ecat.core.State.AttrState;
import com.ecat.core.State.AttributeBase;
import com.ecat.core.State.UnitInfo;
import com.ecat.integration.logicdevice.LogicDevice.LogicDevice;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 钢瓶档案读写（真相源=airstation 标准气体钢瓶逻辑设备，方案 A 2026-08-23 定案）。
 *
 * <p>标气溯源三要素（来源/编号/浓度）不再由 qcm 自建表维护，直接读写
 * {@code logicdevice_station.standard_gas.{so2|nox|co}} 上的 standalone 业务属性
 * {@code gas_source / cylinder_id / gas_concentration}——与 asm snapshot 同一读取面
 * （{@code attr.getState()} 一次取不可变快照，无撕裂读）。换瓶登记即写设备属性，
 * qcm 执行完成时冻结读一次，读路径零关联。</p>
 *
 * <p>槽映射按 qcm {@link ParameterEnum} 数字代码：SO2→so2、NO2→nox（NOx 分析仪跨度
 * 校准用 NO 标气，用户定案）、CO→co、O3→无槽（臭氧发生器产生非钢瓶供应，瓶号/来源
 * 如实为空是正确语义）。</p>
 */
@Slf4j
public final class CylinderArchiveSupport {

    /** airstation 标准气体钢瓶逻辑设备 uniqueId 前缀（与 AsmSnapshotService 同一判据）。 */
    static final String STANDARD_GAS_UID_PREFIX = "logicdevice_station.standard_gas.";

    /** 档案属性 ID（与 logicdevice-airstation StandardGas 元数据同词汇）。 */
    static final String ATTR_GAS_SOURCE = "gas_source";
    static final String ATTR_CYLINDER_ID = "cylinder_id";
    static final String ATTR_GAS_CONCENTRATION = "gas_concentration";

    private CylinderArchiveSupport() {
    }

    /** 钢瓶档案读取结果（三要素 + 浓度单位；来源/编号 null=未登记）。 */
    public static final class GasTrace {
        public final String gasSource;
        public final String cylinderId;
        public final BigDecimal concentration;
        public final String concentrationUnit;

        public GasTrace(String gasSource, String cylinderId, BigDecimal concentration, String concentrationUnit) {
            this.gasSource = gasSource;
            this.cylinderId = cylinderId;
            this.concentration = concentration;
            this.concentrationUnit = concentrationUnit;
        }
    }

    /**
     * qcm 气体代码 → standard_gas 槽名；无钢瓶供应（O3）返回 null。
     */
    public static String standardGasSlotFor(String gasCode) {
        if (gasCode == null) {
            return null;
        }
        switch (gasCode) {
            case "1": return "so2";
            case "2": return "nox";
            case "4": return "co";
            default: return null; // "3"=O3 发生器供气，无钢瓶档案
        }
    }

    /**
     * REST/前端面名称词汇（SO2/NO2/CO/O3）→ 槽名；O3 无钢瓶返回 null。
     */
    public static String standardGasSlotForName(String gasName) {
        if (gasName == null) {
            return null;
        }
        switch (gasName) {
            case "SO2": return "so2";
            case "NO2": return "nox";
            case "CO": return "co";
            default: return null;
        }
    }

    /**
     * 读钢瓶档案（完成时冻结用；asm 同款 AttrState 单次读取）。
     *
     * @return 无槽（O3）/集成未加载/设备未注册时 null；属性存在但未登记时字段为 null
     */
    public static GasTrace readArchive(EcatCore core, String gasCode) {
        String slot = standardGasSlotFor(gasCode);
        return slot == null || core == null ? null : readArchiveOfSlot(core, slot);
    }

    private static GasTrace readArchiveOfSlot(EcatCore core, String slot) {
        LogicDevice device = LogicDeviceReportSupport.airstationDevice(core, STANDARD_GAS_UID_PREFIX + slot);
        if (device == null || device.getAttrs() == null) {
            log.debug("钢瓶档案读取跳过：槽 {} 逻辑设备未注册（正常于 airstation 未加载/未 provision）", slot);
            return null;
        }
        return new GasTrace(
                textStateOf(device, ATTR_GAS_SOURCE),
                textStateOf(device, ATTR_CYLINDER_ID),
                numericStateOf(device, ATTR_GAS_CONCENTRATION),
                unitOf(device, ATTR_GAS_CONCENTRATION));
    }

    /** 按名称词汇读档案（REST 配置面用）。 */
    public static GasTrace readArchiveByName(EcatCore core, String gasName) {
        String slot = standardGasSlotForName(gasName);
        return slot == null || core == null ? null : readArchiveOfSlot(core, slot);
    }

    /** 按名称词汇转写（REST 配置面用）。 */
    public static boolean writeTraceByName(EcatCore core, String gasName, String gasSource, String cylinderId) {
        String slot = standardGasSlotForName(gasName);
        return slot != null && core != null && writeTraceOfSlot(core, slot, gasSource, cylinderId);
    }

    /**
     * 换瓶登记：转写来源/编号到钢瓶逻辑设备属性（与手写 attr 编辑同通道，持久化同机制）。
     *
     * @return false=槽不存在/设备未注册/任一写入被拒
     */
    public static boolean writeTrace(EcatCore core, String gasCode, String gasSource, String cylinderId) {
        String slot = standardGasSlotFor(gasCode);
        return slot != null && core != null && writeTraceOfSlot(core, slot, gasSource, cylinderId);
    }

    private static boolean writeTraceOfSlot(EcatCore core, String slot, String gasSource, String cylinderId) {
        LogicDevice device = LogicDeviceReportSupport.airstationDevice(core, STANDARD_GAS_UID_PREFIX + slot);
        if (device == null || device.getAttrs() == null) {
            return false;
        }
        try {
            return await(writeText(device, ATTR_GAS_SOURCE, gasSource))
                    & await(writeText(device, ATTR_CYLINDER_ID, cylinderId));
        } catch (Exception e) {
            log.warn("钢瓶档案写入异常 slot={}: {}", slot, e.getMessage());
            return false;
        }
    }

    /** 取属性不可变状态；属性不存在或尚无状态快照（新属性从未写入）返回 null——未登记语义。 */
    private static AttrState<?> stateOf(LogicDevice device, String attrId) {
        AttributeBase<?> attr = device.getAttrs().get(attrId);
        if (attr == null) {
            return null;
        }
        // getState() 对从未 updateValue 的属性返回 null（2026-08-23 失败分支 NPE 实证）；
        // asm snapshot 同款判空（AsmSnapshotService.buildAttrs）
        return attr.getState();
    }

    private static String textStateOf(LogicDevice device, String attrId) {
        AttrState<?> state = stateOf(device, attrId);
        Object value = state == null ? null : state.getValue();
        // 未登记的「未设置」默认值显式可辨，原样返回（区分「没填」与「没这栏」）
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal numericStateOf(LogicDevice device, String attrId) {
        AttrState<?> state = stateOf(device, attrId);
        Object value = state == null ? null : state.getValue();
        return value instanceof Number ? BigDecimal.valueOf(((Number) value).doubleValue()) : null;
    }

    private static String unitOf(LogicDevice device, String attrId) {
        AttrState<?> state = stateOf(device, attrId);
        UnitInfo unit = state == null ? null : state.getDisplayUnit();
        return unit == null ? null : unit.getName();
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Boolean> writeText(LogicDevice device, String attrId, String value) {
        AttributeBase<?> attr = device.getAttrs().get(attrId);
        if (attr == null) {
            return CompletableFuture.completedFuture(false);
        }
        return (CompletableFuture<Boolean>) attr.setDisplayValue(value);
    }

    private static boolean await(CompletableFuture<Boolean> future) throws Exception {
        return Boolean.TRUE.equals(future.get(5, TimeUnit.SECONDS));
    }
}
