# 03 · 对外 SDK

> 决策依据：D8（进程内 Java SDK，对齐 adm AirStationSdk 模式）、D10（拒绝也写记录）、用户原始需求：异步调用、远程标记、allowQueue 预留、结果可查。

## 1. 形态与获取

- **FR-03-01** qcm 新增 `api` 包：`QualityControlSdk` 接口 + 不可变 DTO（Java 8 兼容），零 ruoyi / Spring / vue / core 依赖（对齐 adm `api/AirStationSdk.java:8-21` 模式）。外部集成 maven 依赖 qcm jar（scope provided）只 import 本包。
- **FR-03-02** 获取方式为进程内 registry，不经 Spring bean：
  `core.getIntegrationRegistry().getIntegration("integration-env-quality-control-manager").getQualityControlSdk()`
  实现类为 qcm 内部 service，动态 jar 单例用 `@Service`（`@Component` 不被 DynamicJarLoader 注册的既有约束）。
- **FR-03-03** SDK 仅供外部集成使用；qcm 自身（页面立即执行 / 计划调度）走内部 service 直达同一执行入口，不绕 SDK（但记录模型完全一致）。

## 2. 能力清单

- **FR-03-04** 三个方法：

| 方法 | 用途 |
|---|---|
| `trigger(QcTriggerRequest)` | 触发一次质控执行（异步，立即返回） |
| `queryExecution(String batchId)` | 查批次执行状态与各记录摘要 |
| `getRecordDetail(long recordId)` | 查单条执行记录明细（含结果评定） |

## 3. trigger 契约

- **FR-03-05** 请求字段（不可变 DTO）：

| 字段 | 必填 | 类型 | 说明 |
|---|---|---|---|
| qcType | 是 | 枚举 8 种 | 任务类型（含新多仪器零点，⚠️ OPEN-03） |
| instruments | 是 | List | 仪器代码；仅多仪器零点允许 >1，其余必须恰好 1 |
| concentrationPpb | 条件 | Double | 跨度 / 人工核查必填（ppb）；零点 / 精密度 / 转换效率忽略 |
| pointPercents | 否 | List<Double> | 线性 / 准确度的量程百分比序列（0~1 小数，与页面/存储语义一致，见 01 §6）；缺省用默认序列 |
| flowRateLpm | 否 | Double | 标气流量 (0,50]；零点类忽略；缺省用计划/引擎默认 4.0 |
| durationOverrides | 否 | Map<String,Number> | 底层时长参数覆盖（键同 01 §6 参数表；多仪器零点适用零点类参数） |
| sourceName | 是 | String | 调用方标识（远程标记），如「LIMS 系统」；持久化为 trigger_user，记录页可见 |
| allowQueue | 是 | boolean | 是否允许排队；**本期仅支持 false**（FR-03-08） |

- **FR-03-06** 回复（不可变 DTO）两态：
  - `ACCEPTED`：`batchId` + `recordIds[]`（按仪器序）——已受理并异步开跑；
  - `REJECTED`：`reason` 枚举 + `message`（人读）+ `recordIds[]`（被拒留痕记录，见 FR-03-07）。
- **FR-03-07** 拒绝原因枚举（严格模式：每类拒绝原因明确，不静默兜底）：

| reason | 含义 | 是否写记录 |
|---|---|---|
| `BUSY_CONFLICT` | 执行器忙（已有任务运行中，且不允许排队） | 是（D10，每仪器一条失败记录） |
| `QUEUE_NOT_SUPPORTED` | allowQueue=true（本期不支持排队） | 否（参数级拒绝，未进入触发语义） |
| `INVALID_PARAM` | 参数校验失败（message 含字段与原因） | 否 |
| `EXECUTOR_TYPE_NOT_READY` | 新类型 composer 未接线（中间态） | 是（失败记录） |

仪器代码不合法 / 不在候选集并入 `INVALID_PARAM`（message 指明字段），不单列枚举。

- **FR-03-08** allowQueue=true → 恒返回 `QUEUE_NOT_SUPPORTED`，不猜测排队、不延迟重试；参数在签名中预留，后续支持排队时语义扩展为「忙时入队」而不破坏现有调用方。
- **FR-03-09** sourceName 非空校验；缺失 → INVALID_PARAM（远程标记是留痕硬要求）。

## 4. 异步语义

- **FR-03-10** trigger 调用**立即返回**（受理即返回，毫秒级），执行在 qcm 任务线程异步进行；调用线程不被执行过程阻塞，SDK 内部不得同步等待执行结果。
- **FR-03-11** 结果获取走查询（轮询由调用方自行决定节奏）：`queryExecution(batchId)` 返回批次状态（RUNNING / 终态）+ 每条记录摘要（仪器 / 状态 / 起止时间 / 结果评定 code）。不提供回调 / 监听器（⚠️ OPEN-04）。
- **FR-03-12** 批次终态判定：批次内全部记录终态（成功 / 失败 / 终止）后批次为终态。
- **FR-03-13** 线程安全：SDK 方法可多线程并发调用；内部收敛到统一执行入口与同一互斥闸（与计划 / 手动触发同一把锁，保证三源互斥判定一致）。

## 5. 结果与报告

- **FR-03-14** `getRecordDetail(recordId)` 返回执行明细：触发源 / 触发者 / 批次 / 计划快照、phaseTimelines（各阶段起止与预估）、监测数据、计算值、结果评定（复用现有记录字段语义，见 04 §3）。
- **FR-03-15** SDK 数据供给以「**外部调用方仅凭 SDK 即可自行生成报告**」为验收标准（用户定案 D15）：必须暴露现有内部报告生成器消费的全部数据——触发信息（源/触发者/批次）、计划快照、阶段时间线（各阶段起止/预估/状态）、各阶段采样过程数据、监测数据（monitoring_data）、标准值（standard_value）、计算值（calculated_value）、结果评定（result_evaluation）、仪器与气体信息。报告**文档模板渲染**（PDF/表格样式）仍不进 SDK，外部自行组织版式。

## 6. 错误与边界

- **FR-03-16** SDK 不抛检查型业务异常；参数 / 状态错误一律以 REJECTED + reason 返回；不可恢复的内部错误如实抛运行时异常（不吞、不返回伪造成功）。
- **FR-03-17** 跨类型约束校验与页面表单规则一致（FR-01-27/01-30 单一事实源，实现层共享同一校验器）。
