# QCM 对外 SDK 手册（QualityControlSdk）

状态：2026-09-11 ｜ 与 `integration-env-qc-manager` 3.1.0 api 包**强类型词汇域重打型**同步（9 枚举）｜ 契约真相源=api 包 javadoc，本文是人读整合手册，两者不一致以代码为准并回改本文

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

**import 约束**：只允许 import `api` 包（接口+不可变 DTO+闭域词汇枚举）；护栏测试 `SdkApiZeroDependencyGuardTest` 强制 api 包零第三方依赖（仅 java.*/lombok），内部实现类不得反向泄漏——9 个词汇域枚举同受 guard 覆盖，装配期 CNFE 红线照旧。

**闭域词汇一律 api 枚举（编译期穷尽）**：值集封闭的词汇域在契约里全部是 api 包枚举——编译期穷尽、IDE 补全，调用方不看文档也不猜字符串；开放文本/标识（batchId/triggerRequestId/planName/instrumentName/triggerUser/resultEvaluation/message/单位串等）保持 String。**未知值行为**：入口枚举天然拒绝（编译期根本写不出未知值）；出口 DB 值译不出枚举 → 抛 `IllegalArgumentException` 带原值（不伪造不兜底）。操作来源 `SdkOperatorSource`（SCHEDULE/REST_USER/SDK_LOCAL/PLATFORM，见 §4.1）是本原则的既有先例。

**9 枚举值集速查**：

| 枚举（api 包） | 值集 | 出现位置 |
|---|---|---|
| `SdkInstrument` | `SO2` `NO2` `O3` `CO` `PM10` `PM2_5` | TriggerRequest/StopReply/RunningExecution/PlanSetting/ResultFilter 的 instruments·instrument、BatchState 摘要/ExecutionResult/RecordDetail 的 instrument |
| `SdkQcType` | `ZERO_CHECK` `SPAN_CHECK` `MULTI_CHECK` `PRECISION_CHECK` `ACCURACY_CHECK` `CONVERSION_CHECK` `AUDIT_SPAN_CHECK` `MULTI_ZERO_CHECK` | TriggerRequest/PlanSetting/ResultFilter 的 qcType、ExecutionResult/RecordDetail 的 qcType |
| `SdkTriggerSource` | `SCHEDULED` `MANUAL` `REMOTE` | RunningExecution/ExecutionResult/RecordDetail/ResultFilter 的 triggerSource |
| `SdkExecutionStatus` | `WAITING` `RUNNING` `SUCCESS` `FAILED` `STOPPING` | BatchState 摘要 statusName、ExecutionResult/RecordDetail 的 executionStatusName |
| `SdkReason` | `ACCEPTED` `BUSY_CONFLICT` `QUEUE_NOT_SUPPORTED` `INVALID_PARAM` `EXECUTOR_TYPE_NOT_READY` `STOP_INITIATED` `ALREADY_TERMINAL` `NOTHING_RUNNING` `RECORD_NOT_FOUND` | TriggerReply/StopReply 的 reason |
| `SdkPlanStatus` | `ACTIVE` `PAUSED` `FINISHED` | queryPlans 入参（null=默认 ACTIVE+PAUSED）、PlanSetting.status |
| `SdkScheduleType` | `DAILY` `WEEKLY` `MONTHLY` `ONCE` | PlanSetting.scheduleType |
| `SdkFailureReason` | 值集=api 枚举，以代码为准（与内部 failure_reason 落库值逐一对应） | RecordDetail/ExecutionResult 的 failureReason（null=无结构化原因） |
| `SdkDurationKey` | 值集=api 枚举，以代码为准（与计划参数时长键白名单同源） | TriggerRequest.durationOverrides 的键 |

**典型时序**（触发→轮询→结果 / 先看再停）：

