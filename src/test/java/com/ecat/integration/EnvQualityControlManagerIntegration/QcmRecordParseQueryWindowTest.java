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
 * 查询时间窗桥接（params[end_time 窗]/params[start_time 窗] → 直字段 Instant）解析测试。
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
    void resolveQueryWindows_bridgesQueryWindowToDirectFields() {
        QcmRecord record = new QcmRecord();
        Map<String, Object> params = new HashMap<>();
        params.put("beginEndTime", "2026-08-20 00:00:00");
        params.put("endEndTime", "2026-08-20 23:59:59");
        record.setParams(params);
        // 模拟 controller 入口：setParams 只存原始 map（Spring 绑定会绕过 setter 解析），显式 resolve 才桥接
        record.resolveQueryWindows();
        assertEquals(LocalDateTime.of(2026, 8, 20, 0, 0, 0).atZone(ZONE).toInstant(), record.getBeginEndTime());
        assertEquals(LocalDateTime.of(2026, 8, 20, 23, 59, 59).atZone(ZONE).toInstant(), record.getEndEndTime());
    }

    @Test
    void resolveQueryWindows_bridgesStartTimeWindowToDirectFields() {
        // records 页「开始时间」daterange 通道：beginStartTime/endStartTime → start_time 窗直字段
        QcmRecord record = new QcmRecord();
        Map<String, Object> params = new HashMap<>();
        params.put("beginStartTime", "2026-08-20 00:00:00");
        params.put("endStartTime", "2026-08-20 23:59:59");
        record.setParams(params);
        record.resolveQueryWindows();
        assertEquals(LocalDateTime.of(2026, 8, 20, 0, 0, 0).atZone(ZONE).toInstant(), record.getBeginStartTime());
        assertEquals(LocalDateTime.of(2026, 8, 20, 23, 59, 59).atZone(ZONE).toInstant(), record.getEndStartTime());
        // 两窗通道互不串扰：未提交的 end_time 窗保持 null
        assertNull(record.getBeginEndTime());
        assertNull(record.getEndEndTime());
    }

    @Test
    void resolveQueryWindows_startTimeIllegalFormatThrows() {
        // 严格沿用 beginEndTime 的错误语义：非法格式抛 IllegalArgumentException，不猜默认
        QcmRecord record = new QcmRecord();
        Map<String, Object> params = new HashMap<>();
        params.put("beginStartTime", "2026/08/20");
        record.setParams(params);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, record::resolveQueryWindows);
        assertEquals("params[beginStartTime] 时间格式非法，期望 yyyy-MM-dd HH:mm:ss，实际: 2026/08/20", e.getMessage());
    }

    @Test
    void setParams_aloneDoesNotParse_windowsStayNull() {
        // 回归锁：Spring 对 params[key] 的绑定自动生长 Map 并绕过 setter 解析——
        // setParams 必须只存 map（在 setter 里解析是死代码），桥接只能由 resolveQueryWindows 完成
        QcmRecord record = new QcmRecord();
        Map<String, Object> params = new HashMap<>();
        params.put("beginStartTime", "2026-08-20 00:00:00");
        record.setParams(params);
        assertNull(record.getBeginStartTime());
        assertEquals(params, record.getParams());
    }
}
