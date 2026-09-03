<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="触发来源" prop="taskType">
        <el-select class="custom-select" v-model="queryParams.taskType" placeholder="请选择触发来源" clearable>
          <el-option
            v-for="dict in quality_control_task_type"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="质控类型" prop="qualityControlType" width="30" >
        <el-select class="custom-select" v-model="queryParams.qualityControlType" placeholder="请选择质控类型" clearable>
          <el-option
            v-for="dict in quality_control_type"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="质控参数" prop="parameter">
        <el-select class="custom-select" v-model="queryParams.parameter" placeholder="请选择质控参数" clearable>
          <el-option
            v-for="dict in quality_control_param"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="执行状态" prop="executionStatus">
        <el-select class="custom-select" v-model="queryParams.executionStatus" placeholder="请选择执行状态" clearable>
          <el-option
            v-for="dict in quality_control_execution_status"
            :key="dict.value"
            :label="dict.label"
            :value="dict.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="开始时间">
        <!-- 天粒度 daterange：提交时补 00:00:00/23:59:59 走 params 通道（后端按 start_time between 含边界过滤） -->
        <el-date-picker
          v-model="dateRange"
          value-format="YYYY-MM-DD"
          type="daterange"
          range-separator="-"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          clearable
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button
          type="warning"
          plain
          icon="Download"
          @click="handleExport"
          v-hasPermi="['quality_control:records:export']"
        >导出</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8" v-if="false">
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="Plus"
          @click="handleAdd"
          v-hasPermi="['quality_control:records:add']"
        >新增</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="success"
          plain
          icon="Edit"
          :disabled="single"
          @click="handleUpdate"
          v-hasPermi="['quality_control:records:edit']"
        >修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="danger"
          plain
          icon="Delete"
          :disabled="multiple"
          @click="handleDelete"
          v-hasPermi="['quality_control:records:remove']"
        >删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="Download"
          @click="handleExport"
          v-hasPermi="['quality_control:records:export']"
        >导出</el-button>
      </el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="recordsList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="30" align="center" />
      <el-table-column label="记录ID" align="center" prop="id" v-if="false" />
      <el-table-column label="触发来源" align="center" prop="taskType" width="130">
        <template #default="scope">
          <div class="qc-trigger-cell">
            <dict-tag :options="quality_control_task_type" :value="scope.row.taskType"/>
            <div v-if="triggerDetailText(scope.row)" class="qc-trigger-cell__detail">{{ triggerDetailText(scope.row) }}</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="质控类型" align="center" prop="qualityControlType">
        <template #default="scope">
          <dict-tag :options="quality_control_type" :value="scope.row.qualityControlType"/>
        </template>
      </el-table-column>
      <el-table-column label="质控参数" align="center" prop="parameter">
        <template #default="scope">
          <dict-tag :options="quality_control_param" :value="scope.row.parameter ? scope.row.parameter.split(',') : []"/>
        </template>
      </el-table-column>
      <el-table-column label="开始时间" align="center" prop="startTime" />
      <el-table-column label="结束时间" align="center" prop="endTime" />
      <el-table-column label="标准值" align="center" prop="standardValue" v-if="false"/>
      <el-table-column label="监测数据" align="center" prop="monitoringData" v-if="false"/>
      <el-table-column label="计算值" align="center" prop="calculatedValue" v-if="false"/>
      <el-table-column label="执行状态" align="center" prop="executionStatus">
        <template #default="scope">
          <dict-tag :options="quality_control_execution_status" :value="scope.row.executionStatus"/>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" min-width="210" class-name="small-padding fixed-width qc-record-op-col">
        <template #default="scope">
          <div class="qc-record-op-actions">
            <div
              class="log-preview log-preview--compact"
              :class="opPhaseDetailStatusClass(scope.row)"
              @click.stop="showExecutionLogDetail(scope.row)"
            >
              <el-icon class="log-preview__icon"><Operation /></el-icon>
              <span>阶段详情</span>
            </div>
            <template v-if="canShowAbortInOpColumn(scope.row)">
              <div
                v-if="canStopQualityControl(scope.row)"
                class="log-preview log-preview--compact log-preview--stop"
                :class="getExecutionLogStatusClass('1')"
                @click.stop="handleStop(scope.row)"
              >
                <el-icon class="log-preview__icon stop-qc-btn__icon"><CircleCloseFilled /></el-icon>
                <span>中止质控</span>
              </div>
              <div
                v-else
                class="log-preview log-preview--compact log-preview--ghost-slot"
                aria-hidden="true"
              ></div>
            </template>
            <div
              v-else-if="canShowQcResultPreview(scope.row)"
              v-hasPermi="['quality_control:records:query']"
              class="log-preview log-preview--compact"
              :class="getExecutionLogStatusClass(scope.row.executionStatus)"
              @click.stop="handleQcResultPreview(scope.row)"
            >
              <el-icon class="log-preview__icon"><Reading /></el-icon>
              <span>质控结果</span>
            </div>
            <div
              v-else-if="isQcManualAbortEndRow(scope.row)"
              class="log-preview log-preview--compact log-preview--qc-result-ph-manual"
              role="presentation"
            >
              <el-icon class="log-preview__icon"><Reading /></el-icon>
              <span>质控结果</span>
            </div>
            <div
              v-else-if="showQcResultGhostPlaceholder(scope.row)"
              class="log-preview log-preview--compact log-preview--ghost-slot"
              aria-hidden="true"
            ></div>
            <div
              v-else
              class="log-preview log-preview--compact log-preview--ghost-slot"
              aria-hidden="true"
            ></div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="质控结论" align="center" width="128">
        <template #default="scope">
          <el-tag
            :type="resolveQcVerdict(scope.row).elType"
            effect="dark"
            class="qc-verdict-tag"
            :class="{ 'qc-verdict-tag--manual-abort': isQcManualAbortEndRow(scope.row) }"
          >
            {{ resolveQcVerdict(scope.row).text }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>

    <pagination
      v-show="total>0"
      :total="total"
      v-model:page="queryParams.pageNum"
      v-model:limit="queryParams.pageSize"
      @pagination="getList"
    />

    <ExecutionLogDetailDialog ref="executionLogDetailRef" @row-updated="syncRowFromDetail" />

    <QcResultPreviewDialog ref="qcResultPreviewRef" />

    <!-- 添加或修改质控记录对话框 -->
    <el-dialog :title="title" v-model="open" width="500px" append-to-body>
      <el-form ref="recordsRef" class="left-aligned-form"
               :model="form"
               :rules="rules" label-width="160px">
        <el-form-item label="任务类型" prop="taskType">
          <el-select v-model="form.taskType" placeholder="请选择任务类型">
            <el-option
              v-for="dict in quality_control_task_type"
              :key="dict.value"
              :label="dict.label"
              :value="dict.value"
            ></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="质控类型" prop="qualityControlType">
          <el-select v-model="form.qualityControlType" placeholder="请选择质控类型">
            <el-option
              v-for="dict in quality_control_type"
              :key="dict.value"
              :label="dict.label"
              :value="dict.value"
            ></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="质控参数" prop="parameter">
          <el-select v-model="form.parameter" placeholder="请选择质控参数">
            <el-option
              v-for="dict in quality_control_param"
              :key="dict.value"
              :label="dict.label"
              :value="dict.value"
            ></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间" prop="startTime">
          <el-date-picker clearable
                          v-model="form.startTime"
                          type="datetime"
                          value-format="YYYY-MM-DD HH:mm:ss"
                          placeholder="质控任务开始时间">
          </el-date-picker>
        </el-form-item>
        <el-form-item label="结束时间" prop="endTime">
          <el-date-picker clearable
                          v-model="form.endTime"
                          type="datetime"
                          value-format="YYYY-MM-DD HH:mm:ss"
                          placeholder="质控任务结束时间">
          </el-date-picker>
        </el-form-item>
        <el-form-item label="标准值" prop="standardValue">
          <el-input v-model="form.standardValue" placeholder="请输入标准值" />
        </el-form-item>
        <el-form-item label="监测数据" prop="monitoringData">
          <el-input v-model="form.monitoringData" placeholder="请输入监测数据" />
        </el-form-item>
        <el-form-item label="计算值" prop="calculatedValue">
          <el-input v-model="form.calculatedValue" placeholder="请输入计算值" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" @click="submitForm">确 定</el-button>
          <el-button @click="cancel">取 消</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style>
.custom-select {
  width: 200px; /* 固定宽度 */
  min-width: 100px; /* 最小宽度 */
  max-width: 400px; /* 最大宽度 */
}

/* 触发来源列：第二行明细（操作人/来源系统），小号弱化 */
.qc-trigger-cell__detail {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.4;
  color: #909399;
  word-break: break-all;
}

.execution-log-card {
  position: relative !important;
  cursor: pointer;
  overflow: visible !important;
}

.qc-record-op-col .cell {
  padding-left: 8px;
  padding-right: 8px;
}

.qc-record-op-actions {
  display: flex;
  flex-direction: row;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
}

.qc-record-op-actions .log-preview--qc-result-ph-manual {
  cursor: not-allowed;
  opacity: 0.55;
  pointer-events: none;
  color: #909399 !important;
  border-color: #dcdfe6 !important;
  background: #f5f7fa !important;
  text-decoration: none !important;
}

.qc-record-op-actions .log-preview--qc-result-ph-manual:hover {
  text-decoration: none !important;
}

.qc-record-op-actions .log-preview--qc-result-ph {
  cursor: not-allowed;
  opacity: 0.45;
  pointer-events: none;
  color: #909399 !important;
  border-color: #e4e7ed !important;
  background: #f5f7fa !important;
  text-decoration: none !important;
}

.qc-record-op-actions .log-preview--qc-result-ph:hover {
  text-decoration: none !important;
}

.qc-record-op-actions .log-preview--ghost-slot {
  min-width: 88px;
  max-width: 112px;
  min-height: 26px;
  padding: 3px 8px;
  border: 1px dashed transparent !important;
  background: transparent !important;
  cursor: default;
  pointer-events: none;
  box-sizing: border-box;
}

/* 操作列：紧凑胶囊 */
.qc-record-op-actions .log-preview--compact {
  width: auto;
  max-width: 112px;
  gap: 3px;
  padding: 3px 8px;
  border-radius: 4px;
  font-size: 11px;
  line-height: 1.2;
}

.qc-record-op-actions .log-preview {
  flex: 0 1 auto;
  white-space: nowrap;
}

.qc-record-op-actions .log-preview__icon {
  font-size: 14px;
}

.log-preview.log-preview--stop {
  background: #fef0f0 !important;
  border-color: #f89898 !important;
  color: #f56c6c !important;
}

.log-preview.log-preview--stop:hover {
  background: #fde2e2 !important;
  border-color: #f56c6c !important;
  color: #dd6161 !important;
}

.log-preview {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 6px;
  border: 1px solid #e4e7ed;
  transition: all 0.3s ease;
  cursor: pointer;
  color: #409eff;
}

.log-preview:hover {
  background: #ecf5ff;
  border-color: #409eff;
  color: #66b1ff;
  text-decoration: underline;
}

/* 执行状态对应的颜色样式 - 提高优先级 */
.log-preview.status-waiting {
  background: #fdf6ec !important;
  border-color: #e6a23c !important;
  color: #e6a23c !important;
}

.log-preview.status-waiting:hover {
  background: #fdf6ec !important;
  border-color: #e6a23c !important;
  color: #cf9236 !important;
}

.log-preview.status-running {
  background: #f0f9ff !important;
  border-color: #409eff !important;
  color: #409eff !important;
}

.log-preview.status-running:hover {
  background: #ecf5ff !important;
  border-color: #409eff !important;
  color: #66b1ff !important;
}

.log-preview.status-success {
  background: #f0f9eb !important;
  border-color: #67c23a !important;
  color: #67c23a !important;
}

.log-preview.status-success:hover {
  background: #f0f9eb !important;
  border-color: #67c23a !important;
  color: #5daf34 !important;
}

.log-preview.status-failed {
  background: #fef0f0 !important;
  border-color: #f56c6c !important;
  color: #f56c6c !important;
}

.log-preview.status-failed:hover {
  background: #fef0f0 !important;
  border-color: #f56c6c !important;
  color: #f78989 !important;
}

.log-preview.status-default {
  background: #f5f7fa !important;
  border-color: #e4e7ed !important;
  color: #909399 !important;
}

.log-preview.status-default:hover {
  background: #f5f7fa !important;
  border-color: #e4e7ed !important;
  color: #606266 !important;
}

.log-preview.status-aborted {
  background: #fdf6ec !important;
  border-color: #e6a23c !important;
  color: #b88230 !important;
}

.log-preview.status-aborted:hover {
  background: #faecd8 !important;
  border-color: #d9a441 !important;
  color: #a76a15 !important;
}



.stop-qc-btn {
  display: inline-flex;
  align-items: center;
  padding: 6px 12px;
  border-radius: 6px;
  font-weight: 500;
}

.stop-qc-btn__icon {
  margin-right: 4px;
  font-size: 14px;
}

.qc-verdict-tag {
  font-weight: 600;
}

.qc-verdict-tag.qc-verdict-tag--manual-abort {
  --el-tag-text-color: #ffffff !important;
  --el-tag-bg-color: #e6a23c !important;
  --el-tag-border-color: #cf9236 !important;
}

</style>

<script setup name="Records">
import { listRecords, delRecords, addRecords, updateRecords, stopRecords } from "@/api/quality_control/records";
import { Operation, Reading, CircleCloseFilled } from '@element-plus/icons-vue';
import { getCurrentInstance, onMounted, nextTick, watch, ref, reactive, toRefs } from 'vue';
import ExecutionLogDetailDialog from './components/ExecutionLogDetailDialog.vue';
import QcResultPreviewDialog from './components/QcResultPreviewDialog.vue';
import {
  getExecutionLogStatusClass,
  canStopQualityControl,
  showQcResultGhostPlaceholder,
  canShowAbortInOpColumn,
  opPhaseDetailStatusClass,
  isQcManualAbortEndRow,
  canShowQcResultPreview,
  displayRecordEndTime
} from './recordRowStatus';

const { proxy } = getCurrentInstance();
const { quality_control_param, quality_control_task_type, quality_control_execution_status, quality_control_type } = proxy.useDict('quality_control_param', 'quality_control_task_type', 'quality_control_execution_status', 'quality_control_type');

// 监听字典数据变化
watch([quality_control_task_type, quality_control_type, quality_control_execution_status], () => {
  // 字典数据变化时的处理逻辑
}, { immediate: true });

const recordsList = ref([]);
const open = ref(false);
const loading = ref(true);
const showSearch = ref(true);
const ids = ref([]);
const single = ref(true);
const multiple = ref(true);
const total = ref(0);
const title = ref("");
const executionLogDetailRef = ref(null);
const qcResultPreviewRef = ref(null);
// 「开始时间」daterange（天粒度串）；独立于 queryParams，避免分页参数混入日期数组
const dateRange = ref([]);

const data = reactive({
  form: {},
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    taskType: null,
    qualityControlType: null,
    parameter: null,
    executionStatus: null,
  },
  rules: {
    taskType: [
      { required: true, message: "任务类型不能为空", trigger: "change" }
    ],
    qualityControlType: [
      { required: true, message: "质控类型不能为空", trigger: "change" }
    ],
    parameter: [
      { required: true, message: "质控参数不能为空", trigger: "blur" }
    ],
    executionStatus: [
      { required: true, message: "执行状态不能为空", trigger: "change" }
    ],
    createdBy: [
      { required: true, message: "创建人不能为空", trigger: "blur" }
    ],
    updatedBy: [
      { required: true, message: "更新人不能为空", trigger: "blur" }
    ],
    createTime: [
      { required: true, message: "创建时间不能为空", trigger: "blur" }
    ],
    updateTime: [
      { required: true, message: "更新时间不能为空", trigger: "blur" }
    ]
  }
});

