package com.ecat.integration.EnvQualityControlManagerIntegration;

import com.ecat.core.State.AttributeBase;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlRecords;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlRecordsService;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.ReportGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * GenConversionReport 单元测试类
 * 测试氮氧化物分析仪转换效率测试记录表生成
 * 
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
class GenConversionReportTest {

    @Mock
    private EcatCore mockEcatCore;

    @Mock
    private EcatCoreRuoyiIntegration mockMry;

    @Mock
    private IntegrationRegistry mockRegistry;

    @Mock
    private DeviceRegistry mockDeviceRegistry;

    @Mock
    private IEnvQualityControlRecordsService mockQualityControlRecordsService;

    private ReportGenerator reportGenerator;

    private Date startTime;
    private Date endTime;

    @BeforeEach
    void setUp() {
        // 设置测试时间范围
        Calendar cal = Calendar.getInstance();
        cal.set(2025, Calendar.JANUARY, 15, 14, 0, 0);
        startTime = cal.getTime();
        
        cal.set(2025, Calendar.JANUARY, 15, 16, 0, 0);
        endTime = cal.getTime();

        // 模拟 EcatCore
        lenient().when(mockEcatCore.getIntegrationRegistry()).thenReturn(mockRegistry);
        lenient().when(mockRegistry.getIntegration("integration-ecat-core-ruoyi")).thenReturn(mockMry);
        lenient().when(mockMry.getSpringBean(IEnvQualityControlRecordsService.class)).thenReturn(mockQualityControlRecordsService);
        lenient().when(mockEcatCore.getDeviceRegistry()).thenReturn(mockDeviceRegistry);

        // 创建 ReportGenerator 实例
        reportGenerator = new ReportGenerator(mockEcatCore);
    }

    @Test
    void testGenConversionReport_NOx() {
        // 准备测试数据 - NOx转换效率测试
        EnvQualityControlRecords record = createConversionCheckRecord();
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟NO2设备
        DeviceBase no2Device = createMockDevice("esa-no2", "NO2分析仪", "SN-NO2-001");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-no2")).thenReturn(no2Device);
        
        // 模拟校准系统设备（提供 NO 和 NO2 标气浓度）
        DeviceBase calibDevice = createMockCalibDevice();
        lenient().when(mockDeviceRegistry.getDeviceByID("sms-calib")).thenReturn(calibDevice);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证结果
        assertNotNull(reports);
        assertEquals(1, reports.size());
        
        EnvQualityControlReport report = reports.get(0);
        assertNotNull(report);
        assertEquals("ReportD6", report.getComponent());
        assertNotNull(report.getReportData());
        
        // 验证报告数据
        Map<String, Object> reportData = report.getReportData();
        assertTrue(reportData.containsKey("title"));
        assertTrue(reportData.containsKey("instrument_info"));
        assertTrue(reportData.containsKey("orig_no2_datas"));
        assertTrue(reportData.containsKey("orig_no2_avg"));
        assertTrue(reportData.containsKey("no2_efficiency"));
        assertTrue(reportData.containsKey("rem_no_datas"));
        assertTrue(reportData.containsKey("rem_nox_datas"));
        assertTrue(reportData.containsKey("orig_no_datas"));
        assertTrue(reportData.containsKey("orig_nox_datas"));
        assertTrue(reportData.containsKey("efficiency"));
    }

