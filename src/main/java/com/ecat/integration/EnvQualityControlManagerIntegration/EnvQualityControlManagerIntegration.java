package com.ecat.integration.EnvQualityControlManagerIntegration;

import java.net.URLClassLoader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ecat.core.Integration.IIntegrationTaskManagement;
import com.ecat.core.Integration.IntegrationBase;
import com.ecat.core.Task.TaskExecutor;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.integration.EnvCalibrationComposerIntegration.AbstractCalibrationFlow;
import com.ecat.integration.EnvQualityControlManagerIntegration.schedule.QcmPlanScheduler;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.EnvQualityControlCustomTask;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.EnvQualityControlGenReportTask;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.EnvQualityControlTask;

public class EnvQualityControlManagerIntegration extends IntegrationBase  implements IIntegrationTaskManagement {

    private EcatCoreRuoyiIntegration mry;

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private TaskExecutor taskExecutor;

    public Map<Long, AbstractCalibrationFlow> executorMap = new ConcurrentHashMap<>();

    /** 对外 SDK（Phase 3）：onStart 经 Spring 容器取一次缓存；对外 API 需稳定，未就绪时 getter 硬抛。 */
    private volatile com.ecat.integration.EnvQualityControlManagerIntegration.api.QualityControlSdk qualityControlSdk;
    @Override
    public void onInit() {
        log.info("EnvQualityControlManagerIntegration initialized");
        mry = (EcatCoreRuoyiIntegration) integrationRegistry.getIntegration("integration-ecat-core-ruoyi");
    }

    @Override
    public void onStart() {
        log.info("EnvQualityControlManagerIntegration started");

        if (!(this.loadOption.getClassLoader() instanceof URLClassLoader)) {
            throw new IllegalStateException("类加载器不是URLClassLoader，无法动态加载");
        }
        // 将当前jar的类加载器转换为URLClassLoader
        URLClassLoader classLoader = (URLClassLoader) this.loadOption.getClassLoader();

        try {
            // mry.loadJar(classLoader);
            mry.loadJarAndVue(classLoader, this);
            // 创建任务执行器
            taskExecutor = new TaskExecutor(this);

            // 创建质控任务前先将因突然断电等导致任务中断的任务的状态改为失败：
            // 仅处理处于非终态（等待中=0/执行中=1/手动中止中=4）的记录，终态（成功/已失败）不覆盖。
            // 恢复失败只影响历史记录状态展示，不阻断任务注册，故 log.error 后继续启动。
            try {
                IQcmRecordService qcmRecordService = mry.getSpringBean(IQcmRecordService.class);
                int newExecutionStatus = 3; // 非终态改为失败
                String resultEvaluation = "任务运行期间意外中止";
                int updatedCount = qcmRecordService.updateRecordsExecutionStatus(newExecutionStatus, resultEvaluation, null, null);
                log.info(updatedCount + " EnvQualityControlTask was updated to failure on start.");
            } catch (Exception e) {
                log.error("Failed to reset interrupted quality control records on start, tasks will still be registered", e);
            }
            EnvQualityControlTask standardTask = new EnvQualityControlTask();
            EnvQualityControlCustomTask customTask = new EnvQualityControlCustomTask();
            registerResultFormatters(standardTask, customTask);
            cacheQualityControlSdk();
            taskExecutor.addTask(standardTask);
            taskExecutor.addTask(new EnvQualityControlGenReportTask());
            taskExecutor.addTask(customTask);

            // 质控计划精确唤醒调度器随集成启动（先做停机 misfire 扫描再 arm 最早计划）
            startPlanScheduler();
        } catch (Exception e) {
            log.error("EnvQualityControlManagerIntegration onStart failed", e);
        }

    }

    @Override
    public void onPause() {
        log.info("EnvQualityControlManagerIntegration paused");
        stopPlanScheduler();
    }

    @Override
    public void onRelease() {
        log.info("EnvQualityControlManagerIntegration released");
        stopPlanScheduler();
    }

    @Override
    public TaskExecutor getTaskExecutor() {
        return taskExecutor;
    }

    /** 向统一编排器注册两 Task 的结果格式化器（QcResultFormatter 缝）；bean 未就绪仅告警，触发时编排器会硬抛。 */
    private void registerResultFormatters(EnvQualityControlTask standardTask, EnvQualityControlCustomTask customTask) {
        try {
            com.ecat.integration.EnvQualityControlManagerIntegration.service.QcmExecutionOrchestrator orchestrator =
                    mry.getSpringBean(com.ecat.integration.EnvQualityControlManagerIntegration.service.QcmExecutionOrchestrator.class);
            if (orchestrator == null) {
                log.error("QcmExecutionOrchestrator bean 不存在，质控触发将在编排器侧硬抛");
                return;
            }
            orchestrator.setFormatters(java.util.Arrays.asList(standardTask, customTask));
        } catch (Exception e) {
            log.error("注册 QcResultFormatter 失败", e);
        }
    }

    /** 对外暴露的质控 SDK（Phase 3）；获取方式对齐 ADM：registry.getIntegration(...).getQualityControlSdk()。 */
    public com.ecat.integration.EnvQualityControlManagerIntegration.api.QualityControlSdk getQualityControlSdk() {
        com.ecat.integration.EnvQualityControlManagerIntegration.api.QualityControlSdk sdk = qualityControlSdk;
        if (sdk == null) {
            throw new IllegalStateException("QualityControlSdk 尚未就绪（集成 onStart 未完成或 bean 注册失败），稍后重试");
        }
        return sdk;
    }

    /** onStart 时取一次 SDK bean 缓存为 volatile 字段（对外 API 需稳定，不做每次 getSpringBean）。 */
    private void cacheQualityControlSdk() {
        try {
            qualityControlSdk = mry.getSpringBean(com.ecat.integration.EnvQualityControlManagerIntegration.service.QualityControlSdkImpl.class);
            if (qualityControlSdk == null) {
                log.error("QualityControlSdkImpl bean 不存在，getQualityControlSdk() 将抛 IllegalStateException");
            }
        } catch (Exception e) {
            log.error("缓存 QualityControlSdkImpl bean 失败", e);
        }
    }

    /** 启动计划调度器（bean 未就绪/拉取异常不阻断任务注册，error 后继续）。 */
    private void startPlanScheduler() {
        try {
            QcmPlanScheduler scheduler = mry.getSpringBean(QcmPlanScheduler.class);
            if (scheduler == null) {
                log.error("QcmPlanScheduler bean 不存在，质控计划调度未启动");
                return;
            }
            scheduler.start();
        } catch (Exception e) {
            log.error("QcmPlanScheduler 启动失败，质控计划调度未启动", e);
        }
    }

    /** 暂停/释放时关调度器（onPause 时 bean 可能尚未就绪，null 跳过即可，不视为异常）。 */
    private void stopPlanScheduler() {
        try {
            QcmPlanScheduler scheduler = mry.getSpringBean(QcmPlanScheduler.class);
            if (scheduler == null) {
                log.info("QcmPlanScheduler bean 未就绪或已释放，跳过 shutdown");
                return;
            }
            scheduler.shutdown();
        } catch (Exception e) {
            log.error("QcmPlanScheduler shutdown 失败", e);
        }
    }

}
