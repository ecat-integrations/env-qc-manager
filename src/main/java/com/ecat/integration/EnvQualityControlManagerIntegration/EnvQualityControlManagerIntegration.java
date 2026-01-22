package com.ecat.integration.EnvQualityControlManagerIntegration;

import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;

import com.ecat.core.Integration.IIntegrationTaskManagement;
import com.ecat.core.Integration.IntegrationBase;
import com.ecat.core.Task.TaskExecutor;
import com.ecat.integration.EcatCoreRuoyiIntegration.EcatCoreRuoyiIntegration;
import com.ecat.integration.EnvDeviceCalibrationIntegration.ExecutorBase;
import com.ecat.integration.EnvQualityControlManagerIntegration.service.IEnvQualityControlRecordsService;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.EnvQualityControlCustomTask;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.EnvQualityControlGenReportTask;
import com.ecat.integration.EnvQualityControlManagerIntegration.tasks.EnvQualityControlTask;

public class EnvQualityControlManagerIntegration extends IntegrationBase  implements IIntegrationTaskManagement {

    private EcatCoreRuoyiIntegration mry;

    private TaskExecutor taskExecutor;

    public Map<Long, ExecutorBase> executorMap = new HashMap<>();;
    @Override
    public void onInit() {
        System.out.println("EnvQualityControlManagerIntegration initialized");
        mry = (EcatCoreRuoyiIntegration) integrationRegistry.getIntegration("integration-ecat-core-ruoyi");
    }

    @Override
    public void onStart() {
        System.out.println("EnvQualityControlManagerIntegration started");

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

            // 创建质控任务前先将因突然断电等导致任务中断的任务的状态由运行改为失败
            try {
                IEnvQualityControlRecordsService envQualityControlRecordsService = mry.getSpringBean(IEnvQualityControlRecordsService.class);
                int newExecutionStatus = 3; // 运行改为失败
                String resultEvaluation = "任务运行期间意外中止";
                int updatedCount = envQualityControlRecordsService.updateRecordsExecutionStatus(newExecutionStatus, resultEvaluation, null, null);
                log.error(updatedCount + " EnvQualityControlTask was updated to failure on start." );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            taskExecutor.addTask(new EnvQualityControlTask(executorMap));
            taskExecutor.addTask(new EnvQualityControlGenReportTask());
            taskExecutor.addTask(new EnvQualityControlCustomTask());
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @Override
    public void onPause() {
        System.out.println("EnvQualityControlManagerIntegration paused");
    }

    @Override
    public void onRelease() {
        System.out.println("EnvQualityControlManagerIntegration released");
    }

    @Override
    public TaskExecutor getTaskExecutor() {
        return taskExecutor;
    }

}
