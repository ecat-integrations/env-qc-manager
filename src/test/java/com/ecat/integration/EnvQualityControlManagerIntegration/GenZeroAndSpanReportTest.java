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
 * GenZeroAndSpanReport 单元测试类
 * 测试仪器运行状况检查/校准记录表（零跨报告）生成
 * 
 * @version 1.0
 *
 * @author coffee
 */
@ExtendWith(MockitoExtension.class)
class GenZeroAndSpanReportTest {

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
        cal.set(2025, Calendar.JANUARY, 10, 8, 0, 0);
        startTime = cal.toInstant();
        
        cal.set(2025, Calendar.JANUARY, 10, 10, 0, 0);
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
    void testGenZeroAndSpanReport_SO2() {
        // 准备测试数据 - SO2零跨检查（零点 + 跨度，使用 parameter code="1"）
        List<QcmRecord> records = Arrays.asList(
            createZeroCheckRecord("1"),  // SO2 code
            createSpanCheckRecord("1")   // SO2 code
        );
        
        when(mockQualityControlRecordsService.selectQcmRecordByTypeTime(
                any(Instant.class), any(Instant.class), any(), any(Integer.class)))
                .thenReturn(records);

        // 模拟SO2设备和校准系统
        DeviceBase so2Device = createMockDevice("esa-so2", "SO2分析仪", "SN-SO2-001", "SO2");
        // DeviceBase calibDevice = createMockCalibDevice();
        when(mockDeviceRegistry.getDeviceByID("esa-so2")).thenReturn(so2Device);
        // when(mockDeviceRegistry.getDeviceByID("sms-calib")).thenReturn(calibDevice);

        // 执行测试
        List<QcmReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证结果
        assertNotNull(reports);
        assertEquals(1, reports.size());
        
        QcmReport report = reports.get(0);
        assertNotNull(report);
        assertEquals("ReportD2", report.getComponent());
        assertNotNull(report.getReportData());
        
        // 验证报告数据
        @SuppressWarnings("unchecked")
        Map<String, Object> reportData = report.getReportData();
        assertTrue(reportData.containsKey("title"));
        assertTrue(reportData.containsKey("instrument_info"));
        assertTrue(reportData.containsKey("calibration_points"));
        assertTrue(reportData.containsKey("zero_drift_result"));
        assertTrue(reportData.containsKey("span_80_drift_result"));
        assertTrue(reportData.containsKey("key_parameters"));
    }

    @Test
    void testGenZeroAndSpanReport_MultipleGases() {
        // 准备测试数据 - 多种气体的零跨检查（使用 parameter code）
        List<QcmRecord> records = Arrays.asList(
            createZeroCheckRecord("1"),  // SO2 code
            createSpanCheckRecord("1"),  // SO2 code
            createZeroCheckRecord("2"),  // NO2 code
            createSpanCheckRecord("2")   // NO2 code
        );
        
        when(mockQualityControlRecordsService.selectQcmRecordByTypeTime(
                any(Instant.class), any(Instant.class), any(), any(Integer.class)))
                .thenReturn(records);

        // 模拟所有气体设备和校准系统
        setupMockDevices();

        // 执行测试
        List<QcmReport> reports = reportGenerator.generate(startTime, endTime);

        // 验证结果 - 应生成2个报告（SO2、NO2各一个）
        assertNotNull(reports);
        assertEquals(2, reports.size());
        
        // 验证每个报告都有正确的组件
        for (QcmReport report : reports) {
            assertEquals("ReportD2", report.getComponent());
            assertNotNull(report.getReportData());
        }
    }

