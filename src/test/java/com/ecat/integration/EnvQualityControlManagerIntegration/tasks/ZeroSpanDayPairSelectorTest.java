package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ZeroSpanDayPairSelector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.time.Instant;
import java.util.List;

import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.SPAN_CHECK;
import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.ZERO_CHECK;
import static org.junit.jupiter.api.Assertions.*;
/**
 * @author coffee
 */

class ZeroSpanDayPairSelectorTest {

    @Test
    void bothPass_anyOrder_picksLatestEndSum() {
        QcmRecord z = zero(day(10, 8), true);
        QcmRecord s = span(day(10, 9), true);
        List<QcmRecord> chosen = ZeroSpanDayPairSelector.select(list(z, s));
        assertEquals(2, chosen.size());
        assertSame(z, chosen.get(0));
        assertSame(s, chosen.get(1));
    }

    @Test
    void lastPassingZero_noSpanAfter_usesPriorSpan() {
        QcmRecord sEarly = span(day(10, 6), false);
        QcmRecord zPass = zero(day(10, 10), true);
        List<QcmRecord> chosen = ZeroSpanDayPairSelector.select(list(sEarly, zPass));
        assertEquals(2, chosen.size());
        assertSame(zPass, chosen.get(0));
        assertSame(sEarly, chosen.get(1));
    }

    @Test
    void fallback_maxPassCount() {
        QcmRecord zFail = zero(day(10, 8), false);
        QcmRecord sPass = span(day(10, 9), true);
        List<QcmRecord> chosen = ZeroSpanDayPairSelector.select(list(zFail, sPass));
        assertEquals(2, chosen.size());
    }

    @Test
    void spanOnly_returnsOne() {
        QcmRecord s = span(day(10, 9), true);
        List<QcmRecord> chosen = ZeroSpanDayPairSelector.select(Collections.singletonList(s));
        assertEquals(1, chosen.size());
        assertSame(s, chosen.get(0));
    }

    private static List<QcmRecord> list(QcmRecord... rs) {
        List<QcmRecord> l = new ArrayList<>();
        for (QcmRecord r : rs) {
            l.add(r);
        }
        return l;
    }

    private static QcmRecord zero(Instant start, boolean pass) {
        QcmRecord r = new QcmRecord();
        r.setQualityControlType(ZERO_CHECK.getCode());
        r.setParameter("1");
        r.setStartTime(start);
        r.setEndTime(start.plusSeconds(60));
        r.setExecutionLog(buildLog(pass));
        return r;
    }

    private static QcmRecord span(Instant start, boolean pass) {
        QcmRecord r = new QcmRecord();
        r.setQualityControlType(SPAN_CHECK.getCode());
        r.setParameter("1");
        r.setStartTime(start);
        r.setEndTime(start.plusSeconds(60));
        r.setExecutionLog(buildLog(pass));
        return r;
    }

    private static String buildLog(boolean pass) {
        return "{\"resultValue\":1,\"stdValue\":0,\"deviceValue\":0,\"checkPassLimit\":1,\"checkCalibLimit\":1,\"isPass\":"
                + pass + "}";
    }

    private static Instant day(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.FEBRUARY, 10, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.toInstant();
    }
}