const { queryParams, form, rules } = toRefs(data);

function resolveQcVerdict(row) {
  if (isQcManualAbortEndRow(row)) {
    return { text: '手动中止', elType: 'warning' };
  }
  const st = String(row.executionStatus);
  if (st === '3') {
    return { text: '执行失败', elType: 'danger' };
  }
  if (st !== '2') {
    return { text: '—', elType: 'info' };
  }
  if (String(row.qualityControlType) === '6') {
    return { text: '—', elType: 'info' };
  }
  const fromText = (t) => {
    const s = String(t || '');
    if (!s.trim()) {
      return null;
    }
    if ((/无需校准|不需校准|已通过|合格|正常/.test(s)) && !(/不合格|未通过|失败/.test(s))) {
      return { text: '合格', elType: 'success' };
    }
    if (/不合格|未通过|失败/.test(s)) {
      return { text: '不合格', elType: 'danger' };
    }
    return null;
  };
  let parsed = {};
  try {
    parsed = row.executionLog ? JSON.parse(row.executionLog) : {};
  } catch (e) {
    return fromText(row.executionLog) || fromText(row.resultEvaluation) || { text: '—', elType: 'info' };
  }
  const sm = parsed.statusMap || {};
  const pass = sm.isPass === true || sm.isPass === 'true';
  const msg = String(sm.resultMessage || '');
  if (pass) {
    if (/自动校准|校准完成/.test(msg)) {
      return { text: '校准后合格', elType: 'success' };
    }
    return { text: '合格', elType: 'success' };
  }
  if ((/无需校准|不需校准|已通过|合格/.test(msg)) && !/不合格|未通过/.test(msg)) {
    return { text: '合格', elType: 'success' };
  }
  const fallback = fromText(msg) || fromText(row.resultEvaluation);
  if (fallback) {
    return fallback;
  }
  return { text: '不合格', elType: 'danger' };
}