```java
// ① 触发（受理即返，不等待执行）；闭域词汇全部枚举入参
Map<SdkDurationKey, Number> overrides = new EnumMap<>(SdkDurationKey.class);  // 键值集以 api 包为准
SdkTriggerReply reply = sdk.trigger(SdkTriggerRequest.builder()
    .qcType(SdkQcType.ZERO_CHECK)
    .instruments(List.of(SdkInstrument.SO2))
    .durationOverrides(overrides)                             // 稀疏覆盖：Map<SdkDurationKey,Number>，可省
    .operator(SdkOperator.builder().sourceType(SdkOperatorSource.PLATFORM)
        .name("scada").ip("10.0.0.1").port(5025).build())     // 必填
    .allowQueue(false)                                        // 排队不支持，必须 false
    .build());
if (!reply.isAccepted()) { /* reply.getReason() 是 SdkReason 枚举，见 §3.1 拒绝矩阵 */ }

// ② 轮询到终态（建议间隔 ≥5s；终态=全部记录 SUCCESS/FAILED）
while (!sdk.queryExecution(reply.getBatchId()).isTerminal()) { Thread.sleep(5000); }

// ③ 读结果（报告级明细）：instrument/qcType/triggerSource 已是枚举
SdkExecutionResult r = sdk.getExecutionResult(reply.getRecordIds().get(0));

// 停止侧：先看在跑什么，再全停（或按句柄停）
List<SdkRunningExecution> running = sdk.queryRunning();       // qcType/instruments/triggerSource 已枚举
if (!running.isEmpty()) {
    SdkStopReply stopped = sdk.stop(SdkStopRequest.builder().allRunning(true)
        .operator(myOperator).build());
    // stopped.getReason()：STOP_INITIATED=已发起 / NOTHING_RUNNING（非故障）/ ALREADY_TERMINAL / INVALID_PARAM
}
```

## 3. 方法参考

### 3.1 trigger — 异步触发

```java
SdkTriggerReply trigger(SdkTriggerRequest request)
```

- **受理即返**：accepted=true 时回执带 `batchId`/`recordIds`/`triggerRequestId` 三句柄（关系见 §5.3）且 `reason=ACCEPTED`；执行异步，结果靠 `queryExecution` 轮询。
- **参数域**：`qcType`（`SdkQcType` 8 值，见 §2 速查表）+ `instruments`（`List<SdkInstrument>`）——未知词汇编译期即写不出，不再有「字符串拼错运行期才炸」；跨度/人工核查需 `concentrationPpb`，多点/准确度需 `pointPercents`——合法性由 qcm 内部**与计划参数同一套校验器**判定（非法逐字段报错，不静默修正）。
- **单飞互斥**：任一时刻至多 1 个运行批次。并发触发被 BUSY 拒绝（见下表），SDK 不支持排队（`allowQueue=true` 直接拒）——排队/重试策略由消费方自持。
- **拒绝矩阵**（`accepted=false`，`reason` 为 `SdkReason` 枚举值；接口 `REASON_*` String 常量已删除，枚举取代）：

| reason（SdkReason） | 建行留痕 | 说明 |
|---|---|---|
| `BUSY_CONFLICT` | ✅ 批次 FAILED 行 | 有执行中任务；回执 recordIds 非空可查拒绝详情 |
| `EXECUTOR_TYPE_NOT_READY` | ✅ 批次 FAILED 行 | 执行器类型未接线（MULTI_ZERO_CHECK 未就绪） |
| `INVALID_PARAM` | 参数完整时 ✅ / 残缺时 ❌ | 逐字段错误在 message；残缺（qcType/仪器缺）或 operator 缺失时仅 reply+log |
| `QUEUE_NOT_SUPPORTED` | 参数完整时 ✅ / 残缺时 ❌ | allowQueue=true |

### 3.2 stop — 按句柄/全停（批次粒度，幂等）

```java
SdkStopReply stop(SdkStopRequest request)
```

