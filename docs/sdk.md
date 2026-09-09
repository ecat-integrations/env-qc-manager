# QCM 对外 SDK 手册（QualityControlSdk）

状态：2026-09-09 ｜ 与 `integration-env-qc-manager` 3.1.0 api 包同步 ｜ 契约真相源=api 包 javadoc，本文是人读整合手册，两者不一致以代码为准并回改本文

QCM（环境质控管理集成）对外触发/停止/查询能力的**进程内 SDK**：同一 JVM 内的其他集成经 `QualityControlSdk` 触发质控执行、按句柄停止、轮询批次终态并读取报告级明细，不经 REST、不带 ruoyi 鉴权语义。执行体由内部校准编排（composer）驱动真实设备，SDK 消费方不接触任何设备细节。

---

## 1. 能力总览

| 方法 | 一句话 | 落点 |
|---|---|---|
| `trigger` | 异步触发一次质控（毫秒级受理回执） | `qcm_record` 批次 + composer flow |
| `stop` | 按句柄/全停停止执行中批次（幂等） | flow 协作停止 + 恢复链 + 终态落库 |
| `queryRunning` | 当前运行中的执行（单飞语义下至多 1 项） | executorMap + `qcm_record` |
| `queryExecution` | 按批次轮询状态（终态判定） | `qcm_record` |
| `getRecordDetail` | 单记录全量明细（阶段时间线+快照，报告级） | `qcm_record` + 三子表 |
| `queryResults` | 按过滤条件批量读强类型结果快照（≤500 行） | 结果快照层 |
| `getExecutionResult` | 单记录强类型结果快照 | 结果快照层 |
| `queryPlans` | 质控计划当前设置目录（上报用） | `qcm_plan` |

## 2. 快速开始

**依赖**：消费方 pom 以 `provided` 引本集成 jar（SDK 与消费方同 JVM，由 core 类加载器体系装配，不打包传递）；跨集成类可见性须在消费方 `ecat-config.yml` 声明 `dependencies`（QCM artifactId=`integration-env-qc-manager`）。

**获取实例**（进程内 registry，不经 Spring bean）：

```java
QualityControlSdk sdk = ((EnvQualityControlManagerIntegration) core.getIntegrationRegistry()
    .getIntegration("integration-env-qc-manager"))
    .getQualityControlSdk();
```

**import 约束**：只允许 import `api` 包（接口+不可变 DTO）；护栏测试 `SdkApiZeroDependencyGuardTest` 强制 api 包零第三方依赖（仅 java.*/lombok），内部实现类不得反向泄漏。

**典型时序**（触发→轮询→结果 / 先看再停）：

```java
// ① 触发（受理即返，不等待执行）
SdkTriggerReply reply = sdk.trigger(SdkTriggerRequest.builder()
    .qcType("zero_check").instruments(List.of("SO2"))
    .operator(SdkOperator.builder().sourceType(SdkOperatorSource.PLATFORM)
        .name("scada").ip("10.0.0.1").port(5025).build())   // 必填
    .allowQueue(false)                                        // 排队不支持，必须 false
    .build());
if (!reply.isAccepted()) { /* 按 reply.getReason() 处理，见 §3.1 拒绝矩阵 */ }

// ② 轮询到终态（建议间隔 ≥5s；终态=全部记录 成功/失败）
while (!sdk.queryExecution(reply.getBatchId()).isTerminal()) { Thread.sleep(5000); }

// ③ 读结果（报告级明细）
SdkExecutionResult r = sdk.getExecutionResult(reply.getRecordIds().get(0));

// 停止侧：先看在跑什么，再全停（或按句柄停）
List<SdkRunningExecution> running = sdk.queryRunning();
if (!running.isEmpty()) {
    sdk.stop(SdkStopRequest.builder().allRunning(true)
        .operator(myOperator).build());
}
```

## 3. 方法参考

### 3.1 trigger — 异步触发

```java
SdkTriggerReply trigger(SdkTriggerRequest request)
```

