-- qcm 域 V4.0.1 存量清理（旧 quartz 通道质控任务组退场 + 强类型换代补列 + 停止留痕列宽扩展）。
-- 语义双态：
--   存量库路径：经 baseline=4.0.0 接管后本脚本随即补刀执行——这是「存量库缺列/旧通道遗留」的修复通道；
--   空库路径：DELETE/DROP 目标不存在或由 V4.0.0 系统种子先种入后于此处清除（净终态与存量路径一致）；
--     ALTER 段 ADD COLUMN IF NOT EXISTS / ALTER COLUMN TYPE 可重跑，新表已含列时幂等吸收。
-- sys_job 21~41 为旧 quartz 质控通道遗留任务（换代新通道 = qcm_plan 计划表 + 18 条计划种子）；
--   id 15（质控报告生成）与 42/43/44/45（人工核查 NO2/CO/O3/SO2，ManualCheck 同族）按需求保留不动（44/45 为 2026-10-04 lead 裁定补记）。
-- 来源拆分：sql/qcm_data.sql 头部存量处置块原文转正（剥注释壳，SQL 未改动）。

-- 1) 删除旧质控任务组 sys_job 21-41（id 15/42~45 不动，FR-06-04）：
DELETE FROM sys_job WHERE job_id BETWEEN 21 AND 41;
-- 2) DROP 三张旧表（qcm 无生产数据，用户确认 D13，不迁移）：
DROP TABLE IF EXISTS env_quality_control_plan;
DROP TABLE IF EXISTS env_quality_control_records;
DROP TABLE IF EXISTS env_quality_control_report;
-- 3) §4.0 强类型换代的已部署库增列（幂等可重跑；ALTER 不破坏存量行，
--    trigger_request_id 因此留 nullable，新写入路径恒有值）：
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS check_pass_limit numeric;
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS check_calib_limit numeric;
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS is_pass bool;
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS slope numeric;
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS intercept numeric;
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS correlation numeric;
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS full_scale numeric;
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS instrument_name varchar(100);
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS instrument_no varchar(50);
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS gas_source varchar(100);
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS gas_no varchar(50);
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS gas_concentration numeric;
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS gas_concentration_unit varchar(20);
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS sampling_start_time timestamptz;
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS sampling_end_time timestamptz;
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS trigger_request_id varchar(36);
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS flow_type varchar(100);
ALTER TABLE qcm_record ADD COLUMN IF NOT EXISTS flow_execution_ref varchar(100);
-- 4) SDK 停止执行留痕的列宽扩展（幂等可重跑；varchar 扩宽不破坏存量行）：
--    触发与停止都写 displayOperator，PLATFORM 形态 name@ip[:port]（含 IPv6）可超 50。
ALTER TABLE qcm_record ALTER COLUMN trigger_user TYPE varchar(100);
ALTER TABLE qcm_record ALTER COLUMN updated_by   TYPE varchar(100);
-- 5) 同日优先级与校准策略增列（幂等可重跑；nullable 缺省语义由 NULL 承载，
--    存量行不补值即取缺省 STANDARD / NONE）：
ALTER TABLE qcm_plan ADD COLUMN IF NOT EXISTS calibration_policy varchar(30);
ALTER TABLE qcm_plan ADD COLUMN IF NOT EXISTS same_day_priority varchar(10);
