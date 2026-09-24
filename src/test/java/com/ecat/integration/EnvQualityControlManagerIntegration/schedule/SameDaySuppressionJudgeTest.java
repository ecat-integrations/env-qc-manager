package com.ecat.integration.EnvQualityControlManagerIntegration.schedule;

import com.ecat.core.Utils.DateTimeUtils;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmPlan;
import com.ecat.integration.EnvQualityControlManagerIntegration.mapper.QcmPlanMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 同日压制判定矩阵（03 设计 §7.2，验收 F1–F10 单测层）：固定时钟 2026-09-22（周二）
 * 10:00 东八区，mock mapper 提供 ACTIVE 候选——判定是纯决策函数，无 IO 无 sleep。
 * 「今日有无触发点」按候选行配置纯算（含已过时刻），不看 next_fire_time 列。
 *
 * @author coffee
 */
class SameDaySuppressionJudgeTest {

    private static final Instant NOW = Instant.parse("2026-09-22T02:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private QcmPlanMapper planMapper;
    private SameDaySuppressionJudge judge;

    @BeforeEach
    void setUp() {
        planMapper = mock(QcmPlanMapper.class);
        judge = new SameDaySuppressionJudge(planMapper, Clock.fixed(NOW, ZONE));
        // 「今日」墙钟日的时区源 = ecat 平台时区单例，钉住与固定时钟同 zone 保确定性
        DateTimeUtils.setZone(ZONE);
    }

    @AfterEach
    void restorePlatformZone() {
        DateTimeUtils.setZone(ZoneId.systemDefault());
    }

    private static QcmPlan plan(long id, String name, String qcType, String priority, String instruments,
                                String scheduleType, String scheduleConfig) {
        QcmPlan p = new QcmPlan();
        p.setId(id);
        p.setPlanName(name);
        p.setQcType(qcType);
        p.setSameDayPriority(priority);
        p.setInstruments(instruments);
        p.setScheduleType(scheduleType);
        p.setScheduleConfig(scheduleConfig);
        p.setStatus("ACTIVE");
        return p;
    }

    private void stubActives(QcmPlan... plans) {
        when(planMapper.selectList(any(QcmPlan.class))).thenReturn(Arrays.asList(plans));
    }

    /** 触发行：multi_zero LOW 四气（日常模板形态），调度形态与判定无关。 */
    private QcmPlan dailyMultiZeroLow() {
        return plan(1, "日常-四气-零点", "multi_zero_check", "LOW",
                "[\"SO2\",\"NO2\",\"CO\",\"O3\"]", "INTERVAL",
                "{\"hour\":0,\"minute\":45,\"intervalDays\":2,\"anchorDate\":\"2026-09-22T00:00:00Z\"}");
    }

    /** 触发行：SO2 跨度 LOW。 */
    private QcmPlan dailySpanSo2Low() {
        return plan(2, "日常-SO2-跨度", "span_check", "LOW", "[\"SO2\"]", "INTERVAL",
                "{\"hour\":1,\"minute\":45,\"intervalDays\":2,\"anchorDate\":\"2026-09-22T00:00:00Z\"}");
    }

    // ---------- F1/F2：同类同日压制 ----------

    @Test
    void lowMultiZeroSuppressedByHighMultiZeroToday() {
        stubActives(plan(10, "周核查-四气-零点", "multi_zero_check", "HIGH",
                "[\"SO2\",\"NO2\",\"CO\",\"O3\"]", "WEEKLY", "{\"hour\":2,\"minute\":45,\"weekdays\":[2]}"));
        assertEquals(Optional.of("周核查-四气-零点"), judge.suppressedBy(dailyMultiZeroLow()));
    }

    @Test
    void lowSpanSuppressedByHighSameGasSpanToday() {
        // 2026-09-22 是周二；HIGH 周二 03:45 → 今日有触发点
        stubActives(plan(11, "周核查-SO2-跨度", "span_check", "HIGH",
                "[\"SO2\"]", "WEEKLY", "{\"hour\":3,\"minute\":45,\"weekdays\":[2]}"));
        assertEquals(Optional.of("周核查-SO2-跨度"), judge.suppressedBy(dailySpanSo2Low()));
    }

    // ---------- F3/F4：跨类不压 ----------

    @Test
    void highZeroFamilyNeverSuppressesLowSpan() {
        // 高优先级只配零点族（multi_zero 今日触发）——低优先级跨度的核查工作未被覆盖，照常执行
        stubActives(plan(12, "周核查-四气-零点", "multi_zero_check", "HIGH",
                "[\"SO2\"]", "DAILY", "{\"hour\":2,\"minute\":45}"));
        assertEquals(Optional.empty(), judge.suppressedBy(dailySpanSo2Low()));
    }

    // ---------- F5：今日无触发点不压 ----------

    @Test
    void highWithoutTriggerPointTodayNotSuppressing() {
        // HIGH 周一 03:45（昨日），今日周二无触发点
        stubActives(plan(13, "周核查-SO2-跨度", "span_check", "HIGH",
                "[\"SO2\"]", "WEEKLY", "{\"hour\":3,\"minute\":45,\"weekdays\":[1]}"));
        assertEquals(Optional.empty(), judge.suppressedBy(dailySpanSo2Low()));
    }

    // ---------- F6：PAUSED 由 ACTIVE 查询天然排除 / 窗口外 / 同优先级 / NONE ----------

    @Test
    void queriesActivePlansOnly() {
        // 压制候选查询口径：status=ACTIVE（PAUSED 行 DB 侧即被排除，判定层不再重复过滤）
        stubActives();
        judge.suppressedBy(dailySpanSo2Low());
        ArgumentCaptor<QcmPlan> captor = ArgumentCaptor.forClass(QcmPlan.class);
        verify(planMapper).selectList(captor.capture());
        assertEquals("ACTIVE", captor.getValue().getStatus());
    }

    @Test
    void highOutsideValidityWindowNotCounted() {
        QcmPlan expired = plan(14, "周核查-SO2-跨度", "span_check", "HIGH",
                "[\"SO2\"]", "DAILY", "{\"hour\":3,\"minute\":45}");
        expired.setPlanEndTime(Instant.parse("2026-09-21T00:00:00Z"));
        stubActives(expired);
        assertEquals(Optional.empty(), judge.suppressedBy(dailySpanSo2Low()));
    }

    @Test
    void samePriorityAndNoneNeverSuppress() {
        stubActives(
                plan(15, "另一日常-SO2-跨度", "span_check", "LOW", "[\"SO2\"]", "DAILY", "{\"hour\":3,\"minute\":45}"),
                plan(16, "无标记-SO2-跨度", "span_check", null, "[\"SO2\"]", "DAILY", "{\"hour\":3,\"minute\":45}"));
        assertEquals(Optional.empty(), judge.suppressedBy(dailySpanSo2Low()));
    }

    @Test
    void nonLowFiringRowNeverJudged() {
        // NONE/HIGH 触发行不让位：短路不查库（无谓的候选扫描与纯算）
        for (String priority : Arrays.asList(null, "NONE", "HIGH")) {
            QcmPlan firing = dailySpanSo2Low();
            firing.setSameDayPriority(priority);
            assertEquals(Optional.empty(), judge.suppressedBy(firing), "priority=" + priority);
        }
        verifyNoInteractions(planMapper);
    }

    @Test
    void outOfFamilyFiringTypeNeverJudged() {
        // 两族之外无同类压制语义（多点/精密度等），同样短路不查库
        QcmPlan firing = plan(3, "日常-多点", "multi_check", "LOW", "[\"SO2\"]", "DAILY", "{\"hour\":2,\"minute\":0}");
        assertEquals(Optional.empty(), judge.suppressedBy(firing));
        verifyNoInteractions(planMapper);
    }

    // ---------- F7：配置上今日应触发即压（含已过时刻） ----------

    @Test
    void highCreatedTodayWithPassedTriggerPointStillSuppresses() {
        // HIGH 当日 08:00 已过（now=10:00），next_fire_time 列已被调度器推走——按配置纯算今日仍有触发点
        stubActives(plan(17, "今日新建-SO2-跨度", "span_check", "HIGH",
                "[\"SO2\"]", "DAILY", "{\"hour\":8,\"minute\":0}"));
        assertEquals(Optional.of("今日新建-SO2-跨度"), judge.suppressedBy(dailySpanSo2Low()));
    }

    @Test
    void triggerAtMidnightTodayCounts() {
        // 「今日 00:00–24:00 含已过时刻」边界：00:00:00 的触发点也计入（after=昨日末一瞬）
        stubActives(plan(18, "零点整-SO2-跨度", "span_check", "HIGH",
                "[\"SO2\"]", "DAILY", "{\"hour\":0,\"minute\":0}"));
        assertEquals(Optional.of("零点整-SO2-跨度"), judge.suppressedBy(dailySpanSo2Low()));
    }

    // ---------- F10：气种覆盖不全整行让位 / 不相交不压 ----------

    @Test
    void partialGasOverlapSuppressesWholeRow() {
        // 周 multi-zero 只配 SO2 → 日常四气 multi 整行让位（行是最小调度单元，不拆分）
        stubActives(plan(19, "周核查-单气-零点", "multi_zero_check", "HIGH",
                "[\"SO2\"]", "DAILY", "{\"hour\":2,\"minute\":45}"));
        assertEquals(Optional.of("周核查-单气-零点"), judge.suppressedBy(dailyMultiZeroLow()));
    }

    @Test
    void disjointInstrumentsNotSuppressing() {
        // HIGH 跨度只配 NO2，LOW 跨度是 SO2——气种集合不相交，本行工作未被覆盖
        stubActives(plan(20, "周核查-NO2-跨度", "span_check", "HIGH",
                "[\"NO2\"]", "DAILY", "{\"hour\":3,\"minute\":45}"));
        assertEquals(Optional.empty(), judge.suppressedBy(dailySpanSo2Low()));
    }

    // ---------- INTERVAL 高优先级行（与调度纯函数联动） ----------

    @Test
    void intervalHighAlignedTodaySuppresses() {
        // 锚点 09-20、间隔 2 → 对齐日 09-20/09-22…，今日周二对齐 → 压
        stubActives(plan(21, "间隔-SO2-跨度", "span_check", "HIGH",
                "[\"SO2\"]", "INTERVAL",
                "{\"hour\":3,\"minute\":45,\"intervalDays\":2,\"anchorDate\":\"2026-09-20T00:00:00Z\"}"));
        assertEquals(Optional.of("间隔-SO2-跨度"), judge.suppressedBy(dailySpanSo2Low()));
    }

    @Test
    void intervalHighNotAlignedTodayNotSuppressing() {
        // 锚点 09-19、间隔 2 → 对齐日 09-19/09-21/09-23，今日 09-22 不对齐 → 不压
        stubActives(plan(22, "错位-SO2-跨度", "span_check", "HIGH",
                "[\"SO2\"]", "INTERVAL",
                "{\"hour\":3,\"minute\":45,\"intervalDays\":2,\"anchorDate\":\"2026-09-19T00:00:00Z\"}"));
        assertEquals(Optional.empty(), judge.suppressedBy(dailySpanSo2Low()));
    }

    // ---------- 坏行防御：候选配置不可解析跳过，不阻碍判定 ----------

    @Test
    void corruptHighConfigCandidateSkipped() {
        stubActives(
                plan(23, "坏行-跨度", "span_check", "HIGH", "[\"SO2\"]", "WEEKLY", "{not-json"),
                plan(24, "好行-SO2-跨度", "span_check", "HIGH",
                        "[\"SO2\"]", "DAILY", "{\"hour\":3,\"minute\":45}"));
        // 坏行跳过后继续看下一个候选——不让坏行既不压制也不炸掉整个判定
        assertEquals(Optional.of("好行-SO2-跨度"), judge.suppressedBy(dailySpanSo2Low()));
    }

    @Test
    void corruptHighInstrumentsCandidateSkipped() {
        stubActives(plan(25, "坏行仪器-跨度", "span_check", "HIGH", "{bad", "DAILY", "{\"hour\":3,\"minute\":45}"));
        assertEquals(Optional.empty(), judge.suppressedBy(dailySpanSo2Low()));
    }

    @Test
    void emptyCandidateListNotSuppressing() {
        stubActives();
        assertEquals(Optional.empty(), judge.suppressedBy(dailySpanSo2Low()));
    }
}