    @Test
    void testGenConversionReport_VerifyNO2Data() {
        // 准备测试数据
        EnvQualityControlRecords record = createConversionCheckRecord();
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟NO2设备
        DeviceBase no2Device = createMockDevice("esa-no2", "NO2分析仪", "SN-NO2-001");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-no2")).thenReturn(no2Device);
        
        // 模拟校准系统设备（提供 NO 和 NO2 标气浓度）
        DeviceBase calibDevice = createMockCalibDevice();
        lenient().when(mockDeviceRegistry.getDeviceByID("sms-calib")).thenReturn(calibDevice);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证NO2测试数据
        EnvQualityControlReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        
        // 验证NO2读数（第一次、第二次、第三次）— 报表为 ppb 字符串列表
        @SuppressWarnings("unchecked")
        List<String> origNo2Datas = (List<String>) reportData.get("orig_no2_datas");
        assertNotNull(origNo2Datas);
        assertEquals(3, origNo2Datas.size());
        assertEquals(398.5f, parseConcCell(origNo2Datas.get(0)), 0.1);
        assertEquals(399.2f, parseConcCell(origNo2Datas.get(1)), 0.1);
        assertEquals(398.8f, parseConcCell(origNo2Datas.get(2)), 0.1);
        
        // 验证NO2平均值
        String origNo2AvgStr = (String) reportData.get("orig_no2_avg");
        assertNotNull(origNo2AvgStr);
        assertEquals(398.83f, parseConcCell(origNo2AvgStr), 0.1);
        
        // 验证NO2转换效率（带 %）
        String no2EfficiencyStr = (String) reportData.get("no2_efficiency");
        assertNotNull(no2EfficiencyStr);
        assertEquals(99.7f, parsePercentCell(no2EfficiencyStr), 0.1);
    }

    @Test
    void testGenConversionReport_VerifyNOData_O3Off() {
        // 准备测试数据
        EnvQualityControlRecords record = createConversionCheckRecord();
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟NO2设备
        DeviceBase no2Device = createMockDevice("esa-no2", "NO2分析仪", "SN-NO2-001");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-no2")).thenReturn(no2Device);
        
        // 模拟校准系统设备（提供 NO 和 NO2 标气浓度）
        DeviceBase calibDevice = createMockCalibDevice();
        lenient().when(mockDeviceRegistry.getDeviceByID("sms-calib")).thenReturn(calibDevice);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证NO测试数据（O3关）
        EnvQualityControlReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        
        // 验证[NO]orig
        @SuppressWarnings("unchecked")
        List<String> origNoDatas = (List<String>) reportData.get("orig_no_datas");
        assertNotNull(origNoDatas);
        assertEquals(3, origNoDatas.size());
        assertEquals(395.2f, parseConcCell(origNoDatas.get(0)), 0.1);
        assertEquals(396.1f, parseConcCell(origNoDatas.get(1)), 0.1);
        assertEquals(395.7f, parseConcCell(origNoDatas.get(2)), 0.1);
        
        String origNoAvgStr = (String) reportData.get("orig_no_avg");
        assertNotNull(origNoAvgStr);
        assertEquals(396.0f, parseConcCell(origNoAvgStr), 0.1);
        
        // 验证[NOx]orig
        @SuppressWarnings("unchecked")
        List<String> origNoxDatas = (List<String>) reportData.get("orig_nox_datas");
        assertNotNull(origNoxDatas);
        assertEquals(3, origNoxDatas.size());
        assertEquals(397.5f, parseConcCell(origNoxDatas.get(0)), 0.1);
        assertEquals(398.2f, parseConcCell(origNoxDatas.get(1)), 0.1);
        assertEquals(397.8f, parseConcCell(origNoxDatas.get(2)), 0.1);
        
        String origNoxAvgStr = (String) reportData.get("orig_nox_avg");
        assertNotNull(origNoxAvgStr);
        assertEquals(397.83f, parseConcCell(origNoxAvgStr), 0.1);
    }

