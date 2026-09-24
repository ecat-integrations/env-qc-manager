package com.ecat.integration.EnvQualityControlManagerIntegration.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * bean 类字节码隔离回归锁（ruoyi 延迟加载形态）。
 *
 * 生产装载链：ruoyi 侧加载 qcm 集成 jar 后立即注册全部 @Service 并 preInstantiateSingletons，
 * Spring 对 bean 类做 getDeclaredMethods 内省触发 JVM 链接+类型校验；彼时 composer 集成
 * （独立 jar，晚数秒加载）尚未进共享 loader。类型校验校验器解析字节码里校验强制位
 * （StackMapTable full_frame 点名的局部/栈类型、checkcast 目标、方法描述符参数）时必须加载
 * 对应类——orchestrator 一旦引用 composer 类型即 NoClassDefFoundError，bean 创建失败并
 * 级联拖垮整条执行链（签名层「不引用 composer 类型」的旧纪律管不到字节码局部变量帧）。
 *
 * 因此 QcmExecutionOrchestrator 作为 bean 类必须常量池零 composer 类引用：composer 触碰段
 * 收敛在非 bean 的协作类（首执行才加载，彼时 composer 必已就位）。两条断言锁死该边界，
 * 防「字节码层 composer 化」再次混入 bean 类：
 *
 * 1. 字节扫描：bean 类二进制不得出现 composer 类型名——任何引用形态（描述符/帧/checkcast）
 *    都会以 UTF8 常量落进 class 文件，一次扫描全覆盖；
 * 2. 隔离加载复现：在「composer 被屏蔽、其余依赖照常委派」的 loader 里重新定义一份
 *    orchestrator 副本再内省——副本的链接+校验必须不依赖 composer（与 ruoyi 侧共享
 *    loader 上 composer 缺席时的内省同路径）。
 *
 * @author coffee
 */
class QcmExecutionOrchestratorBeanIsolationTest {

    private static final String COMPOSER_PACKAGE = "com.ecat.integration.EnvCalibrationComposerIntegration.";
    private static final String COMPOSER_SAMPLE_TYPE = COMPOSER_PACKAGE + "ExecutorResultBase";
    private static final String BEAN_NAME = QcmExecutionOrchestrator.class.getName();

    /** composer 类型名出现在 bean 类的任何字节码引用位（描述符/帧/checkcast）都会落成 UTF8 常量。 */
    @Test
    void beanClassBytecode_mustNotReferenceComposerTypes() throws IOException {
        String binary = new String(readResource("QcmExecutionOrchestrator.class"), StandardCharsets.ISO_8859_1);
        assertFalse(binary.contains("EnvCalibrationComposerIntegration"),
                "QcmExecutionOrchestrator 是 ruoyi 内省路径上的 bean 类，字节码引用 composer 类型会让"
                        + "类校验在 composer 加载前 NoClassDefFoundError——composer 触碰段应放非 bean 协作类");
    }

    /** 镜像生产内省（Spring bean 创建期的 getDeclaredMethods 链路）在 composer 缺席时必须可完成。 */
    @Test
    void beanIntrospection_underClassLoaderWithoutComposer_mustNotThrow() throws Exception {
        ClassLoader real = QcmExecutionOrchestratorBeanIsolationTest.class.getClassLoader();
        assertNotNull(Class.forName(COMPOSER_SAMPLE_TYPE, false, real),
                "真实 loader 须能看到 composer——否则下面的屏蔽是空操作，测试空转");

        ClassLoader isolated = new ClassLoader(real) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(COMPOSER_PACKAGE)) {
                    throw new ClassNotFoundException(name + "：composer 在本 loader 上被刻意屏蔽");
                }
                if (name.equals(BEAN_NAME)) {
                    // 重定义副本：定义 loader 是本隔离 loader，其校验期解析才走本 loader 的屏蔽规则；
                    // 直接委派父级会复用早已链接的原类，校验被跳过，断言失去意义
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        byte[] bytes;
                        try {
                            bytes = readResource("QcmExecutionOrchestrator.class");
                        } catch (IOException e) {
                            throw new IllegalStateException("读取 bean 类字节失败", e);
                        }
                        c = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (resolve) {
                        resolveClass(c);
                    }
                    return c;
                }
                return super.loadClass(name, resolve);
            }
        };

        Class<?> bean = Class.forName(BEAN_NAME, false, isolated);
        assertEquals(isolated, bean.getClassLoader(),
                "内省对象必须是隔离 loader 定义的副本，而非父级已链接的原类");
        // 三个反射入口都会触发链接+类型校验，与 Spring 内省同路径
        bean.getDeclaredMethods();
        bean.getDeclaredFields();
        bean.getDeclaredConstructors();
    }

    private static byte[] readResource(String name) throws IOException {
        try (InputStream in = QcmExecutionOrchestrator.class.getResourceAsStream(name)) {
            assertNotNull(in, "类资源缺失: " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
