package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordKeyParam;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordPhase;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecordPoint;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.CylinderArchiveSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.LogicDeviceReportSupport;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordKeyParamMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPhaseMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmRecordPointMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.ResultSnapshotWriter.KeyParamSnapshot;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.ResultSnapshotWriter.PhaseSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.EcatCore;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

/**
 * 完成时快照冻结写入器用例（§4.0）：判定标量 → qcm_record 强类型列逐字段映射；
 * 缺失键如实 null（严格模式）；多点序列 → qcm_record_point 行数与 seq 对齐。
 */
class ResultSnapshotWriterTest {

    private QcmRecordMapper recordMapper;
    private QcmRecordPhaseMapper phaseMapper;
    private QcmRecordKeyParamMapper keyParamMapper;
    private QcmRecordPointMapper pointMapper;
    private EcatCore core;
    private ResultSnapshotWriter writer;

    @BeforeEach
    void setUp() {
        recordMapper = mock(QcmRecordMapper.class);
        phaseMapper = mock(QcmRecordPhaseMapper.class);
        keyParamMapper = mock(QcmRecordKeyParamMapper.class);
        pointMapper = mock(QcmRecordPointMapper.class);
        when(recordMapper.updateResultSnapshot(any(QcmRecord.class))).thenReturn(1);
        when(phaseMapper.insertBatch(anyList())).thenReturn(1);
        when(keyParamMapper.insertBatch(anyList())).thenReturn(1);
        when(pointMapper.insertBatch(anyList())).thenReturn(1);
        core = mock(EcatCore.class);
        writer = new ResultSnapshotWriter(recordMapper, phaseMapper, keyParamMapper, pointMapper, core);
    }