    @Test
    void testGenConversionReport_VerifyNOData_O3On() {
        // 准备测试数据
        EnvQualityControlRecords record = createConversionCheckRecord();
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟NO2设备
        DeviceBase no2Device = createMockDevice("esa-no2", "NO2分析仪", "SN-NO2-001");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-no2")).thenReturn(no2Device);
        
        // 模拟校准系统设备（提供 NO 和 NO2 标气浓度）
        DeviceBase calibDevice = createMockCalibDevice();
        lenient().when(mockDeviceRegistry.getDeviceByID("sms-calib")).thenReturn(calibDevice);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证NO测试数据（O3开）
        EnvQualityControlReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        
        // 验证[NO]rem
        @SuppressWarnings("unchecked")
        List<String> remNoDatas = (List<String>) reportData.get("rem_no_datas");
        assertNotNull(remNoDatas);
        assertEquals(3, remNoDatas.size());
        assertEquals(2.5f, parseConcCell(remNoDatas.get(0)), 0.1);
        assertEquals(2.8f, parseConcCell(remNoDatas.get(1)), 0.1);
        assertEquals(2.6f, parseConcCell(remNoDatas.get(2)), 0.1);
        
        String remNoAvgStr = (String) reportData.get("rem_no_avg");
        assertNotNull(remNoAvgStr);
        assertEquals(2.63f, parseConcCell(remNoAvgStr), 0.1);
        
        // 验证[NOx]rem
        @SuppressWarnings("unchecked")
        List<String> remNoxDatas = (List<String>) reportData.get("rem_nox_datas");
        assertNotNull(remNoxDatas);
        assertEquals(3, remNoxDatas.size());
        assertEquals(398.1f, parseConcCell(remNoxDatas.get(0)), 0.1);
        assertEquals(399.0f, parseConcCell(remNoxDatas.get(1)), 0.1);
        assertEquals(398.5f, parseConcCell(remNoxDatas.get(2)), 0.1);
        
        String remNoxAvgStr = (String) reportData.get("rem_nox_avg");
        assertNotNull(remNoxAvgStr);
        assertEquals(398.53f, parseConcCell(remNoxAvgStr), 0.1);
    }

    @Test
    void testGenConversionReport_VerifyEfficiency() {
        // 准备测试数据
        EnvQualityControlRecords record = createConversionCheckRecord();
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟NO2设备
        DeviceBase no2Device = createMockDevice("esa-no2", "NO2分析仪", "SN-NO2-001");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-no2")).thenReturn(no2Device);
        
        // 模拟校准系统设备（提供 NO 和 NO2 标气浓度）
        DeviceBase calibDevice = createMockCalibDevice();
        lenient().when(mockDeviceRegistry.getDeviceByID("sms-calib")).thenReturn(calibDevice);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证转换效率
        EnvQualityControlReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        
        // NO转换效率（展示带空格百分号）
        String efficiency = (String) reportData.get("efficiency");
        assertNotNull(efficiency);
        assertEquals("99.35 %", efficiency);
    }

    @Test
    void testGenConversionReport_VerifyInstrumentInfo() {
        // 准备测试数据
        EnvQualityControlRecords record = createConversionCheckRecord();
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟NO2设备
        DeviceBase no2Device = createMockDevice("esa-no2", "NO2分析仪", "SN-NO2-001");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-no2")).thenReturn(no2Device);
        
        // 模拟校准系统设备（提供 NO 和 NO2 标气浓度）
        DeviceBase calibDevice = createMockCalibDevice();
        lenient().when(mockDeviceRegistry.getDeviceByID("sms-calib")).thenReturn(calibDevice);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证仪器信息
        EnvQualityControlReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        
        @SuppressWarnings("unchecked")
        Map<String, String> instrumentInfo = (Map<String, String>) reportData.get("instrument_info");
        assertNotNull(instrumentInfo);
        assertEquals("NO2分析仪SN-NO2-001", instrumentInfo.get("instrument_name_and_no"));
        assertEquals("NO2分析仪", instrumentInfo.get("instrument_name"));
        assertEquals("SN-NO2-001", instrumentInfo.get("instrument_no"));
        assertNotNull(instrumentInfo.get("report_date"));
    }

    @Test
    void testGenConversionReport_VerifyFillerAndReviewer() {
        // 准备测试数据
        EnvQualityControlRecords record = createConversionCheckRecord();
        record.setCreatedBy("张三");
        record.setUpdateBy("李四");
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟NO2设备
        DeviceBase no2Device = createMockDevice("esa-no2", "NO2分析仪", "SN-NO2-001");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-no2")).thenReturn(no2Device);
        
        // 模拟校准系统设备（提供 NO 和 NO2 标气浓度）
        DeviceBase calibDevice = createMockCalibDevice();
        lenient().when(mockDeviceRegistry.getDeviceByID("sms-calib")).thenReturn(calibDevice);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证填表人和复核人
        EnvQualityControlReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        
        assertEquals("张三", reportData.get("filer"));
        assertEquals("李四", reportData.get("reviewer"));
    }