- **寻址四选一**：`recordId` / `batchId` / `triggerRequestId`（标识键保持 String，给恰好一个，多键或全空=`INVALID_PARAM`）或 `allRunning=true`（「不管在跑什么都停」；无运行批次 → `NOTHING_RUNNING`，**非故障**）。
- **批次粒度**：一个批次一个执行流，按批内任一 recordId 停 = 停整批；回执带 batchId/recordIds/qcType/`List<SdkInstrument>`/startTime，停了什么一目了然。
- **幂等**：批次已终态或已在终止中 → `SdkReason.ALREADY_TERMINAL`，不改库（终态行的 end_time/结果是执行事实，重复 stop 零副作用）。
- **设备安全内置**：停止是协作式（不打断线程），composer 恢复链无条件执行（停气→关阀→ZERO_END/SPAN_END→气体消散→恢复采样），消费方无需也不能自行做设备收尾。
- **operator 必填**（见 §4.1）：缺失/空白 → `INVALID_PARAM`，不落痕。
- 终态仍经 `queryExecution` 轮询收敛；被停记录最终为 FAILED + resultEvaluation「流程被 {操作者} 手动终止」。

### 3.3 queryRunning — 运行中执行

```java
List<SdkRunningExecution> queryRunning()
```

单飞语义下至多 1 项（列表形态防未来并发策略变化）；空列表=无运行（非故障）。返回项的三个句柄即 `SdkStopRequest` 寻址键——「先看在跑什么再停」的入口。词汇域已枚举：`qcType`=`SdkQcType`、`instruments`=`List<SdkInstrument>`、`triggerSource`=`SdkTriggerSource`；batchId/triggerRequestId/triggerUser 保持 String。

### 3.4 queryExecution — 批次轮询

```java
SdkBatchState queryExecution(String batchId)
```

批次终态 = 批次内全部记录到达 `SUCCESS`/`FAILED`；`WAITING`/`RUNNING`/`STOPPING` 均为进行态。`records[]` 摘要含 `statusName`（类型 `SdkExecutionStatus`，**原中文名串已废，出英文枚举名**——消费方要中文展示自行 switch）与 resultEvaluation。batchId 为空或批次不存在抛 `IllegalArgumentException`。

### 3.5 getRecordDetail / queryResults / getExecutionResult — 结果读面

```java
SdkRecordDetail getRecordDetail(long recordId)          // 全量明细（阶段时间线+触发时快照）
List<SdkExecutionResult> queryResults(ResultFilter f)    // 批量过滤查询
SdkExecutionResult getExecutionResult(long recordId)     // 单记录强类型快照
```

- `queryResults`：`ResultFilter` 支持 begin/end/`qcType`（SdkQcType）/`instrument`（SdkInstrument）/`triggerSource`（SdkTriggerSource）/batchId/triggerRequestId 组合，**不能全空**（防全表扫）；命中超 500 行抛 IAE（消息带命中数，收窄时间窗）；结果按 start_time 降序；**不依赖报告任务跑过**（查询即补数）。
- **本版修复**：`ResultFilter.instrument` 同样枚举化——qcm 内部把 `SdkInstrument` 翻成存储码再下 SQL，修掉「消费方传字母名查空结果」的陷阱。
- `getExecutionResult` 是 `getRecordDetail` 的强类型升级（判定标量/关键参数快照/采样窗口/阶段时间线同构），外部仅凭 SDK 即可生成报告。

### 3.6 queryPlans — 计划设置目录

```java
List<SdkPlanSetting> queryPlans(SdkPlanStatus statusFilter)
```

「质控任务当前配置」类上报用：返回中性调度/检测项字段，协议字段名映射由调用方负责。`statusFilter`=`SdkPlanStatus`（ACTIVE/PAUSED/FINISHED）精确匹配；**null=默认 ACTIVE+PAUSED**（已终结一次性计划不属于「当前设置」）。个别计划解析失败被跳过不整批失败。

