package com.ecat.integration.EnvQualityControlManagerIntegration;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 旧 REST 时间窗桥接（params[beginEndTime]/params[endEndTime] → 直字段 Instant）解析测试。
 */
class QcmRecordParseQueryWindowTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void parseQueryWindow_legalFormatParsesToInstant() {
        Instant expected = LocalDateTime.of(2026, 8, 20, 0, 0, 0).atZone(ZONE).toInstant();
        assertEquals(expected, QcmRecord.parseQueryWindow("beginEndTime", "2026-08-20 00:00:00"));
        // 携带时分秒
        Instant expected2 = LocalDateTime.of(2026, 8, 20, 23, 59, 59).atZone(ZONE).toInstant();
        assertEquals(expected2, QcmRecord.parseQueryWindow("endEndTime", "2026-08-20 23:59:59"));
        // null / 空串 = 未提交该键
        assertNull(QcmRecord.parseQueryWindow("beginEndTime", null));
        assertNull(QcmRecord.parseQueryWindow("beginEndTime", "  "));
    }

    @Test
    void parseQueryWindow_illegalFormatThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> QcmRecord.parseQueryWindow("beginEndTime", "2026/08/20"));
        assertEquals("params[beginEndTime] 时间格式非法，期望 yyyy-MM-dd HH:mm:ss，实际: 2026/08/20", e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> QcmRecord.parseQueryWindow("endEndTime", "2026-08-20"));
    }

    @Test
    void setParams_bridgesQueryWindowToDirectFields() {
        QcmRecord record = new QcmRecord();
        Map<String, Object> params = new HashMap<>();
        params.put("beginEndTime", "2026-08-20 00:00:00");
        params.put("endEndTime", "2026-08-20 23:59:59");
        record.setParams(params);
        assertEquals(LocalDateTime.of(2026, 8, 20, 0, 0, 0).atZone(ZONE).toInstant(), record.getBeginEndTime());
        assertEquals(LocalDateTime.of(2026, 8, 20, 23, 59, 59).atZone(ZONE).toInstant(), record.getEndEndTime());
    }
}