    // ==================== 辅助方法 ====================

    private static float parseConcCell(String cell) {
        return Float.parseFloat(cell.replaceAll("(?i)ppb|ppm|\\s", "").trim());
    }

    private static float parsePercentCell(String cell) {
        return Float.parseFloat(cell.replace("%", "").trim());
    }

    /**
     * 创建转换效率检查记录
     */
    private EnvQualityControlRecords createConversionCheckRecord() {
        EnvQualityControlRecords record = new EnvQualityControlRecords();
        record.setId(1L);
        record.setQualityControlType("5");  // CONVERSION_CHECK code
        record.setParameter("2");  // NO2 code (使用 code 而不是 name)
        record.setStartTime(startTime);
        record.setEndTime(endTime);
        record.setExecutionStatus(2L); // 成功状态
        
        // 模拟转换效率测试的执行日志
        String executionLog = "{"
                // NO2测试数据
                + "\"remNo2Datas\":[398.5,399.2,398.8],"
                + "\"origNo2Avg\":398.83,"
                + "\"efficiency2\":99.7,"
                // NO测试数据 - O3关
                + "\"origNoDatas\":[395.2,396.1,395.7],"
                + "\"origNoxDatas\":[397.5,398.2,397.8],"
                + "\"origNoAvg\":396.0,"
                + "\"origNoxAvg\":397.83,"
                // NO测试数据 - O3开
                + "\"remNoDatas\":[2.5,2.8,2.6],"
                + "\"remNoxDatas\":[398.1,399.0,398.5],"
                + "\"remNoAvg\":2.63,"
                + "\"remNoxAvg\":398.53,"
                // NO转换效率
                + "\"efficiency\":99.35"
                + "}";
        
        record.setExecutionLog(executionLog);
        record.setResultEvaluation("转换效率测试合格");
        record.setCreatedBy("admin");
        record.setUpdateBy("admin");
        record.setCreateTime(startTime);
        
        return record;
    }

    /**
     * 创建模拟的设备对象
     */
    private DeviceBase createMockDevice(String id, String name, String sn) {
        DeviceBase device = mock(DeviceBase.class);
        lenient().when(device.getId()).thenReturn(id);
        lenient().when(device.getName()).thenReturn(name);
        lenient().when(device.getSn()).thenReturn(sn);
        
        // 设置属性
        Map<String, AttributeBase<?>> attrs = new HashMap<>();
        
        // 添加NO和NO2标气浓度属性
        AttributeBase<?> noConcentration = mock(AttributeBase.class);
        lenient().when(noConcentration.getDisplayValue()).thenReturn("400.0ppb");
        attrs.put("NOStdGasConcentration", noConcentration);
        
        AttributeBase<?> no2Concentration = mock(AttributeBase.class);
        lenient().when(no2Concentration.getDisplayValue()).thenReturn("400.0ppb");
        attrs.put("NO2StdGasConcentration", no2Concentration);
        
        lenient().when(device.getAttrs()).thenReturn(attrs);
        
        return device;
    }

    /**
     * 创建模拟的校准系统设备（sms-calib）
     */
    private DeviceBase createMockCalibDevice() {
        DeviceBase device = mock(DeviceBase.class);
        lenient().when(device.getId()).thenReturn("sms-calib");
        lenient().when(device.getName()).thenReturn("校准系统");
        lenient().when(device.getSn()).thenReturn("SN-CALIB-001");
        
        // 设置属性
        Map<String, AttributeBase<?>> attrs = new HashMap<>();
        
        // 添加NO和NO2标气浓度属性
        AttributeBase<?> noConcentration = mock(AttributeBase.class);
        lenient().when(noConcentration.getDisplayValue()).thenReturn("400.0ppb");
        attrs.put("NOStdGasConcentration", noConcentration);
        
        AttributeBase<?> no2Concentration = mock(AttributeBase.class);
        lenient().when(no2Concentration.getDisplayValue()).thenReturn("400.0ppb");
        attrs.put("NO2StdGasConcentration", no2Concentration);
        
        lenient().when(device.getAttrs()).thenReturn(attrs);
        
        return device;
    }
}