    private static Map<String, Object> fullJudgement() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stdValue", 100.5f);
        m.put("deviceValue", 101.2f);
        m.put("resultValue", 0.7f);
        m.put("checkPassLimit", 2.0f);
        m.put("checkCalibLimit", 10.0f);
        m.put("isPass", Boolean.TRUE);
        m.put("slope", 0.998f);
        m.put("intercept", 0.12f);
        m.put("correlation", 0.9999f);
        return m;
    }

    @Test
    void fullJudgement_mappedToTypedColumns() {
        Instant s0 = Instant.ofEpochMilli(1_700_000_000_000L);
        Instant s1 = Instant.ofEpochMilli(1_700_000_060_000L);
        writer.freezeResultSnapshot(55L, "air.monitor.calibration.zero_check",
                fullJudgement(), Collections.emptyList(), Collections.emptyList(),
                s0, s1, 1_700_000_000_123L, "SO2", null);

        ArgumentCaptor<QcmRecord> captor = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordMapper).updateResultSnapshot(captor.capture());
        QcmRecord row = captor.getValue();
        assertEquals(55L, row.getId());
        assertEquals(new BigDecimal("100.5"), row.getStandardValue());
        assertEquals(new BigDecimal("101.2"), row.getMonitoringData());
        assertEquals(new BigDecimal("0.7"), row.getCalculatedValue());
        assertEquals(new BigDecimal("2.0"), row.getCheckPassLimit());
        assertEquals(new BigDecimal("10.0"), row.getCheckCalibLimit());
        assertEquals(Boolean.TRUE, row.getIsPass());
        assertEquals(new BigDecimal("0.998"), row.getSlope());
        assertEquals(new BigDecimal("0.12"), row.getIntercept());
        assertEquals(new BigDecimal("0.9999"), row.getCorrelation());
        assertEquals(s0, row.getSamplingStartTime());
        assertEquals(s1, row.getSamplingEndTime());
        assertEquals("air.monitor.calibration.zero_check", row.getFlowType());
        assertEquals("55@1700000000123", row.getFlowExecutionRef());
        assertNotNull(row.getUpdateTime());
        assertEquals("qcm-orchestrator", row.getUpdatedBy());
    }

    /**
     * 终态归属（行内留痕矩阵 §7）：停止行传 displayOperator → 冻结行 updated_by=操作者；
     * 传 null → 维持系统账号惯例（上方用例）。冻结晚于通用 update，归属必须在此层定型。
     */
    @Test
    void terminalActor_stoppedRowCarriesOperator() {
        writer.freezeResultSnapshot(31L, "air.monitor.calibration.span_check",
                Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(),
                null, null, 1L, "SO2", "scada@10.0.0.1:5025");
        ArgumentCaptor<QcmRecord> captor = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordMapper).updateResultSnapshot(captor.capture());
        assertEquals("scada@10.0.0.1:5025", captor.getValue().getUpdatedBy());
    }

    @Test
    void missingKeys_stayNull_noGuessing() {
        writer.freezeResultSnapshot(1L, "air.monitor.calibration.span_check",
                Collections.emptyMap(), null, null, null, null, 42L, null, null);
        ArgumentCaptor<QcmRecord> captor = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordMapper).updateResultSnapshot(captor.capture());
        QcmRecord row = captor.getValue();
        assertNull(row.getStandardValue());
        assertNull(row.getMonitoringData());
        assertNull(row.getCalculatedValue());
        assertNull(row.getCheckPassLimit());
        assertNull(row.getCheckCalibLimit());
        assertNull(row.getIsPass());
        assertNull(row.getSlope());
        assertNull(row.getIntercept());
        assertNull(row.getCorrelation());
        assertNull(row.getSamplingStartTime());
        assertNull(row.getSamplingEndTime());
    }

    @Test
    void phasesAndKeyParams_writtenAsChildRows_seqByListOrder() {
        Instant t0 = Instant.ofEpochMilli(1_700_000_000_000L);
        Instant t1 = Instant.ofEpochMilli(1_700_000_010_000L);
        List<PhaseSnapshot> phases = Arrays.asList(
                new PhaseSnapshot("zero", "零点稳定", 300, t0, t1),
                new PhaseSnapshot("check", "读数", 60, t1, t1.plusSeconds(60)));
        List<KeyParamSnapshot> keyParams = Arrays.asList(
                new KeyParamSnapshot("主浓度", "102.5 ppb", "ppb", "80~120"),
                new KeyParamSnapshot("流量", "1.02 L/min", "L/min", "0.9~1.1"));
        writer.freezeResultSnapshot(7L, "air.monitor.calibration.zero_check",
                Collections.emptyMap(), phases, keyParams, null, null, 1L, "SO2", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QcmRecordPhase>> phaseCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(phaseMapper).insertBatch(phaseCaptor.capture());
        List<QcmRecordPhase> phaseRows = phaseCaptor.getValue();
        assertEquals(2, phaseRows.size());
        assertEquals(7L, phaseRows.get(0).getRecordId());
        assertEquals(0, phaseRows.get(0).getSeq());
        assertEquals("zero", phaseRows.get(0).getPhaseCode());
        assertEquals("零点稳定", phaseRows.get(0).getPhaseName());
        assertEquals(Integer.valueOf(300), phaseRows.get(0).getEstimatedSeconds());
        assertEquals(t0, phaseRows.get(0).getStartTime());
        assertEquals(t1, phaseRows.get(0).getEndTime());
        assertEquals(1, phaseRows.get(1).getSeq());
        assertEquals("check", phaseRows.get(1).getPhaseCode());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QcmRecordKeyParam>> keyCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(keyParamMapper).insertBatch(keyCaptor.capture());
        List<QcmRecordKeyParam> keyRows = keyCaptor.getValue();
        assertEquals(2, keyRows.size());
        assertEquals(0, keyRows.get(0).getSeq());
        assertEquals("主浓度", keyRows.get(0).getName());
        assertEquals("102.5 ppb", keyRows.get(0).getValue());
        assertEquals("ppb", keyRows.get(0).getUnit());
        assertEquals("80~120", keyRows.get(0).getRefRange());
        assertEquals(1, keyRows.get(1).getSeq());
    }

    @Test
    void alignedSeries_writtenAsPoints_seqAligned() {
        Map<String, Object> judgement = new LinkedHashMap<>();
        judgement.put("stdValues", Arrays.asList(0f, 100f, 200f));
        judgement.put("deviceValues", Arrays.asList(0.5f, 101f, 199f));
        writer.freezeResultSnapshot(9L, "air.monitor.calibration.multi_check",
                judgement, Collections.emptyList(), Collections.emptyList(), null, null, 1L, "CO", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QcmRecordPoint>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(pointMapper).insertBatch(captor.capture());
        List<QcmRecordPoint> points = captor.getValue();
        assertEquals(3, points.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(9L, points.get(i).getRecordId());
            assertEquals(i, points.get(i).getSeq());
        }
        // float 字面量化后 0f→"0.0"（scale 与 "0" 不同），数值语义断言用 compareTo
        assertEquals(0, new BigDecimal("0").compareTo(points.get(0).getStdValue()));
        assertEquals(new BigDecimal("0.5"), points.get(0).getDeviceValue());
        assertEquals(0, new BigDecimal("200").compareTo(points.get(2).getStdValue()));
        assertEquals(0, new BigDecimal("199").compareTo(points.get(2).getDeviceValue()));
    }

    /** 精密度类只有 deviceValues（无 stdValues）：std 列如实 null，逐点仍落行。 */
    @Test
    void deviceOnlySeries_stdNullPerPoint() {
        Map<String, Object> judgement = new LinkedHashMap<>();
        judgement.put("deviceValues", Arrays.asList(10.1f, 10.2f));
        writer.freezeResultSnapshot(10L, "air.monitor.calibration.precision_check",
                judgement, Collections.emptyList(), Collections.emptyList(), null, null, 1L, "CO", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QcmRecordPoint>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(pointMapper).insertBatch(captor.capture());
        List<QcmRecordPoint> points = captor.getValue();
        assertEquals(2, points.size());
        assertNull(points.get(0).getStdValue());
        assertEquals(new BigDecimal("10.1"), points.get(0).getDeviceValue());
    }

    /** 严格模式：stdValues 与 deviceValues 都在但长度不等 = 数据损坏，硬抛不猜。 */
    @Test
    void mismatchedSeriesLength_throws() {
        Map<String, Object> judgement = new LinkedHashMap<>();
        judgement.put("stdValues", Arrays.asList(0f, 100f));
        judgement.put("deviceValues", Collections.singletonList(1f));
        try {
            writer.freezeResultSnapshot(11L, "x", judgement,
                    Collections.emptyList(), Collections.emptyList(), null, null, 1L, "SO2", null);
            throw new AssertionError("长度不等应硬抛");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("stdValues/deviceValues"));
        }
        verify(pointMapper, never()).insertBatch(anyList());
    }

    /** 方案 A：冻结时读 airstation 钢瓶档案（GasTrace）写溯源四列。 */
    @Test
    void gasArchive_frozenIntoTraceColumns() {
        try (MockedStatic<CylinderArchiveSupport> support = Mockito.mockStatic(CylinderArchiveSupport.class)) {
            support.when(() -> CylinderArchiveSupport.readArchive(core, "1"))
                    .thenReturn(new CylinderArchiveSupport.GasTrace(
                            "国家标准物质研究所", "GBW-E-050123", new BigDecimal("50"), "ppm"));

            writer.freezeResultSnapshot(21L, "air.monitor.calibration.span_check",
                    fullJudgement(), Collections.emptyList(), Collections.emptyList(),
                    null, null, 1L, "1", null);
        }
        ArgumentCaptor<QcmRecord> captor = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordMapper).updateResultSnapshot(captor.capture());
        QcmRecord row = captor.getValue();
        assertEquals("国家标准物质研究所", row.getGasSource());
        assertEquals("GBW-E-050123", row.getGasNo());
        assertEquals(new BigDecimal("50"), row.getGasConcentration());
        assertEquals("ppm", row.getGasConcentrationUnit());
    }

    /** 无档案（O3 发生器供气/槽未 provision）：溯源四列如实 null，不伪造。 */
    @Test
    void gasArchiveMissing_traceColumnsStayNull() {
        try (MockedStatic<CylinderArchiveSupport> support = Mockito.mockStatic(CylinderArchiveSupport.class)) {
            support.when(() -> CylinderArchiveSupport.readArchive(core, "3")).thenReturn(null);

            writer.freezeResultSnapshot(22L, "air.monitor.calibration.span_check",
                    fullJudgement(), Collections.emptyList(), Collections.emptyList(),
                    null, null, 1L, "3", null);
        }
        ArgumentCaptor<QcmRecord> captor = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordMapper).updateResultSnapshot(captor.capture());
        QcmRecord row = captor.getValue();
        assertNull(row.getGasSource());
        assertNull(row.getGasNo());
        assertNull(row.getGasConcentration());
        assertNull(row.getGasConcentrationUnit());
    }

    /** @Transactional 必须在（主行 UPDATE + 三子表 INSERT 原子，§4.0 同事务冻结）。 */
    @Test
    void freezeMethod_isTransactional() throws Exception {
        Method m = ResultSnapshotWriter.class.getMethod("freezeResultSnapshot",
                long.class, String.class, Map.class, List.class, List.class,
                Instant.class, Instant.class, long.class, String.class, String.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertNotNull(tx, "freezeResultSnapshot 必须 @Transactional");
    }
    /** §4.0 仪器识别+满量程冻结：满量程与 Gen 零跨报告同源（CO=50ppm=50000ppb 归一 ppb，其余 500ppb）。 */
    @Test
    void fullScale_frozenPpbNormalized_CO50000Others500() {
        // gas 档案与本用例无关：stub 掉（real readArchive 会走 mock core 的 null registry）
        try (MockedStatic<CylinderArchiveSupport> gas = Mockito.mockStatic(CylinderArchiveSupport.class)) {
            gas.when(() -> CylinderArchiveSupport.readArchive(any(), any())).thenReturn(null);
        fullScale_frozen_body();
        }
    }

    private void fullScale_frozen_body() {
        writer.freezeResultSnapshot(21L, "air.monitor.calibration.span_check",
                Collections.emptyMap(), null, null, null, null, 1L, "1", null); // SO2 code
        ArgumentCaptor<QcmRecord> captor1 = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordMapper, Mockito.times(1)).updateResultSnapshot(captor1.capture());
        assertEquals(new BigDecimal("500"), captor1.getValue().getFullScale());

        writer.freezeResultSnapshot(22L, "air.monitor.calibration.span_check",
                Collections.emptyMap(), null, null, null, null, 1L, "4", null); // CO code
        ArgumentCaptor<QcmRecord> captor2 = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordMapper, Mockito.times(2)).updateResultSnapshot(captor2.capture());
        assertEquals(new BigDecimal("50000"), captor2.getValue().getFullScale());

        writer.freezeResultSnapshot(23L, "air.monitor.calibration.span_check",
                Collections.emptyMap(), null, null, null, null, 1L, null, null);
        ArgumentCaptor<QcmRecord> captor3 = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordMapper, Mockito.times(3)).updateResultSnapshot(captor3.capture());
        assertNull(captor3.getValue().getFullScale());
    }

    /** §4.0 仪器识别冻结：解析成功 → 设备名/SN 落列（只在冻结时刻读一次设备）。 */
    @Test
    void instrumentIdentity_frozenAtCompletion() {
        DeviceBase analyzer = mock(DeviceBase.class);
        when(analyzer.getName()).thenReturn("SO2分析仪-沙河站");
        when(analyzer.getSn()).thenReturn("XH2000E-001");
        DeviceRegistry registry = mock(DeviceRegistry.class);
        when(core.getDeviceRegistry()).thenReturn(registry);
        when(registry.getDeviceByID("dev-so2-1")).thenReturn(analyzer);

        try (MockedStatic<LogicDeviceReportSupport> support =
                     Mockito.mockStatic(LogicDeviceReportSupport.class)) {
            support.when(() -> LogicDeviceReportSupport.resolveAnalyzerPhysicalDeviceId(core, "SO2"))
                    .thenReturn("dev-so2-1");
            writer.freezeResultSnapshot(31L, "air.monitor.calibration.span_check",
                    Collections.emptyMap(), null, null, null, null, 1L, "1", null);
        }
        ArgumentCaptor<QcmRecord> captor = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordMapper).updateResultSnapshot(captor.capture());
        assertEquals("SO2分析仪-沙河站", captor.getValue().getInstrumentName());
        assertEquals("XH2000E-001", captor.getValue().getInstrumentNo());
    }

    /** §4.0 仪器识别解析失败（无绑定/无 mappings）→ 仪器两列如实 null，快照主体照常写入。 */
    @Test
    void instrumentResolutionMissing_skipsWithoutBreakingSnapshot() {
        try (MockedStatic<LogicDeviceReportSupport> support =
                     Mockito.mockStatic(LogicDeviceReportSupport.class)) {
            support.when(() -> LogicDeviceReportSupport.resolveAnalyzerPhysicalDeviceId(core, "CO"))
                    .thenReturn(null);
            writer.freezeResultSnapshot(32L, "air.monitor.calibration.span_check",
                    Collections.emptyMap(), null, null, null, null, 1L, "4", null);
        }
        ArgumentCaptor<QcmRecord> captor = ArgumentCaptor.forClass(QcmRecord.class);
        verify(recordMapper).updateResultSnapshot(captor.capture());
        assertNull(captor.getValue().getInstrumentName());
        assertNull(captor.getValue().getInstrumentNo());
        assertNotNull(captor.getValue().getFullScale());
    }
    /** 契约回归：writer 把 qcm_record.parameter 数字代码原样传给档案读取（翻译在 support 槽映射）。 */
    @Test
    void gasArchive_calledWithRawParameterCode() {
        try (MockedStatic<CylinderArchiveSupport> support = Mockito.mockStatic(CylinderArchiveSupport.class)) {
            support.when(() -> CylinderArchiveSupport.readArchive(core, "4")).thenReturn(null);
            writer.freezeResultSnapshot(42L, "air.monitor.calibration.span_check",
                    Collections.emptyMap(), null, null, null, null, 1L, "4", null);
            support.verify(() -> CylinderArchiveSupport.readArchive(core, "4"));
        }
        verify(recordMapper).updateResultSnapshot(any(QcmRecord.class));
    }
}