package com.ecat.integration.EnvQualityControlManagerIntegration;

import com.ecat.core.State.AttributeBase;
import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmRecordService;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.ReportGenerator;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * GenMultiCheckReport 单元测试类
 * 测试仪器多点校准记录表生成
 * 
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
class GenMultiCheckReportTest {

    @Mock
    private EcatCore mockEcatCore;

    @Mock
    private EcatCoreRuoyiIntegration mockMry;

    @Mock
    private IntegrationRegistry mockRegistry;

    @Mock
    private DeviceRegistry mockDeviceRegistry;

    @Mock
    private IQcmRecordService mockQualityControlRecordsService;

    private ReportGenerator reportGenerator;

    private Instant startTime;
    private Instant endTime;

    @BeforeEach
    void setUp() {
        // 设置测试时间范围
        Calendar cal = Calendar.getInstance();
        cal.set(2025, Calendar.JANUARY, 15, 10, 0, 0);
        startTime = cal.toInstant();
        
        cal.set(2025, Calendar.JANUARY, 15, 12, 0, 0);
        endTime = cal.toInstant();

        // 模拟 EcatCore
        lenient().when(mockEcatCore.getIntegrationRegistry()).thenReturn(mockRegistry);
        lenient().when(mockRegistry.getIntegration("integration-ecat-core-ruoyi")).thenReturn(mockMry);
        lenient().when(mockMry.getSpringBean(IQcmRecordService.class)).thenReturn(mockQualityControlRecordsService);
        lenient().when(mockEcatCore.getDeviceRegistry()).thenReturn(mockDeviceRegistry);

        // 创建 ReportGenerator 实例
        reportGenerator = new ReportGenerator(mockEcatCore);
    }

    @Test
    void testGenMultiCheckReport_SO2() {
        // 准备测试数据 - SO2多点校准（参数使用 code="1"）
        QcmRecord record = createMultiCheckRecord("1");  // SO2 code
        List<QcmRecord> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectQcmRecordByTypeTime(
                any(Instant.class), any(Instant.class), any(), any(Integer.class)))
                .thenReturn(records);

        // 模拟SO2设备
        DeviceBase so2Device = createMockDevice("esa-so2", "SO2分析仪", "SN-SO2-001", "SO2");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-so2")).thenReturn(so2Device);

        // 执行测试
        List<QcmReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证结果
        assertNotNull(reports);
        assertEquals(1, reports.size());
        
        QcmReport report = reports.get(0);
        assertNotNull(report);
        assertEquals("ReportD3", report.getComponent());
        assertNotNull(report.getReportData());
        
        // 验证报告数据
        Map<String, Object> reportData = report.getReportData();
        assertTrue(reportData.containsKey("title"));
        assertTrue(reportData.containsKey("instrument_info"));
        assertTrue(reportData.containsKey("gas_concentrations_input"));
        assertTrue(reportData.containsKey("instrument_responses"));
        assertTrue(reportData.containsKey("calibration_curve"));
        
        // 验证标气浓度输入（6个点，报表为带单位的字符串）
        @SuppressWarnings("unchecked")
        List<String> gasConcentrations = (List<String>) reportData.get("gas_concentrations_input");
        assertNotNull(gasConcentrations);
        assertEquals(6, gasConcentrations.size());
        assertEquals(0.0f, parseConcCell(gasConcentrations.get(0)), 0.01);
        assertEquals(50.0f, parseConcCell(gasConcentrations.get(1)), 0.01);
        assertEquals(100.0f, parseConcCell(gasConcentrations.get(2)), 0.01);
        assertEquals(200.0f, parseConcCell(gasConcentrations.get(3)), 0.01);
        assertEquals(300.0f, parseConcCell(gasConcentrations.get(4)), 0.01);
        assertEquals(400.0f, parseConcCell(gasConcentrations.get(5)), 0.01);
        
        // 验证仪器响应值
        @SuppressWarnings("unchecked")
        List<String> instrumentResponses = (List<String>) reportData.get("instrument_responses");
        assertNotNull(instrumentResponses);
        assertEquals(6, instrumentResponses.size());
        
