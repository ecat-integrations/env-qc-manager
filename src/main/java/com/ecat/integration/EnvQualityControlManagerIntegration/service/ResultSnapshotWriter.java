package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordKeyParam;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordPhase;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordPoint;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordKeyParamMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPhaseMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPointMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.CylinderArchiveSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.LogicDeviceReportSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.EcatCore;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 完成时结果快照写入器（§4.0 快照原则）：执行完成时刻把「判定标量 + 阶段时间线 + 关键参数
 * + 多点序列 + flow 关联」同事务冻结进 qcm_record 强类型列与三张子表——此后设备改名/计划
 * 修改/属性漂移均不影响历史，读侧零关联（hj212 补数等机器契约直读子表，不再解析 execution_log）。
 *
 * <p>单一职责：只做「解析产物 → 列/子表行」的映射与原子写入；解析（execution_log JSON →
 * 本类入参 DTO）由编排器统一完成一次后喂入，本类不二次解析。方法签名不引用任何 composer
 * 类型（G-BUG-13：Spring 内省在 ruoyi 类加载器下解析签名会 CNFE），执行体类型以 className
 * 字符串传入。</p>
 */
@Slf4j
@Service
public class ResultSnapshotWriter {

    /** 快照列更新人（回调由编排器 future 线程驱动，非 ruoyi 用户操作）。 */
    static final String SNAPSHOT_ACTOR = "qcm-orchestrator";

    private final QcmRecordMapper recordMapper;
    private final QcmRecordPhaseMapper phaseMapper;
    private final QcmRecordKeyParamMapper keyParamMapper;
    private final QcmRecordPointMapper pointMapper;
    /** 完成时冻结仪器名/SN 与钢瓶档案的一次性设备解析用（仅 freeze 临界段读取，不持有运行态） */
    private final EcatCore core;

    @Autowired
    public ResultSnapshotWriter(QcmRecordMapper recordMapper, QcmRecordPhaseMapper phaseMapper,
                                QcmRecordKeyParamMapper keyParamMapper, QcmRecordPointMapper pointMapper,
                                EcatCore core) {
        this.recordMapper = recordMapper;
        this.phaseMapper = phaseMapper;
        this.keyParamMapper = keyParamMapper;
        this.pointMapper = pointMapper;
        this.core = core;
    }

