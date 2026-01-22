package com.ecat.integration.EnvQualityControlManagerIntegration.tasks;

import com.ecat.core.EcatCore;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.core.Task.Task;
import com.ecat.core.Utils.DynamicConfig.*;
import com.ecat.integration.EnvQualityControlManagerIntegration.domain.EnvQualityControlReport;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlReportService;
import com.ruoyi.common.utils.DateUtils;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * EnvQualityControlGenReportTask
 *
 * <p>生成质控仪器运行状况检查/校准记录表格<p/>

 * <p>根据<a href="#">HJ818-2018环境空气气态污染物（SO2、NO2、O3、CO）连续自动监测系统运行和质控技术规范</a>和<a href="#">HJ817-2018环境空气颗粒物（PM10 和 PM2.5）连续自动监测系统运行和质控技术规范</a>
 * 及<a href="#">监测仪器<a/>自动校准条件定期或及时地对仪器进行校准、性能审核，从而生成报表
 * </>
 * @author caohongbo
 * @version 1.0
 * @description
 */
@Component
public class EnvQualityControlGenReportTask extends Task {
    private EcatCore core;
    private EcatCoreRuoyiIntegration mry;

    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private ReportGenerator reportGenerator;

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
            Date beginTime;
            Date endTime;
            try {
                // 如果不传入开始结束时间，默认生成前一天的[preZeroTime, todayZeroTime]的报告，否则生成beginTime~endTime时间内的报告
                if (beginTimeString == null || beginTimeString.isEmpty()) {
                    endTime = DateUtils.zeroClockOfToday();
                    beginTime = DateUtils.previousDaysDate(1, Optional.of(endTime) );
                    endTime = new Date();
                } else {
                    beginTime = formatter.parse(beginTimeString);
                    endTime = formatter.parse(endTimeString);
                }
            } catch (ParseException e) {
                throw new RuntimeException("日期格式错误");
            }
            core = (EcatCore) parameters.get("core");

            // 生成beginTime~endTime时间内的报告
            ReportGenerator reportGenerator = new ReportGenerator(core);
            List <EnvQualityControlReport> reports = reportGenerator.generate(beginTime, endTime);

            if (reports.isEmpty()) {
                log.info("未生成报告"+ "["+beginTime+"~"+endTime+"]");
                return;
            }
            // 存储质控报告
            if (mry == null) {
                mry = (EcatCoreRuoyiIntegration) core.getIntegrationRegistry().getIntegration("integration-ecat-core-ruoyi");
            }
            IEnvQualityControlReportService envQualityControlReportService = mry.getSpringBean(IEnvQualityControlReportService.class);
            for (EnvQualityControlReport report : reports) {
                try {
                    int insertOne = envQualityControlReportService.save(report);
                    if (insertOne > 0) {
                        log.info("存储报告成功");
                    } else {
                        log.error("存储报告失败");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    log.error("存储报告失败", e);
                }
            }

            log.info("生成报告成功, 生成报告数量: " + reports.size() + ", 时间范围: ["+beginTime+"~"+endTime+"]");
        } catch (Exception e) {
            e.printStackTrace();
            log.error("生成报告失败", e);
            throw new RuntimeException(e);
        }
    }

}

