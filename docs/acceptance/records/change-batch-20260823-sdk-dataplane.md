# 验收记录 · 变更批6：SDK 数据面强类型重构（07-SDK 文档 v2.4 自主批）· 2026-08-23

流程：07-SDK 分析文档 v1→v2.4 五轮演进（用户逐轮纠偏：第一性原理非教条迁移 / SdkExecutionResult 命名 /
完全快照独立保存读时零关联 / 强类型列+子表非 JSON / hj212 仅对标不迁移）→ 三步实施（DDL→快照写入器→SDK 读面+气源配置面）
→ 仪器冻结收尾 → 全 reactor 构建 → 部署库 DDL → core 重启 → 短时 E2E ×2 → DB 冻结局验证 → 浏览器 GasSetting 回归。**未 commit**。

## 交付物

### 1. 存储架构（qcm_data.sql 真相源，D13/D15/D18）
- `qcm_record` 38 列：原 3 列复活（standard_value/monitoring_data/calculated_value）+ 新增
  check_pass_limit/check_calib_limit/is_pass/slope/intercept/correlation/full_scale/instrument_name/instrument_no/
  gas_source/gas_no/gas_concentration/sampling_start_time/sampling_end_time/trigger_request_id/flow_type/flow_execution_ref；
- 三张子表：`qcm_record_phase`（阶段时间线 seq 化）、`qcm_record_key_param`（关键参数快照）、
  `qcm_record_point`（多点序列 std/device 双列）；execution_log 降级为原始归档（机器契约不再解析它）；
- `qcm_gas_info` 标气溯源配置表（gas_code 名称键 PK，SO2/NO2/CO/O3 四行 seed）。

### 2. 执行身份链（§4.0.1）
record.id（仪器级）→ batch_id（触发批次）→ **trigger_request_id**（受理时刻生成 UUID，trigger 返回值携带）→
**flow_type**（composer ExecutorType className，受理即冻结）→ **flow_execution_ref**（`{recordId}@{flowStartMillis}`，
composer 日志关联锚）。

### 3. 完成时冻结（ResultSnapshotWriter @Service）
`freezeResultSnapshot` 单事务：判定标量→强类型列（缺键如实 null 不猜测）；阶段→子表行；关键参数→子表行；
多点序列→points 行；标气溯源（名称词汇查 qcm_gas_info）；仪器识别（LogicDeviceReportSupport 同一解析路径，
完成时刻读一次设备取 name/sn）+ 满量程（与 Gen 零跨 FULL_SPAN 同源：CO=50ppm=50000ppb 归一，其余 500ppb）。
方法签名零 composer 类型（G-BUG-13 前例持续遵守）。

### 4. SDK 读面（api 包零依赖）
- `SdkExecutionResult` 5 层 @Value：event（触发/时间/身份链）/ process（阶段时间线）/ judgement
  （stdValues/deviceValues/slope/intercept/correlation/is_pass/limits）/ traceability（关键参数+标气+仪器）/ conclusion；
- `QualityControlSdk.queryResults(ResultFilter)`：begin/end/qcType/instrument/triggerSource/batchId/triggerRequestId
  动态过滤，LIMIT 501 上限 500 超限抛 IAE，空 filter 抛 IAE（不做全表扫）；
- `getExecutionResult(recordId)` 强类型装配，读时零关联（不查 registry/不 join plan/不读 live gas config）。

### 5. 标气溯源配置面（GasSetting）
卡片级「溯源配置」弹窗（来源/编号），保存整行覆盖；浓度/单位从缓存行原样回带（G-BUG-22 修复）。

## 过程缺陷（当场发现当场修，bugs/fixed/ 两卡）
- **G-BUG-21 词汇断裂**：writer 按数字代码查名称键表恒空 → ParameterEnum 翻译对齐 + DDL 注释更正 + TDD 锁定；
- **G-BUG-22 保存抹浓度**：弹窗 payload 漏带非编辑字段 → 整行覆盖契约下前端从缓存回带（后端无错不改）。

## 验证

| 层 | 结果 |
|---|---|
| qcm 模块单测 | **270/270 绿**（266→270：满量程归一/仪器冻结/解析失败跳过/词汇回归 4 用例；顺带修 1 旧用例非现实入参） |
| 全 reactor | `mvnd clean install -T 1C` **BUILD SUCCESS**（94 模块） |
| 部署库 | qcm_gas_info 建表+4 seed 落 47.94.6.129:5435/ecat；qcm_record 38 列/三子表已在前批就位（只读侦察后仅补差） |
| 短时 E2E | **14/14 PASS ×3**（record 16/17/25：SUCCESS+判定通过+快照口径+阶段时间线+报告合格；25 为最终 jar 一次通过） |
| DB 冻结局 | record 16 与 **record 25（最终 jar）**：std=400/monitor=400/calc=0/is_pass=true/full_scale=500/instr=Saimosen-SO2测试+sn/gas 三列全非空（25 含浓度 400）/trigger_request_id·flow_type·flow_execution_ref 齐/5 阶段起止完整/5 关键参数 ppb 口径/points=0（跨度检查单点无序列，如实空） |
| 快照隔离 | **record 16 冻 GBW-E-060522、record 17 冻 GBW-E-860823**——两次执行之间经浏览器换编号，历史记录保留执行当时值（§4.0 核心承诺实证） |
| 浏览器回归 | GasSetting 溯源弹窗：回显 ✓ → 表单保存 → 卡片编号更新 ✓ → DB 浓度 400/ppb 保留 ✓（G-BUG-22 修复实证） |

## 环境事件（非产品缺陷）——重启时机模型实测修正
- 继 16/17 全绿后，后续 6 次 E2E（record 18~24 尝试）先后以 `Failed to acquire lock` /
  `SPAN_START 拒绝` / `calibrator_command=start 拒绝` 失败，且越等越糟（等 10min/15min/重试 4 次全红）。
- **实测推翻「重启后等 10 分钟风暴窗」的旧模型**：真实机制是设备渐进上线——重启初期（0~10min）
  多数设备尚未开始轮询，modbus 命令锁空闲（**绿窗**）；全量轮询跑起来后全局调度池饱和、命令锁饿死
  （红窗可持续数小时）。已绿验证：重启 → 就绪即跑（~3min 内）→ record 25 **14/14 全绿**。
- 深层根因为既有已知架构缺陷（acquire 阻塞全局 2 线程池，见 memory
  「env-prepare-tcp-protocol-dual-source-starvation」），待 per-device executor 根治；
  SKILL.md 前置条件已按「重启后立即跑」修正。
- 所有失败尝试均走 D10 留痕（FAILED 记录 + failure_reason），行为正确。

## 遗留/待用户决策
- 权限串偏差：气源配置面用了 `quality_control:records:list/add` 而非任务书指定的 `gas_setting:edit`
  （records 权限已覆盖操作路径）；是否单拆 `gas_setting:edit` 权限串待用户定。
- hj212-sichuan 对标补数 demo（07-SDK 文档 §6）为既定 OUT 独立任务，本批未做（v2.4 决策：仅对标不迁移）。

执行人：主会话自主批（用户授权「你自主执行，我离开一会，等你全部验收后的成功结果」）