    @Test
    void calibrationResponse_usesVerificationValue_notStdValue() {
        QcmRecord zero = createZeroCheckRecord("1");
        zero.setExecutionLog("{"
                + "\"result\":{"
                + "\"resultValue\":1.5,"
                + "\"stdValue\":0.0,"
                + "\"deviceValue\":-7.5,"
                + "\"verificationValue\":12.5,"
                + "\"isPass\":true"
                + "},"
                + "\"qcPhaseTimelines\":[{\"phaseCode\":\"calibration\",\"endTimeMillis\":1736470800000}],"
                + "\"stdGasConcentration\":\"400\""
                + "}");
        QcmRecord span = createSpanCheckRecord("1");
        span.setExecutionLog("{"
                + "\"result\":{"
                + "\"resultValue\":1.1,"
                + "\"stdValue\":400.0,"
                + "\"deviceValue\":395.0,"
                + "\"verificationValue\":398.0,"
                + "\"isPass\":true"
                + "},"
                + "\"qcPhaseTimelines\":[{\"phaseCode\":\"calibration\",\"endTimeMillis\":1736474400000}]"
                + "}");

        when(mockQualityControlRecordsService.selectQcmRecordByTypeTime(
                any(Instant.class), any(Instant.class), any(), any(Integer.class)))
                .thenReturn(Arrays.asList(zero, span));
        DeviceBase so2Device = createMockDevice("esa-so2", "SO2分析仪", "SN-SO2-001", "SO2");
        when(mockDeviceRegistry.getDeviceByID("esa-so2")).thenReturn(so2Device);

        List<QcmReport> reports = reportGenerator.generate(startTime, endTime);
        assertEquals(1, reports.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> reportData = reports.get(0).getReportData();
        @SuppressWarnings("unchecked")
        Map<String, Object> instrumentInfo = (Map<String, Object>) reportData.get("instrument_info");
        assertEquals("400 ppb", instrumentInfo.get("gas_concentration"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) reportData.get("calibration_points");
        assertEquals("12.5 ppb", points.get(0).get("calibration_value"));
        assertEquals("398 ppb", points.get(1).get("calibration_value"));
    }

    @Test
    void calibrationResponse_emptyWhenCalibratedButNoVerification_andDropsPrimaryConcKeyParams() {
        QcmRecord zero = createZeroCheckRecord("1");
        zero.setExecutionLog("{"
                + "\"result\":{"
                + "\"resultValue\":1.5,"
                + "\"stdValue\":0.0,"
                + "\"deviceValue\":-7.5,"
                + "\"isPass\":true"
                + "},"
                + "\"qcPhaseTimelines\":[{\"phaseCode\":\"calibration\",\"endTimeMillis\":1736470800000}],"
                + "\"keyParametersSnapshot\":["
                + "{\"tName\":\"CO浓度\",\"tValue\":\"1\"},"
                + "{\"tName\":\"样气流量\",\"tValue\":\"0.5\"}"
                + "]"
                + "}");
        QcmRecord span = createSpanCheckRecord("1");
        span.setExecutionLog("{"
                + "\"result\":{"
                + "\"resultValue\":1.1,"
                + "\"stdValue\":400.0,"
                + "\"deviceValue\":395.0,"
                + "\"isPass\":true"
                + "},"
                + "\"qcPhaseTimelines\":[{\"phaseCode\":\"calibration\",\"endTimeMillis\":1736474400000}]"
                + "}");

        when(mockQualityControlRecordsService.selectQcmRecordByTypeTime(
                any(Instant.class), any(Instant.class), any(), any(Integer.class)))
                .thenReturn(Arrays.asList(zero, span));
        DeviceBase so2Device = createMockDevice("esa-so2", "SO2分析仪", "SN-SO2-001", "SO2");
        when(mockDeviceRegistry.getDeviceByID("esa-so2")).thenReturn(so2Device);

        List<QcmReport> reports = reportGenerator.generate(startTime, endTime);
        assertEquals(1, reports.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> reportData = reports.get(0).getReportData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) reportData.get("calibration_points");
        assertEquals("", points.get(0).get("calibration_value"));
        assertEquals("", points.get(1).get("calibration_value"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keyParams = (List<Map<String, Object>>) reportData.get("key_parameters");
        assertEquals(1, keyParams.size());
        assertEquals("样气流量", keyParams.get(0).get("tName"));
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建零点检查记录
     * @param parameterCode 参数 code（"1"=SO2, "2"=NO2, "3"=O3, "4"=CO）
     */
    private QcmRecord createZeroCheckRecord(String parameterCode) {
        QcmRecord record = new QcmRecord();
        record.setId(1L);
        record.setQualityControlType("0");  // ZERO_CHECK code
        record.setParameter(parameterCode);  // 使用参数 code 而不是 name
        
        Calendar cal = Calendar.getInstance();
        cal.set(2025, Calendar.JANUARY, 10, 8, 0, 0);
        record.setStartTime(cal.toInstant());
        
        cal.set(2025, Calendar.JANUARY, 10, 8, 30, 0);
        record.setEndTime(cal.toInstant());
        
        record.setExecutionStatus(2); // 成功状态
        
        // 零点检查执行日志
        String executionLog = "{"
                + "\"resultValue\":1.5,"  // 漂移结果
                + "\"checkCalibLimit\":2500.0,"
                + "\"stdValue\":0.0,"  // 标准值
                + "\"deviceValue\":-7.5,"  // 设备显示值
                + "\"checkPassLimit\":1000.0"
                + "}";
        
        record.setExecutionLog(executionLog);
        record.setResultEvaluation("零点检查合格");
        record.setCreatedBy("admin");
        record.setUpdatedBy("admin");
        record.setCreateTime(record.getStartTime());
        
        return record;
    }

    /**
     * 创建跨度检查记录
     * @param parameterCode 参数 code（"1"=SO2, "2"=NO2, "3"=O3, "4"=CO）
     */
    private QcmRecord createSpanCheckRecord(String parameterCode) {
        QcmRecord record = new QcmRecord();
        record.setId(2L);
        record.setQualityControlType("1");  // SPAN_CHECK code
        record.setParameter(parameterCode);  // 使用参数 code 而不是 name
        
        Calendar cal = Calendar.getInstance();
        cal.set(2025, Calendar.JANUARY, 10, 9, 0, 0);
        record.setStartTime(cal.toInstant());
        
        cal.set(2025, Calendar.JANUARY, 10, 9, 30, 0);
        record.setEndTime(cal.toInstant());
        
        record.setExecutionStatus(2); // 成功状态
        
        // 跨度检查执行日志
        String executionLog = "{"
                + "\"resultValue\":1.1,"  // 漂移结果
                + "\"checkCalibLimit\":10.0,"
                + "\"stdValue\":40000.0,"  // 标准值（满量程80%）
                + "\"deviceValue\":39556.0,"  // 设备显示值
                + "\"checkPassLimit\":5.0"
                + "}";
        
        record.setExecutionLog(executionLog);
        record.setResultEvaluation("跨度检查合格");
        record.setCreatedBy("admin");
        record.setUpdatedBy("admin");
        record.setCreateTime(record.getStartTime());
        
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
     * 创建模拟的校准系统设备
     */
    private DeviceBase createMockCalibDevice() {
        DeviceBase device = mock(DeviceBase.class);
        lenient().when(device.getId()).thenReturn("sms-calib");
        lenient().when(device.getName()).thenReturn("校准系统");
        lenient().when(device.getSn()).thenReturn("SN-CALIB-001");
        
        // 设置属性
        Map<String, AttributeBase<?>> attrs = new HashMap<>();
        
        // 添加各种标气浓度属性
        AttributeBase<?> so2Concentration = mock(AttributeBase.class);
        lenient().when(so2Concentration.getDisplayValue()).thenReturn("400.0ppb");
        attrs.put("so2_std_gas_concentration", so2Concentration);
        
        AttributeBase<?> noConcentration = mock(AttributeBase.class);
        lenient().when(noConcentration.getDisplayValue()).thenReturn("400.0ppb");
        attrs.put("no_std_gas_concentration", noConcentration);
        
        AttributeBase<?> o3Concentration = mock(AttributeBase.class);
        lenient().when(o3Concentration.getDisplayValue()).thenReturn("400.0ppb");
        attrs.put("o3_gas_concentration", o3Concentration);
        
        AttributeBase<?> coConcentration = mock(AttributeBase.class);
        lenient().when(coConcentration.getDisplayValue()).thenReturn("40.0ppm");
        attrs.put("co_std_gas_concentration", coConcentration);
        
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
        
        // 校准系统设备
        DeviceBase calibDevice = createMockCalibDevice();
        lenient().when(mockDeviceRegistry.getDeviceByID("sms-calib")).thenReturn(calibDevice);
    }
}

