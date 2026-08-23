package com.ecat.integration.EnvQualityControlManagerIntegration.domain;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 质控执行数据点子表 qcm_record_point（§4.0 多点校准/精密度序列）。
 *
 * <p>执行完成时一次性冻结为快照写入；读时零关联。
 * 判定层的 stdValues[]/deviceValues[] 序列落此处，不再塞 JSON。</p>
 *
 * @author coffee
 */
@Data
public class QcmRecordPoint {

    /** 子表行 ID */
    private Long id;

    /** 关联 qcm_record.id（逻辑关联，不物理外键；记录删除由应用层同步清理） */
    private Long recordId;

    /** 数据点序号（同 record 内有序） */
    private Integer seq;

    /** 标准值（该点标气浓度） */
    private BigDecimal stdValue;

    /** 仪器读数（该点） */
    private BigDecimal deviceValue;
}
