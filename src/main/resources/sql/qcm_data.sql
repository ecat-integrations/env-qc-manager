-- ============================================================================
-- qcm_data.sql — env-quality-control-manager 数据层换代（qcm_ 前缀三新表 + 18 条计划 seed）
-- 依据：docs/requirements/04-数据模型.md（§1-§4）、06-种子与迁移.md（§3）。
-- 库：ruoyi 主库（PostgreSQL）public schema。app 不自动跑；运维手动 apply，幂等可重跑。
-- 执行顺序（新库全量）：ruoyi public.sql（已不含 qcm 旧表）→ qcm_data.sql → qcm_auth.sql。
-- 幂等设计：CREATE TABLE/INDEX IF NOT EXISTS；COMMENT ON 天然幂等；
--           seed 以 plan_name 为自然键 WHERE NOT EXISTS 守卫（无唯一约束，不用 ON CONFLICT）。
--
-- 【已部署库人工处置 SQL——由运维确认后手动执行，本文件不自动执行】（FR-06-03 / FR-06-06）：
--   -- 1) 删除旧质控任务组 sys_job 21-41（id 15/42/43 不动，FR-06-04）：
--   DELETE FROM sys_job WHERE job_id BETWEEN 21 AND 41;
--   -- 2) DROP 三张旧表（qcm 无生产数据，用户确认 D13，不迁移）：
--   DROP TABLE IF EXISTS env_quality_control_plan;
--   DROP TABLE IF EXISTS env_quality_control_records;
--   DROP TABLE IF EXISTS env_quality_control_report;
--   -- 3) §4.0 强类型换代的已部署库增列（幂等可重跑；ALTER 不破坏存量行，
--   --    trigger_request_id 因此留 nullable，新写入路径恒有值）：
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS check_pass_limit numeric;
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS check_calib_limit numeric;
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS is_pass bool;
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS slope numeric;
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS intercept numeric;
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS correlation numeric;
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS full_scale numeric;
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS instrument_name varchar(100);
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS instrument_no varchar(50);
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS gas_source varchar(100);
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS gas_no varchar(50);
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS gas_concentration numeric;
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS gas_concentration_unit varchar(20);
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS sampling_start_time timestamptz;
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS sampling_end_time timestamptz;
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS trigger_request_id varchar(36);
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS flow_type varchar(100);
--   ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS flow_execution_ref varchar(100);
--   -- 增列后重跑本文件即可带上 COMMENT / 子表 / 索引（均 IF NOT EXISTS 幂等）。
--   -- 4) SDK 停止执行留痕的列宽扩展（幂等可重跑；varchar 扩宽不破坏存量行）：
--   --    触发与停止都写 displayOperator，PLATFORM 形态 name@ip[:port]（含 IPv6）可超 50。
--   ALTER TABLE qcm_record ALTER COLUMN trigger_user TYPE varchar(100);
--   ALTER TABLE qcm_record ALTER COLUMN updated_by   TYPE varchar(100);
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 表 1：qcm_plan 质控任务计划表（FR-04-05 ~ FR-04-07）
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS qcm_plan (
    id                 bigserial     PRIMARY KEY,
    plan_name          varchar(100)  NOT NULL,
    qc_type            varchar(50)   NOT NULL,
    instruments        jsonb         NOT NULL,
    schedule_type      varchar(20)   NOT NULL,
    schedule_config    jsonb         NOT NULL,
    concentration_ppb  numeric,
    flow_rate_lpm      numeric,
    duration_overrides jsonb,
    point_percents     jsonb,
    plan_start_time    timestamptz,
    plan_end_time      timestamptz,
    status             varchar(20)   NOT NULL,
    next_fire_time     timestamptz,
    last_fire_time     timestamptz,
    created_by         varchar(50)   NOT NULL,
    updated_by         varchar(50)   NOT NULL,
    create_time        timestamptz   NOT NULL DEFAULT now(),
    update_time        timestamptz   NOT NULL DEFAULT now()
);