/** 打开执行记录详情弹窗（弹窗内部负责轮询与格式化） */
function showExecutionLogDetail(row) {
  executionLogDetailRef.value?.open(row);
}

/** 详情弹窗轮询拉到最新行数据：同步列表对应行，保持列表与弹窗一致 */
function syncRowFromDetail(id, detail) {
  const idx = recordsList.value.findIndex((r) => r.id === id);
  if (idx >= 0) {
    Object.assign(recordsList.value[idx], detail);
  }
}

/** 打开质控结果预览弹窗（七类报告组件在子组件内解析） */
function handleQcResultPreview(row) {
  qcResultPreviewRef.value?.open(row);
}

/**
 * 触发来源第二行明细：手动/现场=操作人，远程=来源系统名；
 * 计划触发（0）调度用户恒为 system，是噪音不显示。
 */
function triggerDetailText(row) {
  const t = String(row.taskType ?? '');
  const user = String(row.triggerUser || '').trim();
  if (!user) {
    return '';
  }
  if (t === '1' || t === '2') {
    return user;
  }
  if (t === '3') {
    return `来源：${user}`;
  }
  return '';
}

/**
 * 组装查询参数：开始时间 daterange 补全时刻后经 params 通道下发
 * （后端 setParams 识别 beginStartTime/endStartTime，对 start_time between 含边界）。
 */
