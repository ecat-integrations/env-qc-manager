package com.ecat.integration.EnvQualityControlManagerIntegration;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmReportMapper;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.impl.QcmReportServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * 列表瘦身（AC-C6）：列表路径不做 content JSON 反序列化/修补；详情路径负责解析 content。
 */
@ExtendWith(MockitoExtension.class)
class QcmReportServiceImplTest {

    @Mock
    private QcmReportMapper mapper;

    @InjectMocks
    private QcmReportServiceImpl service;

    @Test
    void findPage_noJsonParsing_displayTypeFromDbType() {
        QcmReport row = new QcmReport();
        row.setReportType("1"); // zero_span
        row.setGasType("1");
        // 列表 SQL 已不含 report_content，行上 content 为 null —— 列表路径不得再触碰它
        when(mapper.findPage(row)).thenReturn(Arrays.asList(row));

        List<QcmReport> out = service.findPage(row);

        assertEquals(1, out.size());
        assertNull(out.get(0).getReportData(), "列表路径不得反序列化 report_content");
        assertNull(out.get(0).getComponent());
        assertEquals("零点和跨度检查", out.get(0).getReportDisplayType(), "报表类型展示列改由 DB report_type 映射");
    }

    @Test
    void findPage_unknownDbType_displayTypeEmpty() {
        QcmReport row = new QcmReport();
        row.setReportType("999");
        when(mapper.findPage(row)).thenReturn(Arrays.asList(row));
        List<QcmReport> out = service.findPage(row);
        assertEquals("", out.get(0).getReportDisplayType());
    }

    @Test
    void getDetailById_parsesContentJson() {
        QcmReport row = new QcmReport();
        row.setId(9L);
        row.setReportType("2");
        row.setReportContent("{\"component\":\"ReportD3\",\"gas_type\":\"1\",\"k\":\"v\"}");
        when(mapper.selectDetailById(9L)).thenReturn(row);

        QcmReport out = service.getDetailById(9L);
        assertEquals("ReportD3", out.getComponent());
        assertEquals("v", out.getReportData().get("k"));
        assertEquals("多点检查", out.getReportDisplayType());
    }

    @Test
    void saveBatch_stampsBothCreateTimeAndUpdateTime() {
        // G-BUG-17 回归锁：insertBatch 全列写入，createTime/updateTime 缺一即 NOT NULL 违例（线上实证 2026-08-21）
        List<com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport> reports = new java.util.ArrayList<>();
        reports.add(new com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport());
        org.mockito.ArgumentCaptor<List<com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport>> captor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        when(mapper.insertBatch(captor.capture())).thenReturn(1);
        service.saveBatch(reports);
        com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport saved = captor.getValue().get(0);
        org.junit.jupiter.api.Assertions.assertNotNull(saved.getCreateTime(), "createTime 必须盖时间戳");
        org.junit.jupiter.api.Assertions.assertNotNull(saved.getUpdateTime(), "updateTime 必须盖时间戳（G-BUG-17）");
    }
}
