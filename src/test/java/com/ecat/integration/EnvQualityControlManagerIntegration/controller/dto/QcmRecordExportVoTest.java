package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;

/**
 * 质控记录导出 VO 拷贝与 Instant 预格式化（Asia/Shanghai）单测。
 */
public class QcmRecordExportVoTest {

    @Test
    public void from_instantFormattedAsShanghaiWallClock() {
        // 2026-08-14 00:00 UTC = 上海 08:00
        Instant start = Instant.parse("2026-08-14T00:00:00Z");
        QcmRecord r = new QcmRecord();
        r.setTaskType("定时");
        r.setQualityControlType("ZERO_SPAN");
        r.setParameter("SO2");
        r.setStartTime(start);
        r.setEndTime(start.plusSeconds(3600));
        r.setStandardValue(new BigDecimal("0.5"));
        r.setMonitoringData(new BigDecimal("0.51"));
        r.setCalculatedValue(new BigDecimal("0.001"));
        r.setExecutionStatus(2);
        r.setResultEvaluation("合格");

        QcmRecordExportVo vo = QcmRecordExportVo.from(r);
        assertEquals("定时", vo.getTaskType());
        assertEquals("2026-08-14 08:00:00", vo.getStartTime());
        assertEquals("2026-08-14 09:00:00", vo.getEndTime());
        assertEquals(new BigDecimal("0.5"), vo.getStandardValue());
        assertEquals(Integer.valueOf(2), vo.getExecutionStatus());
        assertEquals("合格", vo.getResultEvaluation());
    }

    @Test
    public void from_nullTimesExportedAsEmptyString() {
        QcmRecord r = new QcmRecord();
        QcmRecordExportVo vo = QcmRecordExportVo.from(r);
        assertEquals("", vo.getStartTime());
        assertEquals("", vo.getEndTime());
    }

    @Test
    public void formatter_roundTripWithQueryWindowParse() {
        Instant t = LocalDateTime.parse("2026-01-02 03:04:05",
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        QcmRecord r = new QcmRecord();
        r.setStartTime(t);
        assertEquals("2026-01-02 03:04:05", QcmRecordExportVo.from(r).getStartTime());
    }
}