function buildQueryParams() {
  const params = { ...queryParams.value };
  if (Array.isArray(dateRange.value) && dateRange.value.length === 2) {
    params.params = {
      beginStartTime: `${dateRange.value[0]} 00:00:00`,
      endStartTime: `${dateRange.value[1]} 23:59:59`
    };
  }
  return params;
}

/** 查询质控记录列表 */
function getList() {
  loading.value = true;
  listRecords(buildQueryParams()).then(response => {
    recordsList.value = response.rows;
    total.value = response.total;
    loading.value = false;
  });
}

// 取消按钮
function cancel() {
  open.value = false;
  reset();
}

// 表单重置
function reset() {
  form.value = {
    id: null,
    taskType: null,
    qualityControlType: null,
    parameter: null,
    startTime: null,
    endTime: null,
    standardValue: null,
    monitoringData: null,
    calculatedValue: null,
    executionStatus: null,
    executionLog: null,
    resultEvaluation: null,
    qualityControlPlanId: null,
    createdBy: null,
    updatedBy: null,
    createTime: null,
    updateTime: null
  };
  proxy.resetForm("recordsRef");
}

/** 搜索按钮操作 */
function handleQuery() {
  queryParams.value.pageNum = 1;
  getList();
}

/** 重置按钮操作（daterange 不在 form model 内，手动清空） */
function resetQuery() {
  dateRange.value = [];
  proxy.resetForm("queryRef");
  handleQuery();
}