    /**
     * 完成时冻结结果快照（同事务）：判定标量 + flow 关联 → qcm_record 强类型列
     * （updateResultSnapshot）；阶段/关键参数/数据点 → 三子表 insertBatch。
     *
     * @param recordId              qcm_record.id
     * @param executorTypeClassName composer ExecutorType className（与 qc_type 业务枚举正交）
     * @param judgementMap          execution_log.result 解析产物（单点标量/多点序列/曲线全在）；
     *                               缺失键如实落 null，不猜默认值（严格模式）
     * @param phases                阶段时间线（qcPhaseTimelines），seq 按列表序
     * @param keyParams             关键参数快照（keyParametersSnapshot）
     * @param samplingStart         采样窗口起（keyParametersSamplingWindow.startTimeMillis）
     * @param samplingEnd           采样窗口止（keyParametersSamplingWindow.endTimeMillis）
     * @param flowStartEpochMillis  flow 启动时刻毫秒（编排器受理时捕获），用于 flow_execution_ref
     * @param gasCode               气体代码（qcm_record.parameter；§4.2 标气溯源：冻结时读
     *                               qcm_gas_info 当前配置，触发时刻的配置即溯源真相；无行/未录入如实 null）
     */
    @Transactional
    public void freezeResultSnapshot(long recordId, String executorTypeClassName,
                                     Map<String, Object> judgementMap, List<PhaseSnapshot> phases,
                                     List<KeyParamSnapshot> keyParams,
                                     Instant samplingStart, Instant samplingEnd,
                                     long flowStartEpochMillis, String gasCode) {
        QcmRecord row = new QcmRecord();
        row.setId(recordId);
        row.setStandardValue(decimalOrNull(judgementMap, "stdValue"));
        row.setMonitoringData(decimalOrNull(judgementMap, "deviceValue"));
        row.setCalculatedValue(decimalOrNull(judgementMap, "resultValue"));
        row.setCheckPassLimit(decimalOrNull(judgementMap, "checkPassLimit"));
        row.setCheckCalibLimit(decimalOrNull(judgementMap, "checkCalibLimit"));
        row.setIsPass(booleanOrNull(judgementMap, "isPass"));
        row.setSlope(decimalOrNull(judgementMap, "slope"));
        row.setIntercept(decimalOrNull(judgementMap, "intercept"));
        row.setCorrelation(decimalOrNull(judgementMap, "correlation"));
        row.setSamplingStartTime(samplingStart);
        row.setSamplingEndTime(samplingEnd);
        // 仪器识别+满量程完成时冻结（§4.0 快照原则收尾；读一次设备后落列，读路径零关联）
        String instrumentLabel = gasCode != null ? ParameterEnum.getNameByCode(gasCode) : null;
        DeviceBase analyzer = core != null && instrumentLabel != null
                ? resolveAnalyzerDevice(instrumentLabel) : null;
        if (analyzer != null) {
            row.setInstrumentName(analyzer.getName());
            row.setInstrumentNo(analyzer.getSn());
        }
        row.setFullScale(fullScaleDecimalFor(gasCode));

        // 标气溯源冻结（方案 A 2026-08-23）：真相源=airstation 钢瓶逻辑设备档案属性
        // （gas_source/cylinder_id/gas_concentration），完成时读一次 AttrState，此后换瓶不改历史；
        // O3 无钢瓶供应（发生器），溯源三列如实 null
        CylinderArchiveSupport.GasTrace trace = CylinderArchiveSupport.readArchive(core, gasCode);
        if (trace != null) {
            row.setGasSource(trace.gasSource);
            row.setGasNo(trace.cylinderId);
            row.setGasConcentration(trace.concentration);
            row.setGasConcentrationUnit(trace.concentrationUnit);
        }
        row.setFlowType(executorTypeClassName);
        row.setFlowExecutionRef(recordId + "@" + flowStartEpochMillis);
        row.setUpdateTime(Instant.now());
        row.setUpdatedBy(SNAPSHOT_ACTOR);
        recordMapper.updateResultSnapshot(row);

        if (phases != null && !phases.isEmpty()) {
            List<QcmRecordPhase> phaseRows = new ArrayList<>();
            for (int i = 0; i < phases.size(); i++) {
                PhaseSnapshot p = phases.get(i);
                QcmRecordPhase r = new QcmRecordPhase();
                r.setRecordId(recordId);
                r.setSeq(i);
                r.setPhaseCode(p.getCode());
                r.setPhaseName(p.getName());
                r.setEstimatedSeconds(p.getEstimatedSeconds());
                r.setStartTime(p.getStart());
                r.setEndTime(p.getEnd());
                phaseRows.add(r);
            }
            phaseMapper.insertBatch(phaseRows);
        }
        if (keyParams != null && !keyParams.isEmpty()) {
            List<QcmRecordKeyParam> keyRows = new ArrayList<>();
            for (int i = 0; i < keyParams.size(); i++) {
                KeyParamSnapshot k = keyParams.get(i);
                QcmRecordKeyParam r = new QcmRecordKeyParam();
                r.setRecordId(recordId);
                r.setSeq(i);
                r.setName(k.getName());
                r.setValue(k.getValue());
                r.setUnit(k.getUnit());
                r.setRefRange(k.getRefRange());
                keyRows.add(r);
            }
            keyParamMapper.insertBatch(keyRows);
        }
        List<?> stdSeries = seriesOrNull(judgementMap, "stdValues");
        List<?> deviceSeries = seriesOrNull(judgementMap, "deviceValues");
        if (stdSeries != null && deviceSeries != null && stdSeries.size() != deviceSeries.size()) {
            // 等长序列是多点判定的不变量；长度不等即数据损坏，硬抛不猜（严格模式）
            throw new IllegalArgumentException("stdValues/deviceValues 长度不等: "
                    + stdSeries.size() + " vs " + deviceSeries.size() + ", recordId=" + recordId);
        }
        int pointCount = Math.max(stdSeries != null ? stdSeries.size() : 0,
                deviceSeries != null ? deviceSeries.size() : 0);
        if (pointCount > 0) {
            List<QcmRecordPoint> pointRows = new ArrayList<>();
            for (int i = 0; i < pointCount; i++) {
                QcmRecordPoint r = new QcmRecordPoint();
                r.setRecordId(recordId);
                r.setSeq(i);
                r.setStdValue(stdSeries != null ? elementDecimal(stdSeries.get(i), "stdValues[" + i + "]") : null);
                r.setDeviceValue(deviceSeries != null
                        ? elementDecimal(deviceSeries.get(i), "deviceValues[" + i + "]") : null);
                pointRows.add(r);
            }
            pointMapper.insertBatch(pointRows);
        }
    }

