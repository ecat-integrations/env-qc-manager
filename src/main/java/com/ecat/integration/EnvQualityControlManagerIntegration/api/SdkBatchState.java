package com.ecat.integration.EnvQualityControlManagerIntegration.api;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/**
 * 批次执行状态（FR-03-11/12 轮询出参）：批次终态 = 全部记录终态
 * （成功/失败；等待中/执行中/手动中止中均为非终态）。
 */
@Value
@Builder
public class SdkBatchState {

    /** 批次标识 */
    String batchId;

    /** 批次是否已达终态（全部记录终态；空批次视为非终态由调用方经 IAE 感知） */
    boolean terminal;

    /** 批次内各记录摘要（与触发返回的 recordIds 一一对应） */
    List<RecordSummary> records;

    /** 单条记录轮询摘要（明细走 SdkRecordDetail）。 */
    @Value
    @Builder
    public static class RecordSummary {

        /** 记录 ID */
        long recordId;

        /** 仪器代码 */
        String instrument;

        /** 执行状态码（qcm_record.execution_status int 编码：0 等待/1 执行中/2 成功/3 失败/4 手动中止中） */
        int status;

        /** 执行状态展示名（等待中/执行中/成功/失败/手动中止） */
        String statusName;

        /** 执行开始时间 */
        Instant startTime;

        /** 执行结束时间（未结束时为 null） */
        Instant endTime;

        /** 结果评定（未结束时为 null） */
        String resultEvaluation;
    }
}
