package com.ecat.integration.EnvQualityControlManagerIntegration.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.Instant;

/**
 * 质控执行阶段时间线子表 qcm_record_phase（§4.0 强类型结构化）。
 *
 * <p>执行完成时一次性冻结为快照写入；读时零关联，机器契约（hj212 补数等）读本表
 * 而非解析 execution_log。</p>
 *
 * @author coffee
 */
@Data
public class QcmRecordPhase {

    /** 子表行 ID */
    private Long id;

    /** 关联 qcm_record.id（逻辑关联，不物理外键；记录删除由应用层同步清理） */
    private Long recordId;

    /** 阶段序号（同 record 内有序） */
    private Integer seq;

    /** 阶段代码（如 check/zero/span） */
    private String phaseCode;

    /** 阶段名称（人读） */
    private String phaseName;

    /** 预计时长秒 */
    private Integer estimatedSeconds;

    /** 阶段开始时间（完成时冻结） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant startTime;

    /** 阶段结束时间（完成时冻结） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Instant endTime;
}