COMMENT ON TABLE qcm_plan IS '质控任务计划表（调度模型换代，不存 cron 表达式）';
COMMENT ON COLUMN qcm_plan.id IS '计划 ID';
COMMENT ON COLUMN qcm_plan.plan_name IS '计划名称';
COMMENT ON COLUMN qcm_plan.qc_type IS '质控类型 code（QualityControlTypeEnum.name：zero_check/span_check/multi_check/precision_check/accuracy_check/conversion_check/audit_span_check + 新增 multi_zero_check）';
COMMENT ON COLUMN qcm_plan.instruments IS '仪器代码数组（SO2/NO2/CO/O3）；单仪器类型长度恒 1';
COMMENT ON COLUMN qcm_plan.schedule_type IS '调度类型：DAILY / WEEKLY / MONTHLY / ONCE';
COMMENT ON COLUMN qcm_plan.schedule_config IS '调度配置 {hour, minute, weekdays[], monthDays[], onceMode: IMMEDIATE|SCHEDULED, onceAt}；按 schedule_type 取相关字段';
COMMENT ON COLUMN qcm_plan.concentration_ppb IS '标气浓度 ppb；仅需要绝对浓度的类型（跨度/人工核查），零点类 NULL';
COMMENT ON COLUMN qcm_plan.flow_rate_lpm IS '标气流量 L/min；零点类 NULL';
COMMENT ON COLUMN qcm_plan.duration_overrides IS '用户覆盖的时长参数（稀疏，仅存改过项）';
COMMENT ON COLUMN qcm_plan.point_percents IS '线性/准确度的量程百分比序列（0~1 小数，如 [0,0.1,0.2,0.4,0.6,0.8]；界面以百分比呈现）';
COMMENT ON COLUMN qcm_plan.plan_start_time IS '计划有效期起（沿用原表 plan_start_time 设计，D18）；NULL=立即生效';
COMMENT ON COLUMN qcm_plan.plan_end_time IS '计划有效期止（沿用原表 plan_end_time 设计，D18）；NULL=长期有效，窗口外调度不触发';
COMMENT ON COLUMN qcm_plan.status IS '计划状态：ACTIVE / PAUSED / FINISHED';
COMMENT ON COLUMN qcm_plan.next_fire_time IS '下次触发时刻（调度器维护；PAUSED 置 NULL）';
COMMENT ON COLUMN qcm_plan.last_fire_time IS '上次触发时刻';
COMMENT ON COLUMN qcm_plan.created_by IS '创建人（ruoyi 用户名）';
COMMENT ON COLUMN qcm_plan.updated_by IS '更新人（ruoyi 用户名）';
COMMENT ON COLUMN qcm_plan.create_time IS '创建时间';
COMMENT ON COLUMN qcm_plan.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_qcm_plan_status_next_fire ON qcm_plan (status, next_fire_time);