        // 验证校准曲线参数
        @SuppressWarnings("unchecked")
        Map<String, String> calibrationCurve = (Map<String, String>) reportData.get("calibration_curve");
        assertNotNull(calibrationCurve);
        assertTrue(calibrationCurve.containsKey("formula"));
        assertTrue(calibrationCurve.containsKey("a")); // 斜率
        assertTrue(calibrationCurve.containsKey("b")); // 截距
        assertTrue(calibrationCurve.containsKey("r")); // 相关系数
        assertEquals("Y = aX + b", calibrationCurve.get("formula"));
    }

    @Test
    void testGenMultiCheckReport_CO_scalesPpbToPpm() {
        QcmRecord record = createMultiCheckRecordCoPpb();
        List<QcmRecord> records = Arrays.asList(record);

        when(mockQualityControlRecordsService.selectQcmRecordByTypeTime(
                any(Instant.class), any(Instant.class), any(), any(Integer.class)))
                .thenReturn(records);

        DeviceBase coDevice = createMockDevice("esa-co", "CO分析仪", "SN-CO-001", "CO");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-co")).thenReturn(coDevice);

        List<QcmReport> reports = reportGenerator.generate(startTime, endTime);
        assertEquals(1, reports.size());
        Map<String, Object> reportData = reports.get(0).getReportData();

        @SuppressWarnings("unchecked")
        List<String> gasConcentrations = (List<String>) reportData.get("gas_concentrations_input");
        assertEquals("0 ppm", gasConcentrations.get(0));
        assertEquals("10 ppm", gasConcentrations.get(1));
        assertEquals("20 ppm", gasConcentrations.get(2));
        assertEquals("30 ppm", gasConcentrations.get(3));
        assertEquals("40 ppm", gasConcentrations.get(4));

        @SuppressWarnings("unchecked")
        List<String> instrumentResponses = (List<String>) reportData.get("instrument_responses");
        assertEquals("0.4 ppm", instrumentResponses.get(0));
        assertEquals("40 ppm", instrumentResponses.get(4));

        @SuppressWarnings("unchecked")
        Map<String, String> calibrationCurve = (Map<String, String>) reportData.get("calibration_curve");
        assertEquals("2.5", calibrationCurve.get("b"));
        assertEquals("0.998", calibrationCurve.get("a"));
    }

    @Test
    void testGenMultiCheckReport_MultipleGases() {
        // 准备测试数据 - 多种气体的多点校准（使用 parameter code）
        List<QcmRecord> records = Arrays.asList(
            createMultiCheckRecord("1"),  // SO2 code
            createMultiCheckRecord("2"),  // NO2 code
            createMultiCheckRecord("3"),  // O3 code
            createMultiCheckRecord("4")   // CO code
        );
        
        when(mockQualityControlRecordsService.selectQcmRecordByTypeTime(
                any(Instant.class), any(Instant.class), any(), any(Integer.class)))
                .thenReturn(records);

        // 模拟所有气体设备
        setupMockDevices();

        // 执行测试
        List<QcmReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证结果
        assertNotNull(reports);
        assertEquals(4, reports.size());
        
        // 验证每个报告都有正确的组件
        for (QcmReport report : reports) {
            assertEquals("ReportD3", report.getComponent());
            assertNotNull(report.getReportData());
        }
    }

    @Test
    void testGenMultiCheckReport_CalibrationResult_Pass() {
        // 准备测试数据 - 校准合格（SO2 code="1"）
        QcmRecord record = createMultiCheckRecord("1", true);  // SO2 code
        record.setExecutionStatus(2); // 成功状态
        List<QcmRecord> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectQcmRecordByTypeTime(
                any(Instant.class), any(Instant.class), any(), any(Integer.class)))
                .thenReturn(records);

        // 模拟SO2设备
        DeviceBase so2Device = createMockDevice("esa-so2", "SO2分析仪", "SN-SO2-001", "SO2");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-so2")).thenReturn(so2Device);

        // 执行测试
        List<QcmReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证校准结果
        QcmReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        String calibrationResult = (String) reportData.get("calibration_result");
        assertEquals("合格", calibrationResult);
    }

    @Test
    void testGenMultiCheckReport_CalibrationResult_Fail() {
        // 准备测试数据 - 校准不合格（SO2 code="1"）
        QcmRecord record = createMultiCheckRecord("1", false);  // SO2 code
        record.setExecutionStatus(3); // 失败状态
        List<QcmRecord> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectQcmRecordByTypeTime(
                any(Instant.class), any(Instant.class), any(), any(Integer.class)))
                .thenReturn(records);

        // 模拟SO2设备
        DeviceBase so2Device = createMockDevice("esa-so2", "SO2分析仪", "SN-SO2-001", "SO2");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-so2")).thenReturn(so2Device);

        // 执行测试
        List<QcmReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证校准结果
        QcmReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        String calibrationResult = (String) reportData.get("calibration_result");
        assertEquals("不合格", calibrationResult);
    }

    @Test
    void testGenMultiCheckReport_VerifyInstrumentInfo() {
        // 准备测试数据（NO2 code="2"）
        QcmRecord record = createMultiCheckRecord("2");  // NO2 code
        List<QcmRecord> records = Arrays.asList(record);
        
        when(mockQualityControlRecordsService.selectQcmRecordByTypeTime(
                any(Instant.class), any(Instant.class), any(), any(Integer.class)))
                .thenReturn(records);

        // 模拟NO2设备
        DeviceBase no2Device = createMockDevice("esa-no2", "NO2分析仪", "SN-NO2-001", "NO2");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-no2")).thenReturn(no2Device);

        // 执行测试
        List<QcmReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证仪器信息
        QcmReport report = reports.get(0);
        Map<String, Object> reportData = report.getReportData();
        
        @SuppressWarnings("unchecked")
        Map<String, String> instrumentInfo = (Map<String, String>) reportData.get("instrument_info");
        assertNotNull(instrumentInfo);
        assertEquals("NO2分析仪SN-NO2-001", instrumentInfo.get("instrument_name_and_no"));
        assertEquals("NO2分析仪", instrumentInfo.get("instrument_name"));
        assertEquals("SN-NO2-001", instrumentInfo.get("instrument_no"));
        assertNotNull(instrumentInfo.get("report_date"));
    }

    // ==================== 辅助方法 ====================

    private static float parseConcCell(String cell) {
        return Float.parseFloat(cell.replaceAll("(?i)ppb|ppm|\\s", "").trim());
    }

    /**
     * 创建多点校准记录
     * @param parameterCode 参数 code（"1"=SO2, "2"=NO2, "3"=O3, "4"=CO）
     */
    private QcmRecord createMultiCheckRecord(String parameterCode) {
        return createMultiCheckRecord(parameterCode, true);
    }

    private QcmRecord createMultiCheckRecord(String parameterCode, boolean isPass) {
        QcmRecord record = new QcmRecord();
        record.setId(1L);
        record.setQualityControlType("2");  // MULTI_CHECK code
        record.setParameter(parameterCode);  // 使用参数 code 而不是 name
        record.setStartTime(startTime);
        record.setEndTime(endTime);
        record.setExecutionStatus(2); // 成功状态
        
        // 模拟多点校准的执行日志（6个点）
        String executionLog = "{"
                + "\"correlation\":0.9995,"
                + "\"check_b_scope\":5.0,"
                + "\"intercept\":2.5,"
                + "\"deviceValues\":[0.4,50.2,99.8,200.5,299.7,399.5],"
                + "\"check_a_max\":1.05,"
                + "\"stdValues\":[0.0,50.0,100.0,200.0,300.0,400.0],"
                + "\"check_r_min\":0.999,"
                + "\"check_a_min\":0.95,"
                + "\"slope\":0.998,"
                + "\"isPass\":" + isPass
                + "}";
        
        record.setExecutionLog(executionLog);
        record.setResultEvaluation("多点校准合格");
        record.setCreatedBy("admin");
        record.setUpdatedBy("admin");
        record.setCreateTime(startTime);
        
        return record;
    }

    /**
     * CO 多点：execution_log 为编排器 PPB（80% 量程 40000 ppb = 40 ppm）。
     */
    private QcmRecord createMultiCheckRecordCoPpb() {
        QcmRecord record = createMultiCheckRecord("4", true);
        String executionLog = "{"
                + "\"correlation\":0.9995,"
                + "\"check_b_scope\":500.0,"
                + "\"intercept\":2500.0,"
                + "\"deviceValues\":[400.0,10000.0,20000.0,30000.0,40000.0],"
                + "\"check_a_max\":1.05,"
                + "\"stdValues\":[0.0,10000.0,20000.0,30000.0,40000.0],"
                + "\"check_r_min\":0.999,"
                + "\"check_a_min\":0.95,"
                + "\"slope\":0.998,"
                + "\"isPass\":true"
                + "}";
        record.setExecutionLog(executionLog);
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

