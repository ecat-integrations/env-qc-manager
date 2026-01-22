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
 * GenAccuracyReport 单元测试类
 * 测试仪器准确度审核记录表生成
 * 
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
class GenAccuracyReportTest {

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
        cal.set(2025, Calendar.JANUARY, 20, 14, 0, 0);
        startTime = cal.getTime();
        
        cal.set(2025, Calendar.JANUARY, 20, 16, 0, 0);
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
    void testGenAccuracyReport_SO2() {
        // 准备测试数据 - SO2准确度测试（使用 code="1"）
        EnvQualityControlRecords record = createAccuracyCheckRecord("1");  // SO2 code
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
        assertEquals("ReportD5", report.getComponent());
        assertNotNull(report.getReportData());
        
        // 验证报告数据
        Map<String, Object> reportData = report.getReportData();
        assertTrue(reportData.containsKey("title"));
        assertTrue(reportData.containsKey("instrument_info"));
        assertTrue(reportData.containsKey("gas_concentrations_input"));
        assertTrue(reportData.containsKey("instrument_responses"));
        assertTrue(reportData.containsKey("calibration_curve"));
        assertTrue(reportData.containsKey("calibration_result"));
    }

    @Test
    void testGenAccuracyReport_O3() {
        // 准备测试数据（O3 code="3"）
        EnvQualityControlRecords record = createAccuracyCheckRecord("3");  // O3 code
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟O3设备
        DeviceBase o3Device = createMockDevice("esa-o3", "O3分析仪", "SN-O3-001", "O3");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-o3")).thenReturn(o3Device);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证结果
        assertNotNull(reports);
        assertEquals(1, reports.size());
        assertEquals("ReportD5", reports.get(0).getComponent());
    }

    @Test
    void testGenAccuracyReport_VerifyGasConcentrationsAndResponses() {
        // 准备测试数据（O3 code="3"）
        EnvQualityControlRecords record = createAccuracyCheckRecord("3");  // O3 code
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟O3设备
        DeviceBase o3Device = createMockDevice("esa-o3", "O3分析仪", "SN-O3-001", "O3");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-o3")).thenReturn(o3Device);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证标气浓度和仪器响应
        EnvQualityControlReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        
        // 验证标气浓度（3个点）
        @SuppressWarnings("unchecked")
        List<Float> gasConcentrations = (List<Float>) reportData.get("gas_concentrations_input");
        assertNotNull(gasConcentrations);
        assertEquals(3, gasConcentrations.size());
        assertEquals(50.0f, gasConcentrations.get(0), 0.1);
        assertEquals(150.0f, gasConcentrations.get(1), 0.1);
        assertEquals(300.0f, gasConcentrations.get(2), 0.1);
        
        // 验证仪器响应
        @SuppressWarnings("unchecked")
        List<Float> instrumentResponses = (List<Float>) reportData.get("instrument_responses");
        assertNotNull(instrumentResponses);
        assertEquals(3, instrumentResponses.size());
        assertEquals(49.5f, instrumentResponses.get(0), 0.1);
        assertEquals(148.2f, instrumentResponses.get(1), 0.1);
        assertEquals(297.8f, instrumentResponses.get(2), 0.1);
    }

    @Test
    void testGenAccuracyReport_VerifyCalibrationCurve() {
        // 准备测试数据（CO code="4"）
        EnvQualityControlRecords record = createAccuracyCheckRecord("4");  // CO code
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟CO设备
        DeviceBase coDevice = createMockDevice("esa-co", "CO分析仪", "SN-CO-001", "CO");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-co")).thenReturn(coDevice);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证校准曲线参数
        EnvQualityControlReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        
        @SuppressWarnings("unchecked")
        Map<String, String> calibrationCurve = (Map<String, String>) reportData.get("calibration_curve");
        assertNotNull(calibrationCurve);
        assertEquals("Y = aX + b", calibrationCurve.get("formula"));
        
        // 验证斜率接近1
        float slope = Float.parseFloat(calibrationCurve.get("a"));
        assertTrue(slope > 0.95f && slope < 1.05f, "斜率应接近1");
        
        // 验证相关系数接近1
        float correlation = Float.parseFloat(calibrationCurve.get("r"));
        assertTrue(correlation > 0.995f, "相关系数应大于0.995");
    }