- **受理即返**：accepted=true 时回执带 `batchId`/`recordIds`/`triggerRequestId` 三句柄（关系见 §5.3）；执行异步，结果靠 `queryExecution` 轮询。
- **参数域**：`qcType`（zero_check/span_check/multi_check/precision_check/accuracy_check/conversion_check/audit_span_check）+ `instruments`（仪器代码列表，如 SO2/NO2/O3/CO）；跨度/人工核查需 `concentrationPpb`，多点/准确度需 `pointPercents`——合法性由 qcm 内部**与计划参数同一套校验器**判定（非法逐字段报错，不静默修正）。
- **单飞互斥**：任一时刻至多 1 个运行批次。并发触发被 BUSY 拒绝（见下表），SDK 不支持排队（`allowQueue=true` 直接拒）——排队/重试策略由消费方自持。
- **拒绝矩阵**（`accepted=false`，reason 对应接口常量）：

| reason | 建行留痕 | 说明 |
|---|---|---|
| `BUSY_CONFLICT` | ✅ 批次 FAILED 行 | 有执行中任务；回执 recordIds 非空可查拒绝详情 |
| `EXECUTOR_TYPE_NOT_READY` | ✅ 批次 FAILED 行 | 执行器类型未接线（multi_zero_check 未就绪） |
| `INVALID_PARAM` | 参数完整时 ✅ / 残缺时 ❌ | 逐字段错误在 message；残缺（qcType/仪器不可解析）或 operator 缺失时仅 reply+log |
| `QUEUE_NOT_SUPPORTED` | 参数完整时 ✅ / 残缺时 ❌ | allowQueue=true |

### 3.2 stop — 按句柄/全停（批次粒度，幂等）

```java
SdkStopReply stop(SdkStopRequest request)
```

- **寻址四选一**：`recordId` / `batchId` / `triggerRequestId`（给恰好一个，多键或全空=INVALID_PARAM）或 `allRunning=true`（「不管在跑什么都停」；无运行批次 → `NOTHING_RUNNING`，**非故障**）。
- **批次粒度**：一个批次一个执行流，按批内任一 recordId 停 = 停整批；回执带 batchId/recordIds/qcType/instruments/startTime，停了什么一目了然。
- **幂等**：批次已终态或已在终止中 → `ALREADY_TERMINAL`，不改库（终态行的 end_time/结果是执行事实，重复 stop 零副作用）。
- **设备安全内置**：停止是协作式（不打断线程），composer 恢复链无条件执行（停气→关阀→ZERO_END/SPAN_END→气体消散→恢复采样），消费方无需也不能自行做设备收尾。
- **operator 必填**（见 §4.1）：缺失/空白 → `INVALID_PARAM`，不落痕。
- 终态仍经 `queryExecution` 轮询收敛；被停记录最终为 FAILED + resultEvaluation「流程被 {操作者} 手动终止」。

### 3.3 queryRunning — 运行中执行

```java
List<SdkRunningExecution> queryRunning()
```

单飞语义下至多 1 项（列表形态防未来并发策略变化）；空列表=无运行（非故障）。返回项的三个句柄即 `SdkStopRequest` 寻址键——「先看在跑什么再停」的入口。

### 3.4 queryExecution — 批次轮询

```java
SdkBatchState queryExecution(String batchId)
```

批次终态 = 批次内全部记录到达 成功/失败；等待中/执行中/手动中止中均为进行态。`records[]` 摘要含 statusName/resultEvaluation。batchId 为空或批次不存在抛 `IllegalArgumentException`。

### 3.5 getRecordDetail / queryResults / getExecutionResult — 结果读面

```java
SdkRecordDetail getRecordDetail(long recordId)          // 全量明细（阶段时间线+触发时快照）
List<SdkExecutionResult> queryResults(ResultFilter f)    // 批量过滤查询
SdkExecutionResult getExecutionResult(long recordId)     // 单记录强类型快照
```

- `queryResults`：`ResultFilter` 支持 begin/end/qcType/instrument/triggerSource/batchId/triggerRequestId 组合，**不能全空**（防全表扫）；命中超 500 行抛 IAE（消息带命中数，收窄时间窗）；结果按 start_time 降序；**不依赖报告任务跑过**（查询即补数）。
- `getExecutionResult` 是 `getRecordDetail` 的强类型升级（判定标量/关键参数快照/采样窗口/阶段时间线同构），外部仅凭 SDK 即可生成报告。

### 3.6 queryPlans — 计划设置目录

```java
List<SdkPlanSetting> queryPlans(String statusFilter)
```

「质控任务当前配置」类上报用：返回中性调度/检测项字段，协议字段名映射由调用方负责。statusFilter=ACTIVE/PAUSED/FINISHED 精确匹配；空=ACTIVE+PAUSED（已终结一次性计划不属于「当前设置」）。个别计划解析失败被跳过不整批失败。

