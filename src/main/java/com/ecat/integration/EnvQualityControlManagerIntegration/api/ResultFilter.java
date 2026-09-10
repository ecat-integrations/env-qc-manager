package com.ecat.integration.EnvQualityControlManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * {@link QualityControlSdk#queryResults(ResultFilter)} 过滤条件（§4.1）：
 * 全部可选，但至少须提供一个——全空过滤等价全表扫，实现侧直接拒绝。
 *
 * <p>时间窗按 {@code qcm_record.start_time} 过滤，边界含（&gt;= begin 且 &lt;= end）。</p>
 *
 * @author coffee
 */
@Value
@Builder
public class ResultFilter {

    /** 时间窗起（含）：按 start_time */
    Instant begin;

    /** 时间窗止（含）：按 start_time */
    Instant end;

    /** 质控类型（span_check 等，精确） */
    String qcType;

    /** 仪器代码（精确） */
    String instrument;

    /** 触发源（SCHEDULED/MANUAL/REMOTE，精确） */
    String triggerSource;

    /** 批次标识（精确） */
    String batchId;

    /** 触发请求标识（精确） */
    String triggerRequestId;

    /** 是否一个条件都没有（全 null）——调用方侧防全表扫的判据。 */
    public boolean isEmpty() {
        return begin == null && end == null
                && (qcType == null || qcType.trim().isEmpty())
                && (instrument == null || instrument.trim().isEmpty())
                && (triggerSource == null || triggerSource.trim().isEmpty())
                && (batchId == null || batchId.trim().isEmpty())
                && (triggerRequestId == null || triggerRequestId.trim().isEmpty());
    }
}
