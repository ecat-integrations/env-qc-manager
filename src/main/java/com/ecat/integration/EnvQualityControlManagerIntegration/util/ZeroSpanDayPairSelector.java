package com.ecat.integration.EnvQualityControlManagerIntegration.util;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlRecords;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.SPAN_CHECK;
import static com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum.ZERO_CHECK;

/**
 * 同一质控参数、同一自然日（按记录 {@link EnvQualityControlRecords#getStartTime()} 在站点时区的日历日）
 * 内的零点与跨度记录择优，生成一张「零点和跨度检查」报告所用的一组记录（0～2 条）。
 * <p>
 * 规则要点：
 * <ul>
 *   <li>优先选用当日「零点与跨度质控均通过」的一对（任意先后顺序）；并列时取时间较晚者（按两条记录 end 时间之和最大）。</li>
 *   <li>若无双通过：若「最后一次通过的零点」之后当日无跨度，则可用该零点与<strong>当日其开始时间之前</strong>的最近一次跨度组成报告（不要求零点早于跨度）。</li>
 *   <li>对称地：若「最后一次通过的跨度」之后当日无零点，则可用该跨度与当日其开始时间之前的最近一次零点。</li>
 *   <li>再退化：在含零与含跨的配对中最大化「通过」条数，并列取时间较晚；仅单边则返回一条。</li>
 * </ul>
 */
public final class ZeroSpanDayPairSelector {

    private ZeroSpanDayPairSelector() {
    }

    public static List<EnvQualityControlRecords> select(List<EnvQualityControlRecords> sameParamSameDay) {
        if (sameParamSameDay == null || sameParamSameDay.isEmpty()) {
            return Collections.emptyList();
        }
        List<EnvQualityControlRecords> zeros = sameParamSameDay.stream()
                .filter(r -> ZERO_CHECK.getCode().equals(r.getQualityControlType()))
                .sorted(Comparator.comparing(EnvQualityControlRecords::getStartTime, Comparator.nullsFirst(Comparator.naturalOrder())))
                .collect(Collectors.toList());
        List<EnvQualityControlRecords> spans = sameParamSameDay.stream()
                .filter(r -> SPAN_CHECK.getCode().equals(r.getQualityControlType()))
                .sorted(Comparator.comparing(EnvQualityControlRecords::getStartTime, Comparator.nullsFirst(Comparator.naturalOrder())))
                .collect(Collectors.toList());

        if (zeros.isEmpty() && spans.isEmpty()) {
            return Collections.emptyList();
        }
        if (zeros.isEmpty()) {
            return Collections.singletonList(latest(spans));
        }
        if (spans.isEmpty()) {
            return Collections.singletonList(latest(zeros));
        }

        // Tier 1: both pass, any order; tie-break: latest by sum of end times
        EnvQualityControlRecords t1z = null;
        EnvQualityControlRecords t1s = null;
        long t1Score = Long.MIN_VALUE;
        for (EnvQualityControlRecords z : zeros) {
            if (!pass(z)) {
                continue;
            }
            for (EnvQualityControlRecords s : spans) {
                if (!pass(s)) {
                    continue;
                }
                long score = endMillis(z) + endMillis(s);
                if (score > t1Score) {
                    t1Score = score;
                    t1z = z;
                    t1s = s;
                }
            }
        }
        if (t1z != null && t1s != null) {
            return asOrderedList(t1z, t1s);
        }

        // Tier 2a: last passing zero, no span on/after its start; pair with latest span strictly before zero.start
        for (int i = zeros.size() - 1; i >= 0; i--) {
            EnvQualityControlRecords z = zeros.get(i);
            if (!pass(z)) {
                continue;
            }
            if (hasSpanOnOrAfter(spans, z.getStartTime())) {
                continue;
            }
            Optional<EnvQualityControlRecords> predSpan = latestSpanStrictlyBefore(spans, z.getStartTime());
            if (predSpan.isPresent()) {
                return asOrderedList(z, predSpan.get());
            }
        }

        // Tier 2b: last passing span, no zero on/after its start; pair with latest zero strictly before span.start
        for (int i = spans.size() - 1; i >= 0; i--) {
            EnvQualityControlRecords s = spans.get(i);
            if (!pass(s)) {
                continue;
            }
            if (hasZeroOnOrAfter(zeros, s.getStartTime())) {
                continue;
            }
            Optional<EnvQualityControlRecords> predZero = latestZeroStrictlyBefore(zeros, s.getStartTime());
            if (predZero.isPresent()) {
                return asOrderedList(predZero.get(), s);
            }
        }

        // Tier 3: best (zero, span) by pass count then time
        EnvQualityControlRecords bestZ = null;
        EnvQualityControlRecords bestS = null;
        int bestPass = -1;
        long bestScore = Long.MIN_VALUE;
        for (EnvQualityControlRecords z : zeros) {
            for (EnvQualityControlRecords s : spans) {
                int pc = passCount(z, s);
                long score = endMillis(z) + endMillis(s);
                if (pc > bestPass || (pc == bestPass && score > bestScore)) {
                    bestPass = pc;
                    bestScore = score;
                    bestZ = z;
                    bestS = s;
                }
            }
        }
        if (bestZ != null && bestS != null) {
            return asOrderedList(bestZ, bestS);
        }

        // Tier 4: only one type (should not reach if both lists non-empty)
        EnvQualityControlRecords zLast = latest(zeros);
        EnvQualityControlRecords sLast = latest(spans);
        if (endMillis(zLast) >= endMillis(sLast)) {
            return Collections.singletonList(zLast);
        }
        return Collections.singletonList(sLast);
    }