## 4. DTO 参考（api 包，@Value/@Builder 不可变）

### 4.1 SdkOperator — 操作来源（trigger/stop 统一必填）

| 字段 | 约定 |
|---|---|
| `sourceType`（`SdkOperatorSource`） | `SCHEDULE`（计划调度）/ `REST_USER`（页面人工）/ `SDK_LOCAL`（本地集成）/ `PLATFORM`（远程平台）——闭域枚举化的既有先例 |
| `name` | **必填**：system / 登录账号 / 集成名 / 平台标识 |
| `ip`、`port` | 仅 PLATFORM 由网络边界填真实对端（SDK 进程内探测不到，靠调用方传入事实） |
| `remark` | 自由文本（平台任务号/工单号等，仅审计追溯） |

**displayOperator 拼平**（qcm 内部落 `trigger_user`/`updated_by`，varchar(100)）：PLATFORM 且 ip 非空 → `name@ip[:port]`；其余形态 → `name`。停止成功的 result_evaluation 即「流程被 {displayOperator} 手动终止」——操作留痕在记录行内，详情弹窗/列表可见。

### 4.2 触发族

- `SdkTriggerRequest`：`qcType`（**SdkQcType**）/ `instruments`（**List\<SdkInstrument\>**）/ concentrationPpb / pointPercents / flowRateLpm / `durationOverrides`（**Map\<SdkDurationKey,Number\>**，稀疏——只放要改的键；键值集=api 枚举，与计划参数时长键白名单同源，未知键枚举层即拒）/ **operator**（必填）/ allowQueue（必须 false）。
- `SdkTriggerReply`：accepted / `reason`（**SdkReason**，受理=`ACCEPTED`——受理也显式给 reason，消除 accepted/reason 双通道歧义）/ batchId / recordIds / triggerRequestId / message。

### 4.3 停止族

- `SdkStopRequest`：recordId / batchId / triggerRequestId / allRunning（寻址四选一，标识键保持 String）+ operator（必填）。
- `SdkStopReply`：accepted / `reason`（**SdkReason**：STOP_INITIATED / ALREADY_TERMINAL / NOTHING_RUNNING / RECORD_NOT_FOUND / INVALID_PARAM）/ message + 被停批次上下文（batchId / recordIds / `qcType`（**SdkQcType**）/ `instruments`（**List\<SdkInstrument\>**）/ startTime；NOTHING_RUNNING、RECORD_NOT_FOUND 时批次字段为 null）。
- `SdkRunningExecution`：batchId / recordIds / triggerRequestId / `qcType`（**SdkQcType**）/ `instruments`（**List\<SdkInstrument\>**）/ startTime / `triggerSource`（**SdkTriggerSource**）/ triggerUser（displayOperator 拼平串，String）。

### 4.4 其余

| DTO | 枚举型字段 | String/其余 |
|---|---|---|
| `SdkBatchState` | records[] 摘要：`instrument`（SdkInstrument）、`qcType`（SdkQcType）、`statusName`（**SdkExecutionStatus**，英文枚举名替代原中文名串） | batchId、terminal、resultEvaluation |
| `SdkExecutionResult` | `instrument`、`qcType`、`triggerSource`、`executionStatusName`（SdkExecutionStatus）、`failureReason`（**SdkFailureReason**，null=无结构化原因，普通文案在 resultEvaluation） | 判定标量+三子表快照 |
| `SdkRecordDetail` | `instrument`、`qcType`、`triggerSource`、`executionStatusName`（SdkExecutionStatus）、`failureReason`（SdkFailureReason） | 阶段时间线+触发时快照 |
| `SdkPlanSetting` | `status`（**SdkPlanStatus**）、`scheduleType`（**SdkScheduleType**）、`qcType`（SdkQcType）、`instruments`（List\<SdkInstrument\>） | planName/instrumentName/instrumentNo/gasSource/gasNo（自由文本） |
| `ResultFilter` | `qcType`、`instrument`、`triggerSource` | begin/end/batchId/triggerRequestId |