-- ----------------------------------------------------------------------------
-- 表 2：qcm_record 执行记录表（FR-04-08 ~ FR-04-11）
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS qcm_record (
    id                   bigserial    PRIMARY KEY,
    batch_id             varchar(36)  NOT NULL,
    plan_id              int8,
    task_type            varchar(50)  NOT NULL,
    quality_control_type varchar(50)  NOT NULL,
    parameter            text         NOT NULL,
    start_time           timestamptz  NOT NULL,
    end_time             timestamptz,
    standard_value       numeric,
    monitoring_data      numeric,
    calculated_value     numeric,
    execution_status     int4         NOT NULL,
    execution_log        text,
    result_evaluation    text,
    trigger_user         varchar(100) NOT NULL,
    failure_reason       varchar(50),
    record_snapshot      jsonb,
    -- 判定标量（完成时冻结；standard_value/monitoring_data/calculated_value 为原表激活列）
    check_pass_limit     numeric,
    check_calib_limit    numeric,
    is_pass              bool,
    slope                numeric,
    intercept            numeric,
    correlation          numeric,
    -- 快照层（完成时冻结当时值，读时零关联）
    full_scale           numeric,
    instrument_name      varchar(100),
    instrument_no        varchar(50),
    gas_source           varchar(100),
    gas_no               varchar(50),
    gas_concentration    numeric,
    gas_concentration_unit varchar(20),
    sampling_start_time  timestamptz,
    sampling_end_time    timestamptz,
    -- 执行标识与 flow 关联（§4.0.1；trigger_request_id 设计意图为必填，
    --   DDL 留 nullable 是为已部署库 ALTER 增列不破坏存量行，新写入路径恒有值）
    trigger_request_id   varchar(36),
    flow_type            varchar(100),
    flow_execution_ref   varchar(100),
    created_by           varchar(50)  NOT NULL,
    updated_by           varchar(100) NOT NULL,
    create_time          timestamptz  NOT NULL DEFAULT now(),
    update_time          timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE qcm_record IS '质控执行记录表（多仪器零点一次执行 N 行：各行 parameter 独立、batch_id 同值）';
COMMENT ON COLUMN qcm_record.id IS '记录 ID';
COMMENT ON COLUMN qcm_record.batch_id IS '批次标识（UUID）；一次触发 1..N 条同值';
COMMENT ON COLUMN qcm_record.plan_id IS '关联 qcm_plan.id（原 quality_control_plan_id 改名）；计划删除不级联，靠 record_snapshot 溯源';
COMMENT ON COLUMN qcm_record.task_type IS '任务类型';
COMMENT ON COLUMN qcm_record.quality_control_type IS '质控类型（统一存 QualityControlTypeEnum.name 如 span_check；存量行 code 不迁移，无兼容负担）';
COMMENT ON COLUMN qcm_record.parameter IS '参数（仪器代码；多仪器零点各行各自仪器）';
COMMENT ON COLUMN qcm_record.start_time IS '执行开始时间';
COMMENT ON COLUMN qcm_record.end_time IS '执行结束时间';
COMMENT ON COLUMN qcm_record.standard_value IS '标准值（标气浓度；执行完成时冻结）';
COMMENT ON COLUMN qcm_record.monitoring_data IS '监测数据（仪器读数；执行完成时冻结）';
COMMENT ON COLUMN qcm_record.calculated_value IS '计算值（漂移/计算值；执行完成时冻结）';
COMMENT ON COLUMN qcm_record.check_pass_limit IS '核查通过限（判定标量，完成时冻结）';
COMMENT ON COLUMN qcm_record.check_calib_limit IS '校准通过限（判定标量，完成时冻结）';
COMMENT ON COLUMN qcm_record.is_pass IS '是否通过（判定标量，完成时冻结）';
COMMENT ON COLUMN qcm_record.slope IS '拟合斜率（多点线性，完成时冻结）';
COMMENT ON COLUMN qcm_record.intercept IS '拟合截距（多点线性，完成时冻结）';
COMMENT ON COLUMN qcm_record.correlation IS '相关系数（多点/精密度，完成时冻结）';
COMMENT ON COLUMN qcm_record.full_scale IS '满量程（完成时冻结当时值）';
COMMENT ON COLUMN qcm_record.instrument_name IS '仪器名称（完成时冻结当时值，此后设备改名不影响历史）';
COMMENT ON COLUMN qcm_record.instrument_no IS '仪器编号（完成时冻结当时值）';
COMMENT ON COLUMN qcm_record.gas_source IS '标气来源（完成时冻结当时值）';
COMMENT ON COLUMN qcm_record.gas_no IS '标气编号（完成时冻结当时值）';
COMMENT ON COLUMN qcm_record.gas_concentration IS '标气浓度（完成时冻结当时值，读 airstation 钢瓶档案 gas_concentration）';
COMMENT ON COLUMN qcm_record.gas_concentration_unit IS '标气浓度单位（完成时冻结当时值，如 ppm）';
COMMENT ON COLUMN qcm_record.sampling_start_time IS '采样窗口起（完成时冻结）';
COMMENT ON COLUMN qcm_record.sampling_end_time IS '采样窗口止（完成时冻结）';
COMMENT ON COLUMN qcm_record.trigger_request_id IS '触发请求标识（trigger 受理时生成返回调用方，逐 record 落库；SDK 受理→轮询→结果三段同一标识。设计必填，DDL nullable 仅为已部署库 ALTER 兼容）';
COMMENT ON COLUMN qcm_record.flow_type IS '执行体类型（冻结 composer ExecutorType className，如 air.monitor.calibration.span_check；qc_type 是业务枚举、flow 类名是引擎实现）';
COMMENT ON COLUMN qcm_record.flow_execution_ref IS '排障关联标识（编排器启动时生成 recordId@startMillis，传入 composer 打日志，可机器关联回 record）';
COMMENT ON COLUMN qcm_record.execution_status IS '执行状态（ExecutionStatusEnum int4 编码）';
COMMENT ON COLUMN qcm_record.execution_log IS '执行日志文本（含 phaseTimelines 阶段时间线，现状机制沿用）';
COMMENT ON COLUMN qcm_record.result_evaluation IS '结果评定';
COMMENT ON COLUMN qcm_record.trigger_user IS '触发者 displayOperator：一般形态=操作者名（用户名/system/集成名），PLATFORM 形态=name@ip[:port]；varchar(100) 为容纳平台网络端点';
COMMENT ON COLUMN qcm_record.failure_reason IS '结构化失败原因枚举（FR-02-23）；普通失败仍走 execution_log';
COMMENT ON COLUMN qcm_record.record_snapshot IS '触发时的计划配置快照（名称/类型/仪器/参数摘要）；计划删除后记录仍可溯源';
COMMENT ON COLUMN qcm_record.created_by IS '创建人';
COMMENT ON COLUMN qcm_record.updated_by IS '更新人 displayOperator：一般形态=操作者名，PLATFORM 形态=name@ip[:port]（停止/触发留痕统一口径）；varchar(100) 为容纳平台网络端点';
COMMENT ON COLUMN qcm_record.create_time IS '创建时间';
COMMENT ON COLUMN qcm_record.update_time IS '更新时间';

CREATE INDEX IF NOT EXISTS idx_qcm_record_create_time ON qcm_record (create_time DESC);
CREATE INDEX IF NOT EXISTS idx_qcm_record_batch_id ON qcm_record (batch_id);
CREATE INDEX IF NOT EXISTS idx_qcm_record_plan_id ON qcm_record (plan_id);
CREATE INDEX IF NOT EXISTS idx_qcm_record_task_type_status ON qcm_record (task_type, execution_status);
CREATE INDEX IF NOT EXISTS idx_qcm_record_trigger_request_id ON qcm_record (trigger_request_id);
CREATE INDEX IF NOT EXISTS idx_qcm_record_is_pass ON qcm_record (is_pass);

-- ----------------------------------------------------------------------------
-- 表 2a：qcm_record_phase 阶段时间线子表（§4.0 强类型结构化；机器契约读子表，execution_log 降级归档）
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS qcm_record_phase (
    id                bigserial     PRIMARY KEY,
    record_id         int8          NOT NULL,
    seq               int           NOT NULL,
    phase_code        varchar(50),
    phase_name        varchar(100),
    estimated_seconds int,
    start_time        timestamptz,
    end_time          timestamptz
);

COMMENT ON TABLE qcm_record_phase IS '质控执行阶段时间线子表（完成时一次性写入快照，读时零关联）';
COMMENT ON COLUMN qcm_record_phase.id IS '子表行 ID';
COMMENT ON COLUMN qcm_record_phase.record_id IS '关联 qcm_record.id（逻辑关联，不物理外键；记录删除由应用层同步清理）';
COMMENT ON COLUMN qcm_record_phase.seq IS '阶段序号（同 record 内有序）';
COMMENT ON COLUMN qcm_record_phase.phase_code IS '阶段代码（如 check/zero/span）';
COMMENT ON COLUMN qcm_record_phase.phase_name IS '阶段名称（人读）';
COMMENT ON COLUMN qcm_record_phase.estimated_seconds IS '预计时长秒';
COMMENT ON COLUMN qcm_record_phase.start_time IS '阶段开始时间（完成时冻结）';
COMMENT ON COLUMN qcm_record_phase.end_time IS '阶段结束时间（完成时冻结）';

CREATE INDEX IF NOT EXISTS idx_qcm_record_phase_record_id ON qcm_record_phase (record_id);

-- ----------------------------------------------------------------------------
-- 表 2b：qcm_record_key_param 关键参数子表
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS qcm_record_key_param (
    id         bigserial     PRIMARY KEY,
    record_id  int8          NOT NULL,
    seq        int           NOT NULL,
    name       varchar(100),
    value      varchar(100),
    unit       varchar(30),
    ref_range  text
);

COMMENT ON TABLE qcm_record_key_param IS '质控执行关键参数子表（工况+溯源层，完成时一次性写入快照）';
COMMENT ON COLUMN qcm_record_key_param.id IS '子表行 ID';
COMMENT ON COLUMN qcm_record_key_param.record_id IS '关联 qcm_record.id（逻辑关联，不物理外键）';
COMMENT ON COLUMN qcm_record_key_param.seq IS '参数序号（同 record 内有序）';
COMMENT ON COLUMN qcm_record_key_param.name IS '参数名';
COMMENT ON COLUMN qcm_record_key_param.value IS '参数值（字符串呈现；数值语义由 name+unit 界定）';
COMMENT ON COLUMN qcm_record_key_param.unit IS '参数单位';
COMMENT ON COLUMN qcm_record_key_param.ref_range IS '参考范围（人读）';

CREATE INDEX IF NOT EXISTS idx_qcm_record_key_param_record_id ON qcm_record_key_param (record_id);

-- ----------------------------------------------------------------------------
-- 表 2c：qcm_record_point 数据点子表（多点/精密度序列）
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS qcm_record_point (
    id           bigserial     PRIMARY KEY,
    record_id    int8          NOT NULL,
    seq          int           NOT NULL,
    std_value    numeric,
    device_value numeric
);

COMMENT ON TABLE qcm_record_point IS '质控执行数据点子表（多点校准/精密度序列，完成时一次性写入快照）';
COMMENT ON COLUMN qcm_record_point.id IS '子表行 ID';
COMMENT ON COLUMN qcm_record_point.record_id IS '关联 qcm_record.id（逻辑关联，不物理外键）';
COMMENT ON COLUMN qcm_record_point.seq IS '数据点序号（同 record 内有序）';
COMMENT ON COLUMN qcm_record_point.std_value IS '标准值（该点标气浓度）';
COMMENT ON COLUMN qcm_record_point.device_value IS '仪器读数（该点）';

CREATE INDEX IF NOT EXISTS idx_qcm_record_point_record_id ON qcm_record_point (record_id);

-- ----------------------------------------------------------------------------
-- 表 2d：标气溯源（已废止 2026-08-23 方案 A：qcm 不再自建配置表）
-- 真相源=airstation 标准气体钢瓶逻辑设备（logicdevice_station.standard_gas.{so2|nox|co}）的
-- standalone 业务属性 gas_source / cylinder_id / gas_concentration（logicdevice-airstation
-- StandardGas 元数据）；换瓶登记=写设备属性，qcm 执行完成时读一次 AttrState 冻结进
-- qcm_record.gas_source/gas_no/gas_concentration(_unit)。O3 发生器供气无钢瓶，溯源列如实 NULL。
-- 历史部署库手工清理：DROP TABLE IF EXISTS qcm_gas_info;

-- ----------------------------------------------------------------------------
-- 表 3：qcm_report 报告表（FR-04-12 ~ FR-04-14，列集沿用旧 env_quality_control_report）
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS qcm_report (
    id                   bigserial    PRIMARY KEY,
    report_name          varchar(100) NOT NULL,
    report_type          varchar(20)  NOT NULL,
    report_date          date         NOT NULL,
    instrument_name      varchar(100),
    instrument_no        varchar(50),
    gas_type             varchar(50)  NOT NULL,
    filer                varchar(50),
    reviewer             varchar(50),
    maintenance_company  varchar(100),
    report_content       text,
    report_note          text,
    is_discarded         bool         NOT NULL DEFAULT false,
    created_by           varchar(50),
    updated_by           varchar(50),
    create_time          timestamptz  NOT NULL DEFAULT now(),
    update_time          timestamptz  NOT NULL DEFAULT now(),
    gas_source           varchar(50),
    gas_no               varchar(50)
);

CREATE INDEX IF NOT EXISTS idx_qcm_report_update_time ON qcm_report (update_time DESC);

COMMENT ON TABLE qcm_report IS '质控报表表（列与语义沿用旧 env_quality_control_report，id 改 int8）';
COMMENT ON COLUMN qcm_report.id IS '报告 ID';
COMMENT ON COLUMN qcm_report.report_name IS '报表名称，如"xxx仪器运行状况检查记录表"、"xxx仪器多点校准记录表"、"xxx仪器精密度审核记录表"、"xxx仪器准确度审核记录表"等';
COMMENT ON COLUMN qcm_report.report_type IS '报表类型，如"zero_span"、"multi_check"、"precision_check"、"accuracy_check"等';
COMMENT ON COLUMN qcm_report.report_date IS '报表生成日期';
COMMENT ON COLUMN qcm_report.instrument_name IS '仪器名称，如"赛默飞世尔"';
COMMENT ON COLUMN qcm_report.instrument_no IS '仪器编号，如"CMNO001"';
COMMENT ON COLUMN qcm_report.gas_type IS '标气类型，如"SO2"、"NOx"、"O3"、"CO"等';
COMMENT ON COLUMN qcm_report.filer IS '填表人姓名';
COMMENT ON COLUMN qcm_report.reviewer IS '审核人姓名';
COMMENT ON COLUMN qcm_report.maintenance_company IS '运维公司，负责仪器维护的公司名称';
COMMENT ON COLUMN qcm_report.report_content IS '报表详细内容，包含校准、审核、测试等所有数据（TEXT 类型）';
COMMENT ON COLUMN qcm_report.report_note IS '报表备注信息，用于记录额外说明';
COMMENT ON COLUMN qcm_report.is_discarded IS '是否废弃，true 表示报表已废弃，false 表示正常';
COMMENT ON COLUMN qcm_report.created_by IS '创建人 ID 或姓名';
COMMENT ON COLUMN qcm_report.updated_by IS '更新人 ID 或姓名';
COMMENT ON COLUMN qcm_report.create_time IS '记录创建时间';
COMMENT ON COLUMN qcm_report.update_time IS '记录更新时间';
COMMENT ON COLUMN qcm_report.gas_source IS '标气来源';
COMMENT ON COLUMN qcm_report.gas_no IS '标气编号';

-- ----------------------------------------------------------------------------
-- 18 条等价计划 seed（FR-06-07，对应旧 sys_job 21-41；4 条零点 job 合并为 #1 多仪器零点）
-- 全部 PAUSED / next_fire_time NULL / 每日 00:00 / created_by=updated_by='system'；
-- duration_overrides / point_percents 留 NULL = 全默认（Task 2.6 后补，FR-06-08 OPEN-09）。
-- qc_type 对齐 QualityControlTypeEnum.name（snake_case）；multi_zero_check 为 Phase 4 新类型，
--   新类型接线前若被启用触发 → 产生 EXECUTOR_TYPE_NOT_READY 失败记录，不阻塞其他计划（FR-06-11）。
-- ----------------------------------------------------------------------------

INSERT INTO qcm_plan (plan_name, qc_type, instruments, schedule_type, schedule_config,
                      concentration_ppb, flow_rate_lpm, status, next_fire_time, last_fire_time,
                      created_by, updated_by)
SELECT '零点质控-全部仪器', 'multi_zero_check', '["SO2","NO2","CO","O3"]'::jsonb, 'DAILY', '{"hour":0,"minute":0}'::jsonb,
       NULL, NULL, 'PAUSED', NULL, NULL, 'system', 'system'
WHERE NOT EXISTS (SELECT 1 FROM qcm_plan WHERE plan_name = '零点质控-全部仪器');

-- 跨度核查 ×4（CO 原 job 40ppm 换算 40000ppb）
INSERT INTO qcm_plan (plan_name, qc_type, instruments, schedule_type, schedule_config,
                      concentration_ppb, flow_rate_lpm, status, next_fire_time, last_fire_time,
                      created_by, updated_by)
SELECT v.plan_name, 'span_check', v.instruments::jsonb, 'DAILY', '{"hour":0,"minute":0}'::jsonb,
       v.conc, 4.0, 'PAUSED', NULL, NULL, 'system', 'system'
FROM (VALUES
    ('跨度核查-SO2', '["SO2"]', 400::numeric),
    ('跨度核查-NO2', '["NO2"]', 400::numeric),
    ('跨度核查-CO',  '["CO"]',  40000::numeric),
    ('跨度核查-O3',  '["O3"]',  400::numeric)
) AS v(plan_name, instruments, conc)
WHERE NOT EXISTS (SELECT 1 FROM qcm_plan p WHERE p.plan_name = v.plan_name);

-- 线性核查 ×4（百分比序列走默认，point_percents 留 NULL）
INSERT INTO qcm_plan (plan_name, qc_type, instruments, schedule_type, schedule_config,
                      concentration_ppb, flow_rate_lpm, status, next_fire_time, last_fire_time,
                      created_by, updated_by)
SELECT '线性核查-' || v.gas, 'multi_check', jsonb_build_array(v.gas), 'DAILY', '{"hour":0,"minute":0}'::jsonb,
       NULL, 4.0, 'PAUSED', NULL, NULL, 'system', 'system'
FROM (VALUES ('SO2'), ('NO2'), ('CO'), ('O3')) AS v(gas)
WHERE NOT EXISTS (SELECT 1 FROM qcm_plan p WHERE p.plan_name = '线性核查-' || v.gas);

-- 精密度核查 ×4
INSERT INTO qcm_plan (plan_name, qc_type, instruments, schedule_type, schedule_config,
                      concentration_ppb, flow_rate_lpm, status, next_fire_time, last_fire_time,
                      created_by, updated_by)
SELECT '精密度核查-' || v.gas, 'precision_check', jsonb_build_array(v.gas), 'DAILY', '{"hour":0,"minute":0}'::jsonb,
       NULL, 4.0, 'PAUSED', NULL, NULL, 'system', 'system'
FROM (VALUES ('SO2'), ('NO2'), ('CO'), ('O3')) AS v(gas)
WHERE NOT EXISTS (SELECT 1 FROM qcm_plan p WHERE p.plan_name = '精密度核查-' || v.gas);

-- 准确度核查 ×4（百分比序列走默认，point_percents 留 NULL）
INSERT INTO qcm_plan (plan_name, qc_type, instruments, schedule_type, schedule_config,
                      concentration_ppb, flow_rate_lpm, status, next_fire_time, last_fire_time,
                      created_by, updated_by)
SELECT '准确度核查-' || v.gas, 'accuracy_check', jsonb_build_array(v.gas), 'DAILY', '{"hour":0,"minute":0}'::jsonb,
       NULL, 4.0, 'PAUSED', NULL, NULL, 'system', 'system'
FROM (VALUES ('SO2'), ('NO2'), ('CO'), ('O3')) AS v(gas)
WHERE NOT EXISTS (SELECT 1 FROM qcm_plan p WHERE p.plan_name = '准确度核查-' || v.gas);

-- 转换效率检查-NOx
INSERT INTO qcm_plan (plan_name, qc_type, instruments, schedule_type, schedule_config,
                      concentration_ppb, flow_rate_lpm, status, next_fire_time, last_fire_time,
                      created_by, updated_by)
SELECT '转换效率检查-NOx', 'conversion_check', '["NO2"]'::jsonb, 'DAILY', '{"hour":0,"minute":0}'::jsonb,
       NULL, 4.0, 'PAUSED', NULL, NULL, 'system', 'system'
WHERE NOT EXISTS (SELECT 1 FROM qcm_plan WHERE plan_name = '转换效率检查-NOx');
