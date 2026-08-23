package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.core.EcatCore;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.core.Task.Task;
import com.ecat.core.Utils.DynamicConfig.ConfigDefinition;
import com.ecat.core.Utils.DynamicConfig.ConfigItem;
import com.ecat.core.Utils.DynamicConfig.ConfigItemBuilder;
import com.ecat.core.Utils.DynamicConfig.StringEnumValidator;
import com.ecat.core.Utils.DynamicConfig.StringLengthValidator;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.QcmReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ParameterEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.ReportTypeEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.util.TaskTypeEnum;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EnvQualityControlGenReportTask
 *
 * <p>生成质控仪器运行状况检查/校准记录表格</p>
 *
 * <p>根据 HJ818-2018（环境空气气态污染物 SO2、NO2、O3、CO 连续自动监测系统运行和质控技术规范）、
 * HJ817-2018（环境空气颗粒物 PM10 和 PM2.5 连续自动监测系统运行和质控技术规范）及监测仪器
 * 自动校准条件，定期或及时地对仪器进行校准、性能审核，从而生成报表。</p>
 *
 * @author caohongbo
 * @version 2.0
 */
public class EnvQualityControlGenReportTask extends Task {

    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    /** 与前端/调度入参一致的时间串格式（Asia/Shanghai 本地时间） */
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 调度窗时区。与 QcmRecord.QUERY_WINDOW_ZONE / ReportGenerator.QC_REPORT_ZONE 同值不合并：查询窗、调度窗、报表归日三个域各自独立演进。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Override
    public String getTaskName() {
        return "EnvQualityControlGenReportTask";
    }

    @Override
    public String getDescription() {
        return "质控报告生成任务";
    }

    @Override
    protected ConfigDefinition getConfigDefinition() {
        // 定义配置项

        Set<String> validTriggerTypekValues = new HashSet<>(Arrays.asList("0", "1"));
        Set<String> validTaskTypeValues = TaskTypeEnum.getAllTaskTypeCodes();
        Set<String> validParameterValues = ParameterEnum.getAllParameterNameSet();
        Set<String> validReportTypeValues = ReportTypeEnum.getAllReportTypeSet();

        ConfigDefinition configDefinition = new ConfigDefinition();
        ConfigItemBuilder builder = new ConfigItemBuilder()
                .add(new ConfigItem<>("triggerType", String.class, false, null, new StringEnumValidator(validTriggerTypekValues)))
                .add(new ConfigItem<>("taskType", String.class, false, null, new StringEnumValidator(validTaskTypeValues)))
                .add(new ConfigItem<>("reportType", String.class, false, null, new StringEnumValidator(validReportTypeValues)))
                .add(new ConfigItem<>("parameter", String.class, false, null, new StringEnumValidator(validParameterValues)))
                .add(new ConfigItem<>("beginTime", String.class, false, null, new StringLengthValidator(0, 50)))
                .add(new ConfigItem<>("endTime", String.class, false, null, new StringLengthValidator(0, 50)));

        configDefinition.define(builder);
        return configDefinition;
    }

    @Override
    protected void executeImpl(Map<String, Object> parameters) {

        try {
            String beginTimeString = (String) parameters.get("beginTime");
            String endTimeString = (String) parameters.get("endTime");
            Instant beginTime;
            Instant endTime;
            try {
                // 如果不传入开始结束时间，默认生成前一天的[preZeroTime, todayZeroTime]的报告，否则生成beginTime~endTime时间内的报告
                if (beginTimeString == null || beginTimeString.isEmpty()) {
                    ZonedDateTime todayZero = LocalDate.now(ZONE).atStartOfDay(ZONE);
                    endTime = Instant.now();
                    beginTime = todayZero.minusDays(1).toInstant();
                } else {
                    beginTime = LocalDateTime.parse(beginTimeString, formatter).atZone(ZONE).toInstant();
                    endTime = LocalDateTime.parse(endTimeString, formatter).atZone(ZONE).toInstant();
                }
            } catch (DateTimeParseException e) {
                throw new RuntimeException("日期格式错误");
            }
            EcatCore core = (EcatCore) parameters.get("core");

            // 生成beginTime~endTime时间内的报告
            ReportGenerator reportGenerator = new ReportGenerator(core);
            List <QcmReport> reports = reportGenerator.generate(beginTime, endTime);

            if (reports.isEmpty()) {
                log.info("未生成报告"+ "["+beginTime+"~"+endTime+"]");
                return;
            }
            // 存储质控报告（G-BUG-8：不再持有惰性 mry 字段，每次执行按 core 现取 bean）
            EcatCoreRuoyiIntegration mry = (EcatCoreRuoyiIntegration) core.getIntegrationRegistry()
                    .getIntegration("integration-ecat-core-ruoyi");
            IQcmReportService qcmReportService = mry.getSpringBean(IQcmReportService.class);
            // 整批一次写入；失败不静默——记录失败批次摘要并汇总成功/失败条数，不中断后续任务
            int successCount;
            int failedCount;
            try {
                successCount = qcmReportService.saveBatch(reports);
                failedCount = reports.size() - successCount;
            } catch (Exception e) {
                successCount = 0;
                failedCount = reports.size();
                log.error("存储报告批次失败: 共 {} 条, 批次摘要: 首条 reportName={}, reportType={}, 失败原因: {}",
                        reports.size(),
                        reports.get(0).getReportName(),
                        reports.get(0).getReportType(),
                        e.getMessage(), e);
            }
            log.info("生成报告完成, 时间范围: [{}~{}], 成功 {} 条 / 失败 {} 条", beginTime, endTime, successCount, failedCount);
        } catch (Exception e) {
            log.error("生成报告失败", e);
            throw new RuntimeException(e);
        }
    }

}