未列字段语义见 api 包 javadoc（真相源）。

**本版修复注记（仪器词汇域统一）**：历史版本仪器「入参=字母名、出参=存储数字码泄漏」，首个消费方实踩。本版 SDK 面仪器词汇全程 `SdkInstrument`，「枚举↔存储码」互译收在 qcm 装配边界（内部域/DB 列零改动），触发→回执→摘要→结果→明细往返一致；`ResultFilter.instrument` 过滤同步枚举化（转存储码后下 SQL）。

## 5. 横切语义

1. **单飞**：任一时刻至多 1 个运行批次（互斥闸+忙闸）。触发撞忙 → BUSY 拒绝留痕；消费方需要「必然执行」语义就在自己侧排队重试。
2. **受理-轮询模式**：trigger/stop 都是毫秒级受理回执，执行与设备恢复异步完成；终态判定统一走 `queryExecution`（轮询间隔建议 ≥5s，qcm 不推送事件）。
3. **三句柄关系**：一次触发受理 = 1 个 `batchId`（执行批次，数据分组）；本次每台受检仪器各一行 record（`recordIds`，单仪器 1:1、多仪器 1:N）；`triggerRequestId` = 这次触发动作的请求标识（受理→轮询→结果全程同一，偏请求追踪）。三者在 trigger 回执中一次给全，均为 String（开放标识不枚举化）。
4. **被停的判定**：终态 FAILED + `resultEvaluation` 含「手动终止」文案——需要区分「被停」与「失败」的消费方以此字段判别（无独立 STOPPED 终态；`STOPPING` 是进行态）。
5. **无数据 ≠ 正常**：queryResults 查不到行=无匹配执行，不是「通过」；BUSY/非法参数的拒绝批次也有 FAILED 行（可查拒绝原因），别把拒绝行当执行结果统计。
6. **词汇域翻译收口**：9 域闭域词汇的「api 枚举 ↔ qcm 内部存储」互译全部收在装配边界，mapper/编排器/DB 列/REST 前端零改动。入口枚举拒绝未知值；出口 DB 值译不出枚举 → `IllegalArgumentException` 带原值（不伪造不兜底）。

## 6. 消费方先例

当前外部 SDK 消费方 1 家——首个接入方实踩「入参仪器字母名、出参存储数字码」缺陷，本版词汇域重打型即该缺陷的根治，随本版拿新契约（签名级破坏，无存量负担）。接入时随任务补验「PLATFORM 形态 `来源：name@ip:port` 列表显示」回归项（qcm-requirements §10 待补验项）。

## 7. 兼容与演进原则

- api 包零第三方依赖由 guard 测试锁死（9 个词汇域枚举同受覆盖）；契约原则已修订为「**闭域词汇一律 api 枚举（编译期穷尽），开放文本/标识用 String**」——原「状态与枚举一律 String 常量」原则废止。外部类加载器仍无需可见 qcm 其余类。
- **本版为契约破坏性重打型**（签名级）：9 域 String→枚举、`queryPlans(String)`→`queryPlans(SdkPlanStatus)`、接口 8 个 `REASON_*` String 常量删除；`SdkReason` 增 `ACCEPTED`（受理显式）。升级消费方须一次性跟随。
- **未知值契约**：入口枚举天然拒绝未知值；出口 DB 值译不出枚举 → `IllegalArgumentException` 带原值，不伪造不兜底。
- DTO 为 `@Value/@Builder` 不可变：加字段=非破坏演进；消费方禁反射内部实现类（`QualityControlSdkImpl` 及以下非 api 类型随时可变）。
- 停止语义（批次粒度/幂等/NOTHING_RUNNING 非故障）与词汇域互译规则是契约级承诺，变更须升版本并在此手册同步。
