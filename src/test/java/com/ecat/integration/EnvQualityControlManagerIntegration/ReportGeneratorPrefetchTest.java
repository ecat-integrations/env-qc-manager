package com.ecat.integration.EnvQualityControlManagerIntegration;

import com.ecat.core.Device.DeviceBase;
import com.ecat.core.Device.DeviceRegistry;
import com.ecat.core.EcatCore;
import com.ecat.core.Integration.IntegrationRegistry;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmRecordService;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.ReportGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC-C8 设备查询预取防回归：一次 generate() 的设备注册表查询次数 ≤ 涉及气体参数个数
 * （预取前为逐记录 N+1：每条记录各查一次）。
 */
@ExtendWith(MockitoExtension.class)
class ReportGeneratorPrefetchTest {

    @Mock
    private EcatCore mockEcatCore;

    @Mock
    private EcatCoreRuoyiIntegration mockMry;

    @Mock
    private IntegrationRegistry mockRegistry;

    @Mock
    private DeviceRegistry mockDeviceRegistry;

    @Mock
    private IQcmRecordService mockRecordsService;

    private Instant startTime;
    private Instant endTime;

    @BeforeEach
    void setUp() {
        Calendar cal = Calendar.getInstance();
        cal.set(2025, Calendar.JANUARY, 10, 8, 0, 0);
        startTime = cal.toInstant();
        cal.set(2025, Calendar.JANUARY, 10, 10, 0, 0);
        endTime = cal.toInstant();

        lenient().when(mockEcatCore.getIntegrationRegistry()).thenReturn(mockRegistry);
        lenient().when(mockRegistry.getIntegration("integration-ecat-core-ruoyi")).thenReturn(mockMry);
        lenient().when(mockMry.getSpringBean(IQcmRecordService.class)).thenReturn(mockRecordsService);
        lenient().when(mockEcatCore.getDeviceRegistry()).thenReturn(mockDeviceRegistry);

        DeviceBase so2Device = mockDevice("esa-so2", "SO2分析仪");
        DeviceBase no2Device = mockDevice("esa-no2", "NO2分析仪");
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-so2")).thenReturn(so2Device);
        lenient().when(mockDeviceRegistry.getDeviceByID("esa-no2")).thenReturn(no2Device);
    }

    private DeviceBase mockDevice(String id, String name) {
        DeviceBase d = mock(DeviceBase.class);
        lenient().when(d.getId()).thenReturn(id);
        lenient().when(d.getName()).thenReturn(name);
        lenient().when(d.getSn()).thenReturn("SN-" + id);
        return d;
    }

    private QcmRecord multiCheckRecord(String parameterCode) {
        QcmRecord r = new QcmRecord();
        r.setId(System.nanoTime());
        r.setQualityControlType("2"); // MULTI_CHECK code
        r.setParameter(parameterCode);
        r.setStartTime(startTime);
        r.setEndTime(endTime);
        r.setExecutionStatus(2);
        r.setExecutionLog("{\"slope\":0.998,\"intercept\":2.5,\"correlation\":0.9995,"
                + "\"stdValues\":[0.0,50.0,100.0],\"deviceValues\":[0.4,50.2,99.8],\"isPass\":true}");
        r.setResultEvaluation("多点校准合格");
        r.setCreatedBy("admin");
        r.setUpdatedBy("admin");
        r.setCreateTime(startTime);
        return r;
    }

    @Test
    void deviceRegistryQueries_boundedByDistinctParamCount() {
        // 5 条记录仅涉及 2 种气体（SO2×3 + NO2×2）：预取后设备查询 = 2 次（预取前 = 5 次）
        List<QcmRecord> records = new ArrayList<>(Arrays.asList(
                multiCheckRecord("1"), multiCheckRecord("1"), multiCheckRecord("1"),
                multiCheckRecord("2"), multiCheckRecord("2")));
        when(mockRecordsService.selectQcmRecordByTypeTime(
                any(Instant.class), any(Instant.class), any(), any(Integer.class)))
                .thenReturn(records);

        List<QcmReport> reports = new ReportGenerator(mockEcatCore).generate(startTime, endTime);

        assertEquals(5, reports.size(), "行为不变：每条多点记录仍各生成一张报告");
        verify(mockDeviceRegistry, times(1)).getDeviceByID("esa-so2");
        verify(mockDeviceRegistry, times(1)).getDeviceByID("esa-no2");
        for (QcmReport r : reports) {
            assertNotNull(r.getReportData());
            assertEquals("ReportD3", r.getComponent());
        }
    }
}