## 4. DTO 参考（api 包，@Value/@Builder 不可变）

### 4.1 SdkOperator — 操作来源（trigger/stop 统一必填）

| 字段 | 约定 |
|---|---|
| `sourceType` | `SCHEDULE`（计划调度）/ `REST_USER`（页面人工）/ `SDK_LOCAL`（本地集成）/ `PLATFORM`（远程平台） |
| `name` | **必填**：system / 登录账号 / 集成名 / 平台标识 |
| `ip`、`port` | 仅 PLATFORM 由网络边界填真实对端（SDK 进程内探测不到，靠调用方传入事实） |
| `remark` | 自由文本（平台任务号/工单号等，仅审计追溯） |

**displayOperator 拼平**（qcm 内部落 `trigger_user`/`updated_by`，varchar(100)）：PLATFORM 且 ip 非空 → `name@ip[:port]`；其余形态 → `name`。停止成功的 result_evaluation 即「流程被 {displayOperator} 手动终止」——操作留痕在记录行内，详情弹窗/列表可见。

### 4.2 触发族

- `SdkTriggerRequest`：qcType / instruments / concentrationPpb / pointPercents / flowRateLpm / durationOverrides（稀疏，key 白名单同计划参数 14 键）/ **operator**（必填）/ allowQueue（必须 false）。
- `SdkTriggerReply`：accepted / batchId / recordIds / triggerRequestId / reason / message。

### 4.3 停止族

- `SdkStopRequest`：recordId / batchId / triggerRequestId / allRunning（寻址四选一）+ operator（必填）。
- `SdkStopReply`：accepted / reason / message + 被停批次上下文（batchId / recordIds / qcType / instruments / startTime；NOTHING_RUNNING、RECORD_NOT_FOUND 时批次字段为 null）。
- `SdkRunningExecution`：batchId / recordIds / triggerRequestId / qcType / instruments / startTime / triggerSource（计划/手动/远程）/ triggerUser。

### 4.4 其余

`SdkBatchState`（batchId/terminal/records[] 摘要）、`SdkRecordDetail`、`SdkExecutionResult`（判定标量+三子表）、`SdkPlanSetting`、`ResultFilter`——字段语义见 api 包 javadoc（真相源）。

## 5. 横切语义

1. **单飞**：任一时刻至多 1 个运行批次（互斥闸+忙闸）。触发撞忙 → BUSY 拒绝留痕；消费方需要「必然执行」语义就在自己侧排队重试。
2. **受理-轮询模式**：trigger/stop 都是毫秒级受理回执，执行与设备恢复异步完成；终态判定统一走 `queryExecution`（轮询间隔建议 ≥5s，qcm 不推送事件）。
3. **三句柄关系**：一次触发受理 = 1 个 `batchId`（执行批次，数据分组）；本次每台受检仪器各一行 record（`recordIds`，单仪器 1:1、多仪器 1:N）；`triggerRequestId` = 这次触发动作的请求标识（受理→轮询→结果全程同一，偏请求追踪）。三者在 trigger 回执中一次给全。
4. **被停的判定**：终态 FAILED + `resultEvaluation` 含「手动终止」文案——需要区分「被停」与「失败」的消费方以此字段判别（无独立 STOPPED 终态）。
5. **无数据 ≠ 正常**：queryResults 查不到行=无匹配执行，不是「通过」；BUSY/非法参数的拒绝批次也有 FAILED 行（可查拒绝原因），别把拒绝行当执行结果统计。

## 6. 消费方先例

当前**无外部 SDK 消费方**（现网远程触发走 hj212 任务框架，不经 SDK）。首个接入方注意：接入时随任务补验「PLATFORM 形态 `来源：name@ip:port` 列表显示」回归项（qcm-requirements §10 待补验项）。

## 7. 兼容与演进原则

- api 包零第三方依赖由 guard 测试锁死；状态/原因一律 String 常量，不 import qcm 内部枚举——外部类加载器无需可见 qcm 其余类。
- DTO 为 `@Value/@Builder` 不可变：加字段=非破坏演进；消费方禁反射内部实现类（`QualityControlSdkImpl` 及以下非 api 类型随时可变）。
- 停止语义（批次粒度/幂等/NOTHING_RUNNING 非故障）是契约级承诺，变更须升版本并在此手册同步。
