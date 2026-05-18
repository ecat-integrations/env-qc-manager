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
 * GenPrecisionReport 单元测试类
 * 测试仪器精密度审核记录表生成
 * 
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
class GenPrecisionReportTest {

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
        cal.set(2025, Calendar.JANUARY, 20, 9, 0, 0);
        startTime = cal.getTime();
        
        cal.set(2025, Calendar.JANUARY, 20, 11, 0, 0);
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
    void testGenPrecisionReport_SO2() {
        // 准备测试数据 - SO2精密度测试（使用 code="1"）
        EnvQualityControlRecords record = createPrecisionCheckRecord("1");  // SO2 code
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟SO2设备
        DeviceBase so2Device = createMockDevice("esa-so2", "SO2分析仪", "SN-SO2-001", "SO2");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-so2")).thenReturn(so2Device);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证结果
        assertNotNull(reports);
        assertEquals(1, reports.size());
        
        EnvQualityControlReport report = reports.get(0);
        assertNotNull(report);
        assertEquals("ReportD4", report.getComponent());
        assertNotNull(report.getReportData());
        
        // 验证报告数据
        Map<String, Object> reportData = report.getReportData();
        assertTrue(reportData.containsKey("title"));
        assertTrue(reportData.containsKey("instrument_info"));
        assertTrue(reportData.containsKey("gas_concentrations_input"));
        assertTrue(reportData.containsKey("instrument_responses"));
    }

    @Test
    void testGenPrecisionReport_NO2() {
        // 准备测试数据（NO2 code="2"）
        EnvQualityControlRecords record = createPrecisionCheckRecord("2");  // NO2 code
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟NO2设备
        DeviceBase no2Device = createMockDevice("esa-no2", "NO2分析仪", "SN-NO2-001", "NO2");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-no2")).thenReturn(no2Device);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证结果
        assertNotNull(reports);
        assertEquals(1, reports.size());
        assertEquals("ReportD4", reports.get(0).getComponent());
    }

    @Test
    void testGenPrecisionReport_VerifyInstrumentResponses() {
        // 准备测试数据（SO2 code="1"）
        EnvQualityControlRecords record = createPrecisionCheckRecord("1");  // SO2 code
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟SO2设备
        DeviceBase so2Device = createMockDevice("esa-so2", "SO2分析仪", "SN-SO2-001", "SO2");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-so2")).thenReturn(so2Device);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证仪器响应值（连续7次测量，报表侧为带浓度单位的字符串列表）
        EnvQualityControlReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        
        @SuppressWarnings("unchecked")
        List<String> instrumentResponses = (List<String>) reportData.get("instrument_responses");
        assertNotNull(instrumentResponses);
        assertEquals(7, instrumentResponses.size());
        assertEquals(79.5f, parseConcCell(instrumentResponses.get(0)), 0.1);
        assertEquals(80.2f, parseConcCell(instrumentResponses.get(1)), 0.1);
        assertEquals(79.8f, parseConcCell(instrumentResponses.get(2)), 0.1);
        assertEquals(80.1f, parseConcCell(instrumentResponses.get(3)), 0.1);
        assertEquals(79.7f, parseConcCell(instrumentResponses.get(4)), 0.1);
        assertEquals(80.0f, parseConcCell(instrumentResponses.get(5)), 0.1);
        assertEquals(79.9f, parseConcCell(instrumentResponses.get(6)), 0.1);
    }

    @Test
    void testGenPrecisionReport_VerifyRelativeStandardDeviation() {
        // 准备测试数据（NO2 code="2"）
        EnvQualityControlRecords record = createPrecisionCheckRecord("2");  // NO2 code
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟NO2设备
        DeviceBase no2Device = createMockDevice("esa-no2", "NO2分析仪", "SN-NO2-001", "NO2");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-no2")).thenReturn(no2Device);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证相对标准偏差（报表为带 % 的展示字符串）
        EnvQualityControlReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        
        Object rsdObj = reportData.get("relative_standard_deviation");
        assertNotNull(rsdObj);
        float relativeStandardDeviation = parsePercentCell(String.valueOf(rsdObj));
        assertTrue(relativeStandardDeviation < 2.0f, "相对标准偏差应小于2%");
    }

    @Test
    void testGenPrecisionReport_MultipleGases() {
        // 准备测试数据 - 多种气体的精密度测试
        List<EnvQualityControlRecords> records = Arrays.asList(
            createPrecisionCheckRecord("1"),  // SO2
            createPrecisionCheckRecord("2"),  // NO2
            createPrecisionCheckRecord("3")   // O3
        );
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟所有设备
        setupMockDevices();

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证结果
        assertNotNull(reports);
        assertEquals(3, reports.size());
        
        // 验证每个报告都有正确的组件
        for (EnvQualityControlReport report : reports) {
            assertEquals("ReportD4", report.getComponent());
            assertNotNull(report.getReportData());
        }
    }