    private static BigDecimal decimalOrNull(Map<String, Object> judgementMap, String key) {
        Object v = judgementMap != null ? judgementMap.get(key) : null;
        return v == null ? null : toBigDecimal(v, key);
    }

    private static Boolean booleanOrNull(Map<String, Object> judgementMap, String key) {
        Object v = judgementMap != null ? judgementMap.get(key) : null;
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return Boolean.parseBoolean((String) v);
        }
        throw new IllegalArgumentException("判定键 " + key + " 非布尔类型: " + v.getClass().getName());
    }

    private static List<?> seriesOrNull(Map<String, Object> judgementMap, String key) {
        Object v = judgementMap != null ? judgementMap.get(key) : null;
        if (v == null) {
            return null;
        }
        if (!(v instanceof List)) {
            throw new IllegalArgumentException("判定键 " + key + " 应为数组: " + v.getClass().getName());
        }
        return (List<?>) v;
    }

    private static BigDecimal elementDecimal(Object v, String where) {
        return v == null ? null : toBigDecimal(v, where);
    }

    private static BigDecimal toBigDecimal(Object v, String where) {
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        if (v instanceof Integer || v instanceof Long) {
            return BigDecimal.valueOf(((Number) v).longValue());
        }
        if (v instanceof Float) {
            // float 的 doubleValue 会带二进制尾差（101.2f→101.1999969...），须按 float 字面量字符串化
            return new BigDecimal(String.valueOf((Float) v));
        }
        if (v instanceof Double) {
            return new BigDecimal(String.valueOf((Double) v));
        }
        if (v instanceof String) {
            try {
                return new BigDecimal(((String) v).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("判定值非数值: " + where + "=" + v, e);
            }
        }
        throw new IllegalArgumentException("判定值非数值类型: " + where + " " + v.getClass().getName());
    }

    /** 阶段时间线快照（qcPhaseTimelines 元素），seq 由列表序决定。 */
    @Value
    public static class PhaseSnapshot {
        String code;
        String name;
        Integer estimatedSeconds;
        Instant start;
        Instant end;
    }

    /** 关键参数快照（keyParametersSnapshot 元素）；unit 可空（快照行可能把单位并入 value 呈现）。 */
    @Value
    public static class KeyParamSnapshot {
        String name;
        String value;
        String unit;
        String refRange;
    }

    /** 解析分析仪物理设备（复用报告侧同一解析路径，只在冻结时刻读一次）。 */
    private DeviceBase resolveAnalyzerDevice(String gasParameterName) {
        try {
            String deviceId = LogicDeviceReportSupport.resolveAnalyzerPhysicalDeviceId(core, gasParameterName);
            if (deviceId == null || deviceId.isEmpty()) {
                return null;
            }
            return core.getDeviceRegistry().getDeviceByID(deviceId);
        } catch (Exception e) {
            log.warn("仪器识别冻结跳过（解析失败不影响快照主体）: {} - {}", gasParameterName, e.getMessage());
            return null;
        }
    }

    /** 满量程冻结：与 Gen 零跨报告同源常量（CO=50ppm=50000ppb，其余 500ppb），统一 ppb 数值落列。 */
    private BigDecimal fullScaleDecimalFor(String gasCode) {
        if (gasCode == null) {
            return null;
        }
        return ParameterEnum.CO.getCode().equals(gasCode)
                ? BigDecimal.valueOf(50000L) : BigDecimal.valueOf(500L);
    }
}