package com.ecat.integration.EnvQualityControlManagerIntegration;

import java.net.URLClassLoader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;
import javax.sql.DataSource;

import com.ecat.core.Integration.IIntegrationTaskManagement;
import com.ecat.core.Integration.IntegrationBase;
import com.ecat.core.Task.TaskExecutor;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.integration.EcatDbMigration.DbMigrationFacade;
import com.ecat.integration.EnvCalibrationComposerIntegration.AbstractCalibrationFlow;
import com.ecat.integration.EnvQualityControlManagerIntegration.schedule.QcmPlanScheduler;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IQcmRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.EnvQualityControlCustomTask;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.EnvQualityControlGenReportTask;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.EnvQualityControlTask;
/**
 * @author coffee
 */

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

        // 域自迁移:先照账本补刀再启动任何写路径;失败=本域明确异常,不株连他域。
        migrateOwnDomain();

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
    protected void onReleaseImpl() {
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

    /** 本域标识(与 ecat-config.yml db.domain、脚本目录 migration-&lt;domain&gt; 三处同名)。 */
    private static final String DB_DOMAIN = "qcm";

    /** 本域迁移脚本目录(字面量写死,不做拼接——脚本目录名编译期可见)。 */
    private static final String DB_LOCATION = "classpath:db/migration-qcm";

    /**
     * 本域启动期自迁移:先照账本补刀,再装载/启动任何写路径(谁的地盘谁负责)。失败=本域明确异常。
     * 两态接线:有账本表直接 migrate(幂等);无账本表先 baseline("0") 建账再 migrate(空库放行
     * 全量;0 小于 4.0.0,V4.0.0 不被挡)。未接管存量库在 migrate 处撞已存在表显式报错(「该域
     * 未接管」信号)——禁静默兜底。
     * 资源扫描类加载器由门面经 resourceAnchor(本集成类字面量)取得——域脚本在本仓 jar 内,
     * 唯此加载器可见;TCCL 由门面引擎侧处理,本调用方零线程状态操作。
     */
    void migrateOwnDomain() {
        EcatCoreRuoyiIntegration bridge =
                (EcatCoreRuoyiIntegration) integrationRegistry.getIntegration("integration-ecat-core-ruoyi");
        if (bridge == null) {
            throw new IllegalStateException("桥集成 integration-ecat-core-ruoyi 未注册,本域(" + DB_DOMAIN + ")迁移中止");
        }
        DataSource ds = bridge.getSpringBean(DataSource.class);
        if (ds == null) {
            throw new IllegalStateException("ruoyi Spring 容器未提供 DataSource Bean,本域(" + DB_DOMAIN + ")迁移中止");
        }
        if (DbMigrationFacade.hasHistoryTable(DB_DOMAIN, ds)) {
            DbMigrationFacade.migrate(DB_DOMAIN, ds,
                    Collections.singletonList(DB_LOCATION), EnvQualityControlManagerIntegration.class);
        } else {
            DbMigrationFacade.baseline(DB_DOMAIN, ds, "0");
            DbMigrationFacade.migrate(DB_DOMAIN, ds,
                    Collections.singletonList(DB_LOCATION), EnvQualityControlManagerIntegration.class);
        }
        log.info("域 {} 迁移完成", DB_DOMAIN);
    }
}
