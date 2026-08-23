package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ruoyi.common.annotation.Excel;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 质控记录导出 VO：{@code @Excel} 列集与旧 env_quality_control_records 导出完全一致（列名/顺序不变）。
 *
 * <p>实体 QcmRecord 的 start/end 时间为 {@link Instant}（ExcelUtil 无法识别），
 * 在此处预格式化为 yyyy-MM-dd HH:mm:ss（Asia/Shanghai）String；实体保持 Instant 纯净。</p>
 *
 * @author coffee
 */
@Data
public class QcmRecordExportVo {

    /** 导出时间列格式（与前端展示时区一致，Asia/Shanghai；同值不合并：与 QcmRecord.QUERY_WINDOW_ZONE 各域独立演进） */
    private static final DateTimeFormatter EXPORT_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    @Excel(name = "任务类型")
    private String taskType;

    @Excel(name = "质控类型")
    private String qualityControlType;

    @Excel(name = "质控参数")
    private String parameter;

    @Excel(name = "开始时间", width = 30)
    private String startTime;

    @Excel(name = "结束时间", width = 30)
    private String endTime;

    @Excel(name = "标准值")
    private BigDecimal standardValue;

    @Excel(name = "监测数据")
    private BigDecimal monitoringData;

    @Excel(name = "计算值")
    private BigDecimal calculatedValue;

    @Excel(name = "执行状态")
    private Integer executionStatus;

    @Excel(name = "结果评价")
    private String resultEvaluation;

    /**
     * 实体 → 导出 VO 拷贝（Instant 预格式化；null 时间列输出空串，与旧空单元格行为一致）。
     */
    public static QcmRecordExportVo from(QcmRecord r) {
        QcmRecordExportVo vo = new QcmRecordExportVo();
        vo.setTaskType(r.getTaskType());
        vo.setQualityControlType(r.getQualityControlType());
        vo.setParameter(r.getParameter());
        vo.setStartTime(fmt(r.getStartTime()));
        vo.setEndTime(fmt(r.getEndTime()));
        vo.setStandardValue(r.getStandardValue());
        vo.setMonitoringData(r.getMonitoringData());
        vo.setCalculatedValue(r.getCalculatedValue());
        vo.setExecutionStatus(r.getExecutionStatus());
        vo.setResultEvaluation(r.getResultEvaluation());
        return vo;
    }

    /** 批量拷贝。 */
    public static List<QcmRecordExportVo> fromList(List<QcmRecord> records) {
        List<QcmRecordExportVo> vos = new ArrayList<>(records.size());
        for (QcmRecord r : records) {
            vos.add(from(r));
        }
        return vos;
    }

    private static String fmt(Instant t) {
        return t != null ? EXPORT_TIME_FORMATTER.format(t) : "";
    }
}
