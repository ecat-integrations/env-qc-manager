package com.ecat.integration.EnvQualityControlManagerIntegration.controller.dto;

import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmRecord;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.QualityControlTypeEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.TaskTypeEnum;
import com.ruoyi.common.annotation.Excel;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 质控记录导出 VO：列集沿用旧 env_quality_control_records 导出（顺序不变），
 * 首列由「任务类型」升级为「触发来源」（taskType 展示名 + 触发者/来源，与 records 页展示规则一致）。
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

    @Excel(name = "触发来源")
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
     * taskType/qualityControlType 落库为编码，导出时译为展示名；未知编码透传原值（不猜默认）。
     */
    public static QcmRecordExportVo from(QcmRecord r) {
        QcmRecordExportVo vo = new QcmRecordExportVo();
        vo.setTaskType(triggerSourceText(r.getTaskType(), r.getTriggerUser()));
        vo.setQualityControlType(qcTypeDisplayName(r.getQualityControlType()));
        vo.setParameter(parameterDisplayName(r.getParameter()));
        vo.setStartTime(fmt(r.getStartTime()));
        vo.setEndTime(fmt(r.getEndTime()));
        vo.setStandardValue(r.getStandardValue());
        vo.setMonitoringData(r.getMonitoringData());
        vo.setCalculatedValue(r.getCalculatedValue());
        vo.setExecutionStatus(r.getExecutionStatus());
        vo.setResultEvaluation(r.getResultEvaluation());
        return vo;
    }

    /**
     * 触发来源单格文本，与 records 页「触发来源」列第二行明细同规则：
     * 计划触发不带操作人（调度用户 system 是噪音）；手动/现场带操作人；远程带「来源：」前缀。
     */
    private static String triggerSourceText(String taskType, String triggerUser) {
        if (taskType == null || taskType.isEmpty()) {
            return taskType;
        }
        String name = TaskTypeEnum.displayNameOf(taskType);
        String base = name != null ? name : taskType;
        String user = triggerUser != null ? triggerUser.trim() : "";
        if (user.isEmpty() || "0".equals(taskType)) {
            return base;
        }
        if ("3".equals(taskType)) {
            return base + " 来源：" + user;
        }
        return base + " " + user;
    }

    /** 质控类型编码 → 展示名；未知编码（含 legacy "calibration_check"）透传原值。 */
    private static String qcTypeDisplayName(String qualityControlType) {
        QualityControlTypeEnum e = QualityControlTypeEnum.fromCode(qualityControlType);
        return e != null ? e.getDisplayName() : qualityControlType;
    }

    /** 质控参数编码 → 化学符号（落库存 ParameterEnum 数字码，导出裸码不可读）；已是符号/未知值透传。 */
    private static String parameterDisplayName(String parameter) {
        if (parameter == null || parameter.isEmpty()) {
            return parameter;
        }
        String name = ParameterEnum.getNameByCode(parameter);
        return name != null ? name : parameter;
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