// 多选框选中数据
function handleSelectionChange(selection) {
  ids.value = selection.map(item => item.id);
  single.value = selection.length != 1;
  multiple.value = !selection.length;
}

/** 新增按钮操作 */
function handleAdd() {
  reset();
  open.value = true;
  title.value = "添加质控记录";
}

/** 中止按钮操作 */
function handleStop(row) {
  reset();
  const _id = row.id || ids.value
  // 提示
  proxy.$modal.confirm('是否确认中止该质控任务？').then(function () {
    stopRecords({id: _id}).then(response => {
      title.value = "中止质控记录";
      if (response.code === 200) {
        proxy.$modal.msgSuccess("中止成功");
      } else {
        proxy.$modal.msgError(response.msg);
      }
      getList();
    });
  })
}

/** 提交按钮 */
function submitForm() {
  proxy.$refs["recordsRef"].validate(valid => {
    if (valid) {
      if (form.value.id != null) {
        updateRecords(form.value).then(response => {
          proxy.$modal.msgSuccess("修改成功");
          open.value = false;
          getList();
        });
      } else {
        addRecords(form.value).then(response => {
          proxy.$modal.msgSuccess("新增成功");
          open.value = false;
          getList();
        });
      }
    }
  });
}

/** 删除按钮操作 */
function handleDelete(row) {
  const _ids = row.id || ids.value;
  proxy.$modal.confirm('是否确认删除质控记录编号为"' + _ids + '"的数据项？').then(function () {
    return delRecords(_ids);
  }).then(() => {
    getList();
    proxy.$modal.msgSuccess("删除成功");
  }).catch(() => {
  });
}

/** 导出按钮操作（与列表同筛选条件，含开始时间 params 通道） */
function handleExport() {
  proxy.download('quality_control/records/export', buildQueryParams(), `records_${new Date().getTime()}.xlsx`)
}

onMounted(() => {
  // 等待下一个tick确保字典数据加载完成
  nextTick(() => {
    getList();
  });
});
</script>