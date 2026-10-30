package com.ecat.integration.EnvQualityControlManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * {@link QualityControlSdk#queryResults(ResultFilter)} 与
 * {@link QualityControlSdk#queryResultsByEndTime(ResultFilter)} 共用的过滤条件（§4.1）：
 * 全部可选，但至少须提供一个——全空过滤等价全表扫，实现侧直接拒绝。
 *
 * <p>时间窗的归属列与区间开闭由<b>调用的方法</b>决定：</p>
 * <ul>
 *   <li>{@code queryResults}：按 {@code qcm_record.start_time}，闭区间 {@code [begin, end]}；</li>
 *   <li>{@code queryResultsByEndTime}：按 {@code qcm_record.end_time}，半开区间 {@code [begin, end)}，
 *       且 {@code end_time is null} 的行不出现在结果里。</li>
 * </ul>
 *
 * @author coffee
 */
@Value
@Builder
public class ResultFilter {

    /** 时间窗起（含）：queryResults 按 start_time；queryResultsByEndTime 按 end_time */
    Instant begin;

    /** 时间窗止：queryResults 按 start_time 且含（&lt;=）；queryResultsByEndTime 按 end_time 且不含（&lt;） */
    Instant end;

    /** 质控类型（精确；内部换算为存储词汇后下查） */
    SdkQcType qcType;

    /** 受检仪器（精确；内部换算为存储码后下查——传枚举即可，不再有「传字母名查空」陷阱） */
    SdkInstrument instrument;

    /** 触发源（精确） */
    SdkTriggerSource triggerSource;

    /** 批次标识（精确） */
    String batchId;

    /** 触发请求标识（精确） */
    String triggerRequestId;

    /** 是否一个条件都没有（全 null）——调用方侧防全表扫的判据。 */
    public boolean isEmpty() {
        return begin == null && end == null
                && qcType == null
                && instrument == null
                && triggerSource == null
                && (batchId == null || batchId.trim().isEmpty())
                && (triggerRequestId == null || triggerRequestId.trim().isEmpty());
    }
}
