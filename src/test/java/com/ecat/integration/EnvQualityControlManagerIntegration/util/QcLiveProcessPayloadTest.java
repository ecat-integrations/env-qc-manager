package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.EnvAirDeviceManagerIntegration.EnvAirDeviceManagerIntegration;
import com.ecat.integration.EnvAirDeviceManagerIntegration.api.AdmParamKind;
import com.ecat.integration.EnvAirDeviceManagerIntegration.api.AirDeviceDataSdk;
import com.ecat.integration.EnvAirDeviceManagerIntegration.api.SdkParamKey;
import com.ecat.integration.EnvAirDeviceManagerIntegration.api.SdkParamMeta;
import com.ecat.integration.EnvAirDeviceManagerIntegration.api.SdkSampleRow;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 过程展示解析载荷用例（数据源全量改走 ADM SDK，方案二 v2）：SDK 消费链 + 响应契约关键风险点——
 * 多通道 series（NOx 三通道 isMain 判定）/ 仅执行中生成曲线 / CO↔其余目标单位 full key 构造 /
 * 单位全 key→短名转换 / ADM 未加载与目录零参数的 reason 通道 / markLine 单位换算。
 * 严格模式：SDK 拿不到必须 reason 显式说明，不静默兜底。
 */
class QcLiveProcessPayloadTest {

    private static final String ADM_COORDINATE = "com.ecat:integration-env-air-device-manager";