    @Test
    void testGenAccuracyReport_VerifyAverageRelativeError() {
        // 准备测试数据（NO2 code="2"）
        EnvQualityControlRecords record = createAccuracyCheckRecord("2");  // NO2 code
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟NO2设备
        DeviceBase no2Device = createMockDevice("esa-no2", "NO2分析仪", "SN-NO2-001", "NO2");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-no2")).thenReturn(no2Device);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证平均相对误差
        EnvQualityControlReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        
        String averageRelativeError = (String) reportData.get("average_relative_error");
        assertNotNull(averageRelativeError);
        float error = Float.parseFloat(averageRelativeError);
        assertTrue(error < 5.0f, "平均相对误差应小于5%");
    }

    @Test
    void testGenAccuracyReport_CalibrationResultPass() {
        // 准备测试数据 - 合格（SO2 code="1"）
        EnvQualityControlRecords record = createAccuracyCheckRecord("1", true);  // SO2 code
        record.setExecutionStatus(2L); // 成功状态
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟SO2设备
        DeviceBase so2Device = createMockDevice("esa-so2", "SO2分析仪", "SN-SO2-001", "SO2");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-so2")).thenReturn(so2Device);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证校准结果
        EnvQualityControlReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        String calibrationResult = (String) reportData.get("calibration_result");
        assertEquals("合格", calibrationResult);
    }

    @Test
    void testGenAccuracyReport_CalibrationResultFail() {
        // 准备测试数据 - 不合格（NO2 code="2"）
        EnvQualityControlRecords record = createAccuracyCheckRecord("2", false);  // NO2 code
        record.setExecutionStatus(3L); // 失败状态
        List<EnvQualityControlRecords> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectEnvQualityControlRecordsByTypeTime(
                any(Date.class), any(Date.class), any(), any(Long.class)))
                .thenReturn(records);

        // 模拟NO2设备
        DeviceBase no2Device = createMockDevice("esa-no2", "NO2分析仪", "SN-NO2-001", "NO2");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-no2")).thenReturn(no2Device);

        // 执行测试
        List<EnvQualityControlReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证校准结果
        EnvQualityControlReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        String calibrationResult = (String) reportData.get("calibration_result");
        assertEquals("不合格", calibrationResult);
    }

    @Test
    void testGenAccuracyReport_MultipleGases() {
        // 准备测试数据 - 多种气体的准确度测试
        List<EnvQualityControlRecords> records = Arrays.asList(
            createAccuracyCheckRecord("1"),  // SO2
            createAccuracyCheckRecord("2"),  // NO2
            createAccuracyCheckRecord("4")   // CO
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
            assertEquals("ReportD5", report.getComponent());
            assertNotNull(report.getReportData());
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建准确度检查记录
     * @param parameterCode 参数 code（"1"=SO2, "2"=NO2, "3"=O3, "4"=CO）
     */
    private EnvQualityControlRecords createAccuracyCheckRecord(String parameterCode) {
        return createAccuracyCheckRecord(parameterCode, true);
    }

    private EnvQualityControlRecords createAccuracyCheckRecord(String parameterCode, boolean isPass) {
        EnvQualityControlRecords record = new EnvQualityControlRecords();
        record.setId(1L);
        record.setQualityControlType("4");  // ACCURACY_CHECK code
        record.setParameter(parameterCode);  // 使用参数 code 而不是 name
        record.setStartTime(startTime);
        record.setEndTime(endTime);
        record.setExecutionStatus(2L); // 成功状态
        
        // 模拟准确度测试的执行日志（3个浓度点）
        String executionLog = "{"
                + "\"slope\":0.992,"  // 斜率
                + "\"intercept\":1.5,"  // 截距
                + "\"correlation\":0.9985,"  // 相关系数
                + "\"relativeError\":2.35,"  // 平均相对误差
                + "\"stdValues\":[50.0,150.0,300.0],"  // 标准浓度
                + "\"deviceValues\":[49.5,148.2,297.8],"  // 仪器响应值
                + "\"isPass\":" + isPass
                + "}";
        
        record.setExecutionLog(executionLog);
        record.setResultEvaluation("准确度审核合格");
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