    private static List<EnvQualityControlRecords> asOrderedList(EnvQualityControlRecords zero, EnvQualityControlRecords span) {
        List<EnvQualityControlRecords> out = new ArrayList<>(2);
        out.add(zero);
        out.add(span);
        return out;
    }

    private static EnvQualityControlRecords latest(List<EnvQualityControlRecords> sortedAsc) {
        return sortedAsc.get(sortedAsc.size() - 1);
    }

    private static boolean pass(EnvQualityControlRecords r) {
        return QualityControlExecutionLogHelper.readIsPass(r.getExecutionLog());
    }

    private static int passCount(EnvQualityControlRecords z, EnvQualityControlRecords s) {
        return (pass(z) ? 1 : 0) + (pass(s) ? 1 : 0);
    }

    private static long endMillis(EnvQualityControlRecords r) {
        Date e = r.getEndTime() != null ? r.getEndTime() : r.getStartTime();
        return e != null ? e.getTime() : 0L;
    }

    private static boolean hasSpanOnOrAfter(List<EnvQualityControlRecords> spans, Date pivot) {
        if (pivot == null) {
            return false;
        }
        long t = pivot.getTime();
        for (EnvQualityControlRecords s : spans) {
            if (s.getStartTime() != null && s.getStartTime().getTime() >= t) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasZeroOnOrAfter(List<EnvQualityControlRecords> zeros, Date pivot) {
        if (pivot == null) {
            return false;
        }
        long t = pivot.getTime();
        for (EnvQualityControlRecords z : zeros) {
            if (z.getStartTime() != null && z.getStartTime().getTime() >= t) {
                return true;
            }
        }
        return false;
    }

    private static Optional<EnvQualityControlRecords> latestSpanStrictlyBefore(List<EnvQualityControlRecords> spans, Date zeroStart) {
        if (zeroStart == null) {
            return Optional.empty();
        }
        long t = zeroStart.getTime();
        EnvQualityControlRecords best = null;
        for (EnvQualityControlRecords s : spans) {
            if (s.getStartTime() == null) {
                continue;
            }
            if (s.getStartTime().getTime() < t) {
                if (best == null || s.getStartTime().after(best.getStartTime())) {
                    best = s;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<EnvQualityControlRecords> latestZeroStrictlyBefore(List<EnvQualityControlRecords> zeros, Date spanStart) {
        if (spanStart == null) {
            return Optional.empty();
        }
        long t = spanStart.getTime();
        EnvQualityControlRecords best = null;
        for (EnvQualityControlRecords z : zeros) {
            if (z.getStartTime() == null) {
                continue;
            }
            if (z.getStartTime().getTime() < t) {
                if (best == null || z.getStartTime().after(best.getStartTime())) {
                    best = z;
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