    /** 执行中 NOx 记录全链：目录 → queryLatest 快照 + queryRawSeries 多通道曲线 + markLine 一次读齐。 */
    @Test
    @SuppressWarnings("unchecked")
    void runningNoxRecord_multiChannelSeriesAndAttrs() {
        AirDeviceDataSdk sdk = mock(AirDeviceDataSdk.class);
        // NOx 仪目录：no/no2/nox 三 MONITOR 通道 + sample_flow 状态参数（ADM 域权威分类）
        when(sdk.listStatParams()).thenReturn(Arrays.asList(
                meta("logicdevice.nox", "no", "一氧化氮浓度", AdmParamKind.MONITOR),
                meta("logicdevice.nox", "no2", "二氧化氮浓度", AdmParamKind.MONITOR),
                meta("logicdevice.nox", "nox", "氮氧化物浓度", AdmParamKind.MONITOR),
                meta("logicdevice.nox", "sample_flow", "采样流量", AdmParamKind.STATUS)));
        // 最新行：no/nox 有值；no2 缺席（无行=如实 null 值行）；sample_flow 是 L/min 全 key
        when(sdk.queryLatest(anyList(), eq("AirVolumeUnit.PPB"))).thenReturn(Arrays.asList(
                sample("no", Instant.ofEpochMilli(1_700_000_000_000L), 15.5, "AirVolumeUnit.PPB",
                        Collections.singletonList("NORMAL")),
                sample("nox", Instant.ofEpochMilli(1_700_000_000_000L), 21.0, "AirVolumeUnit.PPB",
                        Collections.singletonList("NORMAL")),
                sample("sample_flow", Instant.ofEpochMilli(1_700_000_000_000L), 0.65,
                        "LiterFlowUnit.L_PER_MINUTE", Collections.singletonList("NORMAL"))));
        // 秒级序列：三 MONITOR 通道各 2 点（升序）；STATUS 参数不得进曲线
        when(sdk.queryRawSeries(anyList(), any(Instant.class), any(Instant.class), eq("AirVolumeUnit.PPB")))
                .thenReturn(Arrays.asList(
                        sample("no", Instant.ofEpochMilli(1_700_000_000_000L), 14.9, "AirVolumeUnit.PPB", null),
                        sample("no", Instant.ofEpochMilli(1_700_000_100_000L), 15.5, "AirVolumeUnit.PPB", null),
                        sample("no2", Instant.ofEpochMilli(1_700_000_000_000L), 2.1, "AirVolumeUnit.PPB", null),
                        sample("nox", Instant.ofEpochMilli(1_700_000_100_000L), 21.0, "AirVolumeUnit.PPB", null)));

        Instant taskStart = Instant.now().minus(Duration.ofMinutes(10));
        QcmRecord record = baseRecord("2", "1"); // NO2 质控落在 NOx 分析仪
        record.setStartTime(taskStart);
        record.setStandardValue(new BigDecimal("400.0"));
        EcatCore core = coreWithSdk(sdk);

        Map<String, Object> payload = QcLiveProcessPayload.build(core, record);

        assertEquals(1, payload.get("executionStatus"));
        Map<String, Object> analyzer = (Map<String, Object>) payload.get("analyzer");
        assertEquals("logicdevice.nox", analyzer.get("uniqueId"), "uniqueId 即 ADM 深链 indicator 词汇");
        assertNull(analyzer.get("name"), "执行中未冻结 instrument_name，如实 null（前端回退本地映射）");
        assertNull(analyzer.get("sn"));
        assertNull(payload.get("analyzerReason"));
        assertEquals("ppb", payload.get("curveUnit"));

        List<Map<String, Object>> series = (List<Map<String, Object>>) payload.get("series");
        assertEquals(3, series.size(), "MONITOR 通道全拉：no/no2/nox 三条");
        Map<String, Object> noSeries = series.stream()
                .filter(s -> "no".equals(s.get("attrId"))).findFirst().orElseThrow(AssertionError::new);
        assertTrue((Boolean) noSeries.get("isMain"), "主通道 = composer 采集键 no（NO2 质控读 NO 通道）");
        assertEquals("一氧化氮浓度", noSeries.get("name"));
        assertEquals("ppb", noSeries.get("unit"), "SDK 全 key 经 UnitInfoFactory 反解为短名");
        List<Map<String, Object>> points = (List<Map<String, Object>>) noSeries.get("points");
        assertEquals(2, points.size());
        assertEquals(Long.valueOf(1_700_000_000_000L), points.get(0).get("t"), "points.t = epochMillis");
        assertEquals(14.9, (Double) points.get(0).get("v"), 1e-9, "points.v 与 attrs.value 同型（number）");
        Map<String, Object> no2Series = series.stream()
                .filter(s -> "no2".equals(s.get("attrId"))).findFirst().orElseThrow(AssertionError::new);
        assertFalse((Boolean) no2Series.get("isMain"));
        Map<String, Object> flowSeries = null;
        for (Map<String, Object> s : series) {
            if ("sample_flow".equals(s.get("attrId"))) {
                flowSeries = s;
            }
        }
        assertNull(flowSeries, "STATUS 参数不进曲线（queryRawSeries 只喂 MONITOR）");

        Map<String, Object> window = (Map<String, Object>) payload.get("seriesWindow");
        assertEquals(taskStart.minus(Duration.ofMinutes(5)).toEpochMilli(),
                ((Number) window.get("start")).longValue(), "窗口起 = 任务开始 −5min 基线段");
        assertTrue(((Number) window.get("end")).longValue() >= taskStart.toEpochMilli());

        // 曲线查询键只含 MONITOR 参数（sample_flow 不得进 batch）
        ArgumentCaptor<List<SdkParamKey>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(sdk).queryRawSeries(keysCaptor.capture(), any(Instant.class), any(Instant.class), eq("AirVolumeUnit.PPB"));
        assertEquals(3, keysCaptor.getValue().size());

        List<Map<String, Object>> attrs = (List<Map<String, Object>>) payload.get("attrs");
        assertEquals(4, attrs.size(), "attrs = 全目录参数（MONITOR+STATUS）");
        Map<String, Object> noRow = attrs.stream()
                .filter(r -> "no".equals(r.get("id"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("MONITOR", noRow.get("kind"));
        assertTrue((Boolean) noRow.get("isMain"));
        assertEquals(15.5, (Double) noRow.get("value"), 1e-9, "value = SDK valueNum 数字直出");
        assertEquals("ppb", noRow.get("unit"));
        assertEquals(Collections.singletonList("NORMAL"), noRow.get("statuses"), "标记数组原样透传");
        assertEquals(Long.valueOf(1_700_000_000_000L), noRow.get("updateTime"));
        Map<String, Object> no2Row = attrs.stream()
                .filter(r -> "no2".equals(r.get("id"))).findFirst().orElseThrow(AssertionError::new);
        assertNull(no2Row.get("value"), "无最新行的参数如实 null 值行（不猜默认值）");
        assertNull(no2Row.get("statuses"));
        Map<String, Object> flowRow = attrs.stream()
                .filter(r -> "sample_flow".equals(r.get("id"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("STATUS", flowRow.get("kind"));
        assertEquals("L/min", flowRow.get("unit"), "非气态参数单位全 key 反解短名（LiterFlowUnit.L_PER_MINUTE）");
        assertFalse((Boolean) flowRow.get("isMain"));
        assertNull(payload.get("attrsReason"));

        Map<String, Object> markLine = (Map<String, Object>) payload.get("markLine");
        assertEquals(400.0, (Double) markLine.get("value"), 1e-6, "非 CO 标气 400ppb 原值画线");
        assertEquals("ppb", markLine.get("unit"));
        assertNull(payload.get("markLineNote"));
    }

    /** 完成时冻结的仪器识别列直通 analyzer.name/sn；已完成记录 series=null（不回查历史）。 */
    @Test
    @SuppressWarnings("unchecked")
    void completedRecord_usesFrozenColumnsAndSkipsSeries() {
        AirDeviceDataSdk sdk = mock(AirDeviceDataSdk.class);
        when(sdk.listStatParams()).thenReturn(Collections.singletonList(
                meta("logicdevice.so2", "so2", "SO2浓度", AdmParamKind.MONITOR)));
        when(sdk.queryLatest(anyList(), any())).thenReturn(Collections.singletonList(
                sample("so2", Instant.ofEpochMilli(1_700_000_000_000L), 101.2, "AirVolumeUnit.PPB",
                        Collections.singletonList("NORMAL"))));

        QcmRecord record = baseRecord("1", "1");
        record.setExecutionStatus(2);
        record.setStartTime(Instant.now().minus(Duration.ofMinutes(30)));
        record.setInstrumentName("SO2分析仪");
        record.setInstrumentNo("SN-SO2-001");

        Map<String, Object> payload = QcLiveProcessPayload.build(coreWithSdk(sdk), record);

        Map<String, Object> analyzer = (Map<String, Object>) payload.get("analyzer");
        assertEquals("SO2分析仪", analyzer.get("name"), "name = 冻结列 instrument_name");
        assertEquals("SN-SO2-001", analyzer.get("sn"));
        assertNull(payload.get("series"), "已完成记录 series=null（契约冻结）");
        assertNull(payload.get("seriesWindow"));
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) payload.get("attrs");
        assertEquals(1, attrs.size());
        verify(sdk, org.mockito.Mockito.never())
                .queryRawSeries(anyList(), any(Instant.class), any(Instant.class), any());
    }

    /** CO 曲线单位 ppm：SDK 目标单位 full key 与响应短名都切 ppm；markLine 40000ppb→40ppm。 */
    @Test
    @SuppressWarnings("unchecked")
    void coParameterUsesPpmTargetUnit() {
        AirDeviceDataSdk sdk = mock(AirDeviceDataSdk.class);
        when(sdk.listStatParams()).thenReturn(Collections.singletonList(
                meta("logicdevice.co", "co", "CO浓度", AdmParamKind.MONITOR)));
        when(sdk.queryLatest(anyList(), eq("AirVolumeUnit.PPM"))).thenReturn(Collections.singletonList(
                sample("co", Instant.ofEpochMilli(1_700_000_000_000L), 40.1, "AirVolumeUnit.PPM",
                        Collections.singletonList("NORMAL"))));
        when(sdk.queryRawSeries(anyList(), any(Instant.class), any(Instant.class), eq("AirVolumeUnit.PPM")))
                .thenReturn(Collections.singletonList(
                        sample("co", Instant.ofEpochMilli(1_700_000_000_000L), 40.1, "AirVolumeUnit.PPM", null)));

        QcmRecord record = baseRecord("4", "1");
        record.setStartTime(Instant.now().minus(Duration.ofMinutes(10)));
        record.setStandardValue(new BigDecimal("40000"));
        Map<String, Object> payload = QcLiveProcessPayload.build(coreWithSdk(sdk), record);

        assertEquals("ppm", payload.get("curveUnit"));
        List<Map<String, Object>> attrs = (List<Map<String, Object>>) payload.get("attrs");
        assertEquals("ppm", attrs.get(0).get("unit"));
        Map<String, Object> markLine = (Map<String, Object>) payload.get("markLine");
        assertEquals(40.0, (Double) markLine.get("value"), 1e-6, "标气 40000ppb 须换算为 40ppm 再画线");
        assertEquals("ppm", markLine.get("unit"));
    }

    /** 异常久未结束的执行：窗口截断到 SDK 24h 上限（seriesWindow 如实反映截断后的窗）。 */
    @Test
    @SuppressWarnings("unchecked")
    void longRunningRecord_windowClampedToSdkCap() {
        AirDeviceDataSdk sdk = mock(AirDeviceDataSdk.class);
        when(sdk.listStatParams()).thenReturn(Collections.singletonList(
                meta("logicdevice.so2", "so2", "SO2浓度", AdmParamKind.MONITOR)));
        when(sdk.queryLatest(anyList(), any())).thenReturn(Collections.emptyList());
        when(sdk.queryRawSeries(anyList(), any(Instant.class), any(Instant.class), any()))
                .thenReturn(Collections.emptyList());

        Instant before = Instant.now();
        QcmRecord record = baseRecord("1", "0"); // 等待中同样属于「执行中」两态
        record.setStartTime(before.minus(Duration.ofHours(30)));
        Map<String, Object> payload = QcLiveProcessPayload.build(coreWithSdk(sdk), record);

        Map<String, Object> window = (Map<String, Object>) payload.get("seriesWindow");
        assertNotNull(window, "30h 执行不能不画曲线");
        long start = ((Number) window.get("start")).longValue();
        long end = ((Number) window.get("end")).longValue();
        assertTrue(start > record.getStartTime().toEpochMilli() + Duration.ofMinutes(5).toMillis(),
                "起点被截断（不再是任务开始−5min）");
        assertTrue(start >= before.minus(Duration.ofHours(24)).toEpochMilli() - 2_000,
                "截断后窗长 ≤ SDK 24h 上限（容许断言期秒级时钟滑移）");
        assertTrue(end - start <= Duration.ofHours(24).toMillis() + 2_000);
    }

    /** ADM 集成未注册：analyzerReason 显式提示 + attrs 空表 + attrsReason，markLine 仍可算（只依赖 record）。 */
    @Test
    @SuppressWarnings("unchecked")
    void admNotLoaded_returnsExplicitReason() {
        EcatCore core = mock(EcatCore.class);
        IntegrationRegistry integrations = mock(IntegrationRegistry.class);
        when(core.getIntegrationRegistry()).thenReturn(integrations);
        when(integrations.getIntegration(ADM_COORDINATE)).thenReturn(null);

        QcmRecord record = baseRecord("1", "0");
        record.setStartTime(Instant.now());
        Map<String, Object> payload = QcLiveProcessPayload.build(core, record);

        Map<String, Object> analyzer = (Map<String, Object>) payload.get("analyzer");
        assertEquals("logicdevice.so2", analyzer.get("uniqueId"), "analyzer 本体仍解析（indicator 来自参数）");
        String reason = String.valueOf(payload.get("analyzerReason"));
        assertTrue(reason.contains("ADM") && reason.contains(ADM_COORDINATE), "reason=" + reason);
        assertNull(payload.get("series"));
        assertTrue(((List<?>) payload.get("attrs")).isEmpty());
        assertTrue(String.valueOf(payload.get("attrsReason")).contains("不可用"));
        Map<String, Object> markLine = (Map<String, Object>) payload.get("markLine");
        assertNotNull(markLine, "markLine 只依赖 record（零点=0），不受 ADM 缺席影响");
        assertEquals(0.0, (Double) markLine.get("value"), 1e-6);
    }

    /** 目录中无该分析仪参数（STORAGE 行未 provision）：attrsReason 点名 indicator，attrs 空表。 */
    @Test
    @SuppressWarnings("unchecked")
    void emptyCatalog_returnsAttrsReason() {
        AirDeviceDataSdk sdk = mock(AirDeviceDataSdk.class);
        when(sdk.listStatParams()).thenReturn(Collections.singletonList(
                meta("logicdevice.meteo", "wind_speed", "风速", AdmParamKind.MONITOR)));

        QcmRecord record = baseRecord("1", "1");
        record.setStartTime(Instant.now());
        Map<String, Object> payload = QcLiveProcessPayload.build(coreWithSdk(sdk), record);

        assertNull(payload.get("analyzerReason"));
        assertTrue(((List<?>) payload.get("attrs")).isEmpty());
        String reason = String.valueOf(payload.get("attrsReason"));
        assertTrue(reason.contains("logicdevice.so2"), "reason 点名 indicator=" + reason);
        assertNull(payload.get("series"));
    }

    /** 未知参数代码：analyzer null + reason，attrs 空表（不猜默认分析仪）。 */
    @Test
    @SuppressWarnings("unchecked")
    void resolveFails_unknownParameterCode() {
        EcatCore core = mock(EcatCore.class);
        Map<String, Object> payload = QcLiveProcessPayload.build(core, baseRecord("99", "1"));
        assertNull(payload.get("analyzer"));
        assertTrue(String.valueOf(payload.get("analyzerReason")).contains("无法识别"),
                "reason=" + payload.get("analyzerReason"));
        assertTrue(((List<?>) payload.get("attrs")).isEmpty());
        assertNull(payload.get("series"));
    }

    /** PM 参数无气态质控执行通道（composer 不支持）：显式 reason 而非异常。 */
    @Test
    void resolveFails_pmParameterHasNoGasChannel() {
        EcatCore core = mock(EcatCore.class);
        Map<String, Object> payload = QcLiveProcessPayload.build(core, baseRecord("5", "1"));
        assertNull(payload.get("analyzer"));
        assertTrue(String.valueOf(payload.get("analyzerReason")).contains("无气态质控执行通道"),
                "reason=" + payload.get("analyzerReason"));
    }

    /** markLine：零点=0；跨度运行中未冻结 standardValue 时回落 execution_log.params.concentrationPpb。 */
    @Test
    @SuppressWarnings("unchecked")
    void markLineZeroAndRunningConcentrationFallback() {
        AirDeviceDataSdk sdk = mock(AirDeviceDataSdk.class);
        when(sdk.listStatParams()).thenReturn(Collections.singletonList(
                meta("logicdevice.so2", "so2", "SO2浓度", AdmParamKind.MONITOR)));
        when(sdk.queryLatest(anyList(), any())).thenReturn(Collections.emptyList());
        EcatCore core = coreWithSdk(sdk);

        QcmRecord zero = baseRecord("1", "0");
        Map<String, Object> markZero = (Map<String, Object>) QcLiveProcessPayload.build(core, zero).get("markLine");
        assertEquals(0.0, (Double) markZero.get("value"), 1e-6);

        QcmRecord spanRunning = baseRecord("1", "1");
        spanRunning.setExecutionLog("{\"params\":{\"concentrationPpb\":400}}");
        Map<String, Object> markSpan = (Map<String, Object>) QcLiveProcessPayload.build(core, spanRunning).get("markLine");
        assertEquals(400.0, (Double) markSpan.get("value"), 1e-6, "运行中未冻结，回落任务参数标气浓度");

        // 运行早期 execution_log 未落 params（完成时才写）→ 回落计划表浓度：曲线打开即有目标线
        QcmRecord spanEarly = baseRecord("1", "1");
        Map<String, Object> markPlan = (Map<String, Object>) QcLiveProcessPayload
                .build(core, spanEarly, new BigDecimal("400")).get("markLine");
        assertEquals(400.0, (Double) markPlan.get("value"), 1e-6, "无冻结无任务参数时，回落计划表标气浓度");
        // 计划浓度也缺（手动触发无 planId）→ 如实不画线
        Map<String, Object> markNone = (Map<String, Object>) QcLiveProcessPayload.build(core, spanEarly).get("markLine");
        assertNull(markNone, "三级全缺时 markLine=null 走 note 说明");
    }

    /** 多点类质控无单一目标浓度线：markLine null + note 说明（不画不猜）。 */
    @Test
    void markLineAbsentForMultiPointTypes() {
        AirDeviceDataSdk sdk = mock(AirDeviceDataSdk.class);
        when(sdk.listStatParams()).thenReturn(Collections.emptyList());
        Map<String, Object> payload = QcLiveProcessPayload.build(coreWithSdk(sdk), baseRecord("1", "2"));
        assertNull(payload.get("markLine"));
        assertTrue(String.valueOf(payload.get("markLineNote")).contains("多点"),
                "note=" + payload.get("markLineNote"));
    }

    // ---------- 测试装置（mock ADM SDK 接口 + registry，不 mock ADM 内部实现类） ----------

    private static QcmRecord baseRecord(String parameterCode, String qcTypeCode) {
        QcmRecord r = new QcmRecord();
        r.setId(1L);
        r.setParameter(parameterCode);
        r.setQualityControlType(qcTypeCode);
        r.setExecutionStatus(1);
        return r;
    }

    /** EcatCore → IntegrationRegistry → ADM 集成实例 → getAirDeviceDataSdk（javadoc 既定获取模式）。 */
    private static EcatCore coreWithSdk(AirDeviceDataSdk sdk) {
        EcatCore core = mock(EcatCore.class);
        IntegrationRegistry integrations = mock(IntegrationRegistry.class);
        EnvAirDeviceManagerIntegration adm = mock(EnvAirDeviceManagerIntegration.class);
        when(core.getIntegrationRegistry()).thenReturn(integrations);
        when(integrations.getIntegration(ADM_COORDINATE)).thenReturn(adm);
        when(adm.getAirDeviceDataSdk()).thenReturn(sdk);
        return core;
    }

    private static SdkParamMeta meta(String uid, String attrId, String displayName, AdmParamKind kind) {
        return SdkParamMeta.builder()
                .logicDeviceUniqueId(uid)
                .attrId(attrId)
                .paramDisplayName(displayName)
                .storageUnit("AirVolumeUnit.PPB")
                .applicableGranularityMask(kind == AdmParamKind.STATUS ? 3 : 15)
                .paramKind(kind)
                .build();
    }

    /** SDK 行装置：载荷侧按 attrId 归位（目录 join），uid 只需非空占位。 */
    private static SdkSampleRow sample(String attrId, Instant dataTime, Double valueNum,
                                       String unitFullKey, List<String> statuses) {
        return SdkSampleRow.builder()
                .logicDeviceUniqueId("logicdevice.test")
                .attrId(attrId)
                .paramDisplayName(attrId)
                .dataTime(dataTime)
                .valueNum(valueNum)
                .unit(unitFullKey)
                .statuses(statuses)
                .source("POLL")
                .build();
    }
}
