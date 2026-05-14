<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="任务类型" prop="taskType">
        <el-select class="custom-select" v-model="queryParams.taskType" placeholder="请选择任务类型" clearable>
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
      <el-table-column label="任务类型" align="center" prop="taskType">
        <template #default="scope">
          <dict-tag :options="quality_control_task_type" :value="scope.row.taskType"/>
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
      <el-table-column label="执行记录" align="center" prop="executionLog">
        <template #default="scope">
          <div class="execution-log-card" @click="showExecutionLogDetail(scope.row)">
            <div class="log-preview" :class="getExecutionLogStatusClass(scope.row.executionStatus)">
              <el-icon><Document /></el-icon>
              <span>查看详情</span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="执行状态" align="center" prop="executionStatus">
        <template #default="scope">
          <dict-tag :options="quality_control_execution_status" :value="scope.row.executionStatus"/>
        </template>
      </el-table-column>
      <el-table-column label="结果评价" align="center" prop="resultEvaluation" />
      <el-table-column label="操作" align="center" min-width="168" class-name="small-padding fixed-width">
        <template #default="scope">
          <el-button
            v-if="scope.row.executionStatus == 2"
            type="primary"
            link
            size="small"
            v-hasPermi="['quality_control:records:query']"
            @click.stop="handleQcResultPreview(scope.row)"
          >质控结果</el-button>
          <el-button
            v-if="scope.row.executionStatus !== 2 && scope.row.executionStatus !== 3 && scope.row.executionStatus !== 4"
            type="danger"
            size="small"
            plain
            class="stop-qc-btn"
            @click.stop="handleStop(scope.row)"
          >
            <el-icon class="stop-qc-btn__icon"><CircleCloseFilled /></el-icon>
            <span>中止质控</span>
          </el-button>
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

    <!-- 执行记录详情对话框 -->
    <el-dialog title="执行记录详情" v-model="executionLogDialogVisible" width="800px" append-to-body>
      <div class="execution-log-dialog-content">
        <div style="background: #f0f9ff; padding: 8px; margin-bottom: 15px; border-radius: 4px; font-size: 12px; color: #666;">
          <strong>数据信息:</strong> 共 {{ Object.keys(parsedExecutionLog).length }} 个字段 |
          <strong>任务参数:</strong> {{ Object.keys(displayExecutionParams).length ? '有' : '无' }} |
          <strong>执行结果:</strong> {{ parsedExecutionLog.result ? (Array.isArray(parsedExecutionLog.result) ? parsedExecutionLog.result.length + '条记录' : '有') : '无' }}
        </div>



        <div v-if="Object.keys(displayExecutionParams).length" class="params-section">
          <h5>任务参数</h5>
          <el-table :data="[displayExecutionParams]" border stripe size="small" class="params-table">
            <el-table-column
              v-for="(value, key) in displayExecutionParams"
              :key="key"
              :prop="key"
              :label="getParamDisplayName(key)"
              align="center"
              min-width="120">
              <template #default="scope">
                <span>{{ formatParamValue(scope.row[key], key) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="parsedExecutionLog.result" class="result-section">
          <h5>执行结果</h5>
          <div v-if="Array.isArray(parsedExecutionLog.result)" class="result-array">
            <el-table :data="parsedExecutionLog.result" border stripe size="small" class="result-table">
              <el-table-column
                v-for="(value, key) in parsedExecutionLog.result[0]"
                :key="key"
                :prop="key"
                :label="getResultDisplayName(key)"
                align="center"
                min-width="120">
                <template #default="scope">
                  <span>{{ formatResultValue(scope.row[key], key) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <div v-else class="result-object">
            <el-table :data="[parsedExecutionLog.result]" border stripe size="small" class="result-table">
              <el-table-column
                v-for="(value, key) in parsedExecutionLog.result"
                :key="key"
                :prop="key"
                :label="getResultDisplayName(key)"
                align="center"
                min-width="120">
                <template #default="scope">
                  <span>{{ formatResultValue(scope.row[key], key) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>

        <!-- 如果没有params和result，直接显示所有数据 -->
        <div v-if="!Object.keys(displayExecutionParams).length && !parsedExecutionLog.result && Object.keys(parsedExecutionLog).length > 0" class="all-data-section">
          <h5>执行记录数据</h5>
          <div class="data-grid">
            <div v-for="(value, key) in parsedExecutionLog" :key="key" class="data-item">
              <span class="data-label">{{ getResultDisplayName(key) }}:</span>
              <span class="data-value">{{ formatResultValue(value, key) }}</span>
            </div>
          </div>
        </div>

        <div v-if="Object.keys(parsedExecutionLog).length === 0" class="no-data">
          <p>暂无详细记录数据</p>
        </div>

        <div class="params-section phase-section">
          <h5>执行阶段</h5>
          <!-- 使用Element Plus时间线组件替代表格 -->
          <el-timeline class="custom-timeline">
            <!-- 开始阶段 -->
            <el-timeline-item
              :timestamp="hoveredRow.startTime ? hoveredRow.startTime : '开始时间未知'"
              type="primary"
              :color="getPhaseStatusColor(0)"
              size="large"
            >
              <template #icon>
                <el-icon><VideoPlay /></el-icon>
              </template>
              <div class="timeline-content">
                <h4>开始</h4>

              </div>
            </el-timeline-item>

            <!-- 执行阶段 -->
            <el-timeline-item
              v-for="(phase, index) in phaseList"
              :key="index"
              :type="getPhaseType(phase)"
              :color="getPhaseColor(phase)"
              :hollow="isCurrentPhase(phase)"
            >
              <div class="timeline-content">
                <h4 :class="{'current-phase': isCurrentPhase(phase)}">{{ phase.phaseName }}</h4>
                <p v-if="phase.phaseTime">预估时长: {{ formatDuration(phase.phaseTime) }}</p>
              </div>
            </el-timeline-item>

            <!-- 结束阶段 -->
            <el-timeline-item
              v-if="hoveredRow.executionStatus == 2 || hoveredRow.executionStatus == 3"
              :timestamp="hoveredRow.endTime ? hoveredRow.endTime : '结束时间未知'"
              :type="hoveredRow.executionStatus == 2 ? 'success' : 'danger'"
              :color="hoveredRow.executionStatus == 2 ? '#67C23A' : '#F56C6C'"
              size="large"
            >
              <template #icon>
                <el-icon>
                  <CircleCheck v-if="hoveredRow.executionStatus == 2" />
                  <CircleClose v-else />
                </el-icon>
              </template>
              <div class="timeline-content">
                <h4>{{ hoveredRow.executionStatus == 2 ? '成功结束' : '失败结束' }}</h4>
                <p v-if="hoveredRow.executionStatus == 3 && hoveredRow.resultEvaluation"
                   class="failure-reason">
                  失败原因: {{ hoveredRow.resultEvaluation }}
                </p>
              </div>
            </el-timeline-item>
          </el-timeline>
        </div>
      </div>
    </el-dialog>

    <el-dialog
      v-model="qcResultDialogVisible"
      title="质控结果"
      width="75%"
      append-to-body
      destroy-on-close
      class="qc-result-dialog"
      @closed="onQcResultDialogClosed"
    >
      <div v-loading="qcResultLoading" class="qc-result-dialog-body">
        <component
          v-if="qcResultComponent"
          :is="qcResultComponent"
          :report-data="qcResultData"
        />
      </div>
    </el-dialog>

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

.execution-log-card {
  position: relative !important;
  cursor: pointer;
  overflow: visible !important;
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



.params-section, .result-section, .all-data-section, .phase-section {
  margin-bottom: 16px;
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

.qc-result-dialog-body {
  min-height: 200px;
}

.phase-section .custom-timeline {
  margin-top: 4px;
}

/* 时间线样式 */
.custom-timeline {
  padding-left: 10px;
}

.timeline-content h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  font-weight: 600;
}

.timeline-content.current-phase {
  color: #409EFF;
  font-weight: bold;
  background-color: #ecf5ff;
  padding: 4px;
  border-radius: 4px;
}

.timeline-content p {
  margin: 4px 0;
  font-size: 12px;
  color: #606266;
}

.failure-reason {
  color: #F56C6C !important;
  font-weight: 500;
  background-color: #fef0f0;
  padding: 4px;
  border-radius: 4px;
  border-left: 2px solid #F56C6C;
}

.data-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.data-item {
  display: flex;
  justify-content: space-between;
  padding: 6px 8px;
  background: #fafafa;
  border-radius: 4px;
  font-size: 12px;
}

.data-label {
  color: #606266;
  font-weight: 500;
}

.data-value {
  color: #303133;
  word-break: break-all;
}

.no-data {
  text-align: center;
  color: #909399;
  font-style: italic;
}

.execution-log-dialog-content {
  max-height: 600px;
  overflow-y: auto;
}

.params-table, .result-table {
  margin-bottom: 20px;
}

.params-table .el-table__header-wrapper th {
  background-color: #f5f7fa;
  color: #606266;
  font-weight: 600;
}

.result-table .el-table__header-wrapper th {
  background-color: #f0f9ff;
  color: #409eff;
  font-weight: 600;
}

.params-section h5, .result-section h5, .phase-section h5 {
  margin-bottom: 12px;
  color: #303133;
  font-size: 16px;
  font-weight: 600;
  border-left: 4px solid #409eff;
  padding-left: 8px;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .data-grid {
    grid-template-columns: 1fr;
  }

  .params-table, .result-table {
    font-size: 12px;
  }
}
</style>

<script setup name="Records">
import { listRecords, getRecords, delRecords, addRecords, updateRecords, stopRecords, getReportPreview } from "@/api/quality_control/records";
import { Document, VideoPlay, CircleCheck, CircleClose, CircleCloseFilled, Stopwatch } from '@element-plus/icons-vue';
import { getCurrentInstance, onMounted, nextTick, watch, ref, computed, reactive, toRefs } from 'vue';
import { ElTimeline, ElTimelineItem } from 'element-plus';
import ReportD1 from '../report/ReportD1AuditSpanCheck.vue';
import ReportD2 from '../report/ReportD2ZeroSpanCheck.vue';
import ReportD3 from '../report/ReportD3MultiCheck.vue';
import ReportD4 from '../report/ReportD4PrecisionCheck.vue';
import ReportD5 from '../report/ReportD5AccuracyCheck.vue';
import ReportD6 from '../report/ReportD6ConversionCheck.vue';
import ReportD7 from '../report/ReportD7TransferAndTrackCheck.vue';

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
const hoveredRow = ref(null);
const parsedExecutionLog = ref({});
const executionLogDialogVisible = ref(false);
const phaseList = ref([]);
const qcResultDialogVisible = ref(false);
const qcResultLoading = ref(false);
const qcResultComponent = ref(null);
const qcResultData = ref({});
/** 详情弹窗内用于浓度显示换算：存库 calculatedValue 为 ppm，非 CO 时按 ppb 展示（×1000） */
const executionDetailGasSymbol = ref('');

// 参数名中文映射（须在 displayExecutionParams 之前定义）
const paramDisplayNames = {
  taskType: '任务类型',
  qualityControlType: '质控类型',
  parameter: '质控参数',
  deviceId: '设备ID',
  triggerType: '触发类型',
  calculatedValue: '计算值',
  standardValue: '标准值',
  monitoringData: '监测数据',
  stdGasInPortName: '标气入口名称',
  taskDescription: '任务描述',
  gas: '气体类型',
  readDataSpan: '读取数据间隔(秒)',
  genGasConc: '生成气体浓度',
  taskName: '任务名称',
  genGasTime: '生成气体时间(秒)',
  readDataCount: '读取数据次数',
  targetFlowLpm: '目标流量 (L/min)',
  flowRateLpm: '目标流量 (L/min)',
  targetFlow: '目标流量 (L/min)'
};

/** 执行记录详情中不展示的 params 键（调度元信息，避免干扰业务参数阅读） */
const EXECUTION_DETAIL_HIDDEN_PARAM_KEYS = new Set([
  'taskType',
  'taskDescription',
  'triggerType',
  'taskName'
]);

const displayExecutionParams = computed(() => {
  const p = parsedExecutionLog.value?.params;
  if (!p || typeof p !== 'object') {
    return {};
  }
  const out = {};
  for (const [k, v] of Object.entries(p)) {
    if (EXECUTION_DETAIL_HIDDEN_PARAM_KEYS.has(k)) {
      continue;
    }
    out[k] = v;
  }
  return out;
});

// 触发类型字典映射
const triggerTypeDict = [
  { value: '0', label: '自动触发' },
  { value: '1', label: '手动触发' }
];

// 任务类型字典映射（用于执行记录详情）
const taskTypeDict = [
  { value: '0', label: '计划任务' },
  { value: '1', label: '手动任务' },
  { value: '2', label: '现场任务' }
];

// 质控类型字典映射（用于执行记录详情，包含英文名称映射）
const qualityControlTypeDict = [
  { value: '0', label: '零点核查' },
  { value: '1', label: '跨度校准' },
  { value: '2', label: '线性核查' },
  { value: '3', label: '精密度检查' },
  { value: '4', label: '准确度校准' },
  { value: '5', label: '转换率检查' },
  { value: '6', label: '人工核查' },
  // 添加英文名称映射
  { value: 'zero_check', label: '零点核查' },
  { value: 'span_calibration', label: '跨度校准' },
  { value: 'linear_check', label: '线性核查' },
  { value: 'precision_check', label: '精密度检查' },
  { value: 'accuracy_calibration', label: '准确度校准' },
  { value: 'conversion_rate_check', label: '转换率检查' },
  { value: 'audit_span_check', label: '人工核查' }
];

// 结果名中文映射
const resultDisplayNames = {
  resultValue: '结果值',
  stdValue: '标准值',
  deviceValue: '设备值',
  checkPassLimit: '通过限值',
  checkCalibLimit: '校准限值',
  slope: '斜率',
  intercept: '截距',
  correlation: '相关系数',
  deviceValues: '设备值列表',
  stdValues: '标准值列表',
  check_r_min: '相关系数最小值',
  check_a_max: '斜率最大值',
  check_a_min: '斜率最小值',
  check_b_scope: '截距范围',
  precision: '精密度',
  mean: '平均值',
  standardDeviation: '标准偏差',
  deviceStdGas: '设备标气浓度',
  checkRsd20Max: '20%满量程相对标准偏差最大值',
  efficiency: '转换效率',
  origNoDatas: '原始NO数据',
  origNoxDatas: '原始NOx数据',
  remNoDatas: '滴定NO数据',
  remNoxDatas: '滴定NOx数据',
  origNoAvg: '原始NO平均值',
  origNoxAvg: '原始NOx平均值',
  remNoAvg: '滴定NO平均值',
  remNoxAvg: '滴定NOx平均值',
  isPass: '是否通过',
  // 新增字段映射
  checkTime: '检查时间',
  checkData: '检查数据',
  qualityControlType: '质控类型',
  taskType: '任务类型',
  stdGasInPortName: '标气入口名称',
  taskDescription: '任务描述',
  gas: '气体类型',
  readDataSpan: '读取数据间隔(秒)',
  genGasConc: '生成气体浓度',
  taskName: '任务名称',
  triggerType: '触发类型',
  genGasTime: '生成气体时间(秒)',
  readDataCount: '读取数据次数',
  isException: '是否异常',
  resultMessage: '结果说明',
  errorMessage: '错误信息'
};

const data = reactive({
  form: {},
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    taskType: null,
    qualityControlType: null,
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

// 解析执行记录JSON
function parseExecutionLog(executionLog) {
  if (!executionLog) {
    return {};
  }

  try {
    return JSON.parse(executionLog);
  } catch (error) {
    console.error('解析执行记录失败:', error);
    return {};
  }
}

function resolveQcResultReportComponent(componentName) {
  switch (componentName) {
    case 'ReportD1':
      return ReportD1;
    case 'ReportD2':
      return ReportD2;
    case 'ReportD3':
      return ReportD3;
    case 'ReportD4':
      return ReportD4;
    case 'ReportD5':
      return ReportD5;
    case 'ReportD6':
      return ReportD6;
    case 'ReportD7':
      return ReportD7;
    default:
      return null;
  }
}

function onQcResultDialogClosed() {
  qcResultComponent.value = null;
  qcResultData.value = {};
  qcResultLoading.value = false;
}

function handleQcResultPreview(row) {
  qcResultDialogVisible.value = true;
  qcResultLoading.value = true;
  qcResultComponent.value = null;
  qcResultData.value = {};
  getReportPreview(row.id).then((response) => {
    qcResultLoading.value = false;
    if (response.code !== 200) {
      proxy.$modal.msgError(response.msg || '加载失败');
      return;
    }
    const payload = response.data || {};
    const comp = resolveQcResultReportComponent(payload.component);
    if (!comp) {
      proxy.$modal.msgError('不支持的报表组件: ' + (payload.component || '(空)'));
      return;
    }
    qcResultComponent.value = comp;
    qcResultData.value = payload.reportData || {};
  }).catch(() => {
    qcResultLoading.value = false;
    proxy.$modal.msgError('加载质控结果失败');
  });
}

function looksLikeLogicAttrId(key) {
  if (key == null || typeof key !== 'string') {
    return false;
  }
  if (paramDisplayNames[key]) {
    return false;
  }
  return key.includes('_') && key.length > 10;
}

function inferAttrLabelFromId(key) {
  const lower = String(key).toLowerCase();
  if (lower.includes('flow') || /_flow$/.test(lower) || /^flow/.test(lower) || lower.includes('流')) {
    return '流量';
  }
  if (lower.includes('pressure') || lower.includes('press') || lower.includes('压')) {
    return '压力';
  }
  if ((lower.includes('temp') && !lower.includes('attempt')) || lower.includes('温')) {
    return '温度';
  }
  if (lower.includes('humid') || lower.includes('湿')) {
    return '湿度';
  }
  return '';
}

// 获取参数显示名称
function getParamDisplayName(key) {
  if (paramDisplayNames[key]) {
    return paramDisplayNames[key];
  }
  const inferred = inferAttrLabelFromId(key);
  if (inferred) {
    return looksLikeLogicAttrId(key) ? `${inferred}（逻辑属性）` : inferred;
  }
  return key;
}

// 获取结果显示名称
function getResultDisplayName(key) {
  return resultDisplayNames[key] || key;
}

// 获取字典标签
function getDictLabel(dictArray, value) {
  if (!dictArray || !Array.isArray(dictArray)) {
    return value;
  }

  // 尝试多种匹配方式
  let item = dictArray.find(dict => dict.value === value);
  if (!item) {
    // 如果精确匹配失败，尝试字符串匹配
    item = dictArray.find(dict => String(dict.value) === String(value));
  }
  if (!item) {
    // 如果字符串匹配也失败，尝试数字匹配
    const numValue = Number(value);
    if (!isNaN(numValue)) {
      item = dictArray.find(dict => Number(dict.value) === numValue);
    }
  }

  return item ? item.label : value;
}

/**
 * 从行数据与执行日志 params 推断气体符号（用于 calculatedValue：存 ppm，非 CO 展示为 ppb）。
 */
function resolveExecutionDetailGasSymbol(row, parsed) {
  const params = parsed && typeof parsed === 'object' ? parsed.params : null;
  const raw = params?.gas ?? params?.parameter ?? row?.parameter;
  if (raw == null || raw === '') {
    return '';
  }
  const s = String(raw).trim();
  if (/^[A-Za-z][A-Za-z0-9]*$/i.test(s) && !/^\d+$/.test(s)) {
    return s.toUpperCase();
  }
  const first = s.split(',')[0].trim();
  const label = getDictLabel(quality_control_param.value, first);
  return String(label)
    .trim()
    .toUpperCase()
    .replace(/O₂/g, 'O2')
    .replace(/O2/g, 'O2');
}

/**
 * 计算值在库中按 ppm 存储：CO 仍显示 ppm，其余气体显示为 ppb（×1000）。
 */
function formatCalculatedValueFromStoredPpm(value) {
  if (value === null || value === undefined) {
    return '无';
  }
  const n = typeof value === 'number' ? value : parseFloat(String(value).replace(/,/g, ''));
  if (Number.isNaN(n)) {
    return String(value);
  }
  const gas = executionDetailGasSymbol.value;
  if (gas === 'CO') {
    const t = Number.isInteger(n) ? String(n) : String(Number(n.toFixed(6)).valueOf());
    return `${t} ppm`;
  }
  const ppb = n * 1000;
  const t = Number.isInteger(ppb) ? String(ppb) : ppb.toFixed(2).replace(/\.?0+$/, '');
  return `${t} ppb`;
}

// 格式化参数值
function formatParamValue(value, key) {
  if (value === null || value === undefined) return '无';
  if (typeof value === 'object') return JSON.stringify(value);
  if (typeof value === 'boolean') {
    return value ? '是' : '否';
  }
  if (typeof value === 'number') {
    if (key === 'calculatedValue') {
      return formatCalculatedValueFromStoredPpm(value);
    }
    if (key === 'targetFlowLpm' || key === 'flowRateLpm' || key === 'targetFlow') {
      return `${Number.isInteger(value) ? value : value.toFixed(2)} L/min`;
    }
    return Number.isInteger(value) ? value.toString() : value.toFixed(2);
  }

  const strValue = String(value);

  switch (key) {
    case 'calculatedValue':
      return formatCalculatedValueFromStoredPpm(strValue);
    case 'taskType':
      return getDictLabel(taskTypeDict, strValue);
    case 'qualityControlType':
      return getDictLabel(qualityControlTypeDict, strValue);
    case 'triggerType':
      return getDictLabel(triggerTypeDict, strValue);
    case 'parameter':
      return getDictLabel(quality_control_param.value, strValue);
    case 'targetFlowLpm':
    case 'flowRateLpm':
    case 'targetFlow':
      return `${strValue} L/min`;
    default:
      return strValue;
  }
}

// 格式化结果值
function formatResultValue(value, key) {
  if (value === null || value === undefined) return '无';
  if (Array.isArray(value)) {
    return value.length > 0 ? value.join(', ') : '空数组';
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  if (typeof value === 'boolean') {
    return value ? '是' : '否';
  }
  if (typeof value === 'number') {
    if (key === 'calculatedValue') {
      return formatCalculatedValueFromStoredPpm(value);
    }
    // 如果是整数，不显示小数位
    return Number.isInteger(value) ? value.toString() : value.toFixed(2);
  }

  // 根据字段名使用字典数据转换
  const strValue = String(value);

  switch (key) {
    case 'calculatedValue':
      return formatCalculatedValueFromStoredPpm(strValue);
    case 'taskType':
      return getDictLabel(taskTypeDict, strValue);
    case 'qualityControlType':
      return getDictLabel(qualityControlTypeDict, strValue);
    case 'triggerType':
      return getDictLabel(triggerTypeDict, strValue);
    default:
      return strValue;
  }
}

// 获取执行记录状态对应的CSS类名
function getExecutionLogStatusClass(executionStatus) {
  // 确保转换为字符串进行比较
  const status = String(executionStatus);

  switch (status) {
    case '0': // 等待中
      return 'status-waiting';
    case '1': // 执行中
      return 'status-running';
    case '2': // 成功
      return 'status-success';
    case '3': // 失败
      return 'status-failed';
    default:
      return 'status-default';
  }
}

// 显示执行记录详情
function showExecutionLogDetail(row) {
  hoveredRow.value = row;
  const parsed = parseExecutionLog(row.executionLog);
  parsedExecutionLog.value = parsed;
  executionDetailGasSymbol.value = resolveExecutionDetailGasSymbol(row, parsed);
  phaseList.value = row.phaseList || [];
  nextTick(() => {
    executionLogDialogVisible.value = true;
  });
}

// 隐藏执行记录详情
function hideExecutionLogDetail() {
  hoveredRow.value = null;
  parsedExecutionLog.value = {};
}

// 判断是否为当前阶段
function isCurrentPhase(phase) {
  // 检查是否有currentPhase为true的阶段
  const currentPhaseExists = phaseList.value.some(p => p.currentPhase === true);

  if (currentPhaseExists) {
    // 如果有currentPhase标记的阶段，则标记为true的阶段是当前阶段
    return phase.currentPhase === true;
  } else {
    // 如果没有currentPhase标记的阶段，则根据hoveredRow的executionStatus判断
    const executionStatus = hoveredRow.value?.executionStatus;
    // 如果执行状态不是结束状态(2,3)，且phaseList有数据，则第一个阶段为当前阶段
    if (executionStatus !== 2 && executionStatus !== 3 && phaseList.value.length > 0) {
      return phaseList.value[0] === phase;
    }
  }

  return false;
}

// 获取阶段类型
function getPhaseType(phase) {
  if (isCurrentPhase(phase)) {
    return 'primary';
  }
  return 'default';
}

// 获取阶段颜色
function getPhaseColor(phase) {
  if (isCurrentPhase(phase)) {
    return '#409EFF'; // 当前阶段颜色
  }
  return '#C0C4CC'; // 默认颜色
}

// 获取阶段状态颜色
function getPhaseStatusColor(status) {
  // 状态为0-等待中，1-执行中，2-成功，3-失败
  switch (String(status)) {
    case '0':
      return '#E6A23C'; // 等待中 - 橙色
    case '1':
      return '#409EFF'; // 执行中 - 蓝色
    case '2':
      return '#67C23A'; // 成功 - 绿色
    case '3':
      return '#F56C6C'; // 失败 - 红色
    default:
      return '#C0C4CC'; // 默认 - 灰色
  }
}

/** 查询质控记录列表 */
function getList() {
  loading.value = true;
  listRecords(queryParams.value).then(response => {
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

/** 重置按钮操作 */
function resetQuery() {
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

/** 修改按钮操作 */
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

// 格式化持续时间，将秒数转换为小时、分钟、秒格式
function formatDuration(seconds) {
  if (seconds < 0) {
    return '无效时间';
  }

  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;

  const parts = [];
  if (h > 0) {
    parts.push(`${h}小时`);
  }
  if (m > 0) {
    parts.push(`${m}分钟`);
  }
  if (s > 0 || (h === 0 && m === 0)) { // 如果小时和分钟都为0，即使秒为0也要显示
    parts.push(`${s}秒`);
  }

  return `预估时长: ${parts.join(' ')}`;
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

/** 导出按钮操作 */
function handleExport() {
  proxy.download('quality_control/records/export', {
    ...queryParams.value
  }, `records_${new Date().getTime()}.xlsx`)
}

onMounted(() => {
  // 等待下一个tick确保字典数据加载完成
  nextTick(() => {
    getList();
  });
});
</script>