    @Test
    void testGenPrecisionReport_VerifyInstrumentInfo() {
        // 准备测试数据（O3 code="3"）
        EnvQualityControlRecords record = createPrecisionCheckRecord("3");  // O3 code
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟O3设备
        DeviceBase o3Device = createMockDevice("esa-o3", "O3分析仪", "SN-O3-001", "O3");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-o3")).thenReturn(o3Device);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证仪器信息
        EnvQualityControlReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        
        @SuppressWarnings("unchecked")
        Map<String, String> instrumentInfo = (Map<String, String>) reportData.get("instrument_info");
        assertNotNull(instrumentInfo);
        assertEquals("O3分析仪SN-O3-001", instrumentInfo.get("instrument_name_and_no"));
        assertEquals("O3分析仪", instrumentInfo.get("instrument_name"));
        assertEquals("SN-O3-001", instrumentInfo.get("instrument_no"));
        assertNotNull(instrumentInfo.get("report_date"));
    }

    // ==================== 辅助方法 ====================

    private static float parseConcCell(String cell) {
        return Float.parseFloat(cell.replaceAll("(?i)ppb|ppm|\\s", "").trim());
    }

    private static float parsePercentCell(String cell) {
        return Float.parseFloat(cell.replace("%", "").trim());
    }

    /**
     * 创建精密度检查记录
     * @param parameterCode 参数 code（"1"=SO2, "2"=NO2, "3"=O3, "4"=CO）
     */
    private EnvQualityControlRecords createPrecisionCheckRecord(String parameterCode) {
        EnvQualityControlRecords record = new EnvQualityControlRecords();
        record.setId(1L);
        record.setQualityControlType("3");  // PRECISION_CHECK code
        record.setParameter(parameterCode);  // 使用参数 code 而不是 name
        record.setStartTime(startTime);
        record.setEndTime(endTime);
        record.setExecutionStatus(2L); // 成功状态
        
        // 模拟精密度测试的执行日志（连续7次测量）
        String executionLog = "{"
                + "\"precision\":1.25,"  // 相对标准偏差（精密度）
                + "\"mean\":79.89,"  // 平均值
                + "\"standardDeviation\":0.998,"  // 标准偏差
                + "\"devicesStdGas\":80.0,"  // 通入设备的标气浓度
                + "\"checkRsd20Max\":2.0,"  // 20%满量程的相对标准偏差最大值
                + "\"deviceValues\":[79.5,80.2,79.8,80.1,79.7,80.0,79.9]"  // 7次测量值
                + "}";
        
        record.setExecutionLog(executionLog);
        record.setResultEvaluation("精密度审核合格");
        record.setCreatedBy("admin");
        record.setUpdateBy("admin");
        record.setCreateTime(startTime);
        
        return record;
    }

    /**
     * 创建模拟的设备对象
     */
    private DeviceBase createMockDevice(String id, String name, String sn, String gasType) {
        DeviceBase device = mock(DeviceBase.class);
        lenient().when(device.getId()).thenReturn(id);
        lenient().when(device.getName()).thenReturn(name);
        lenient().when(device.getSn()).thenReturn(sn);
        
        // 设置属性
        Map<String, AttributeBase<?>> attrs = new HashMap<>();
        
        // 添加标气浓度属性
        AttributeBase<?> gasConcentration = mock(AttributeBase.class);
        lenient().when(gasConcentration.getDisplayValue()).thenReturn("400.0ppb");
        attrs.put(gasType + "StdGasConcentration", gasConcentration);
        
        lenient().when(device.getAttrs()).thenReturn(attrs);
        
        return device;
    }

    /**
     * 设置模拟设备
     */
    private void setupMockDevices() {
        // SO2设备
        DeviceBase so2Device = createMockDevice("esa-so2", "SO2分析仪", "SN-SO2-001", "SO2");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-so2")).thenReturn(so2Device);
        
        // NO2设备
        DeviceBase no2Device = createMockDevice("esa-no2", "NO2分析仪", "SN-NO2-001", "NO2");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-no2")).thenReturn(no2Device);
        
        // O3设备
        DeviceBase o3Device = createMockDevice("esa-o3", "O3分析仪", "SN-O3-001", "O3");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-o3")).thenReturn(o3Device);
        
        // CO设备
        DeviceBase coDevice = createMockDevice("esa-co", "CO分析仪", "SN-CO-001", "CO");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-co")).thenReturn(coDevice);
    }
}

