<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryRef" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="计划名称" prop="planName">
        <el-input
          class="qc-plan-filter-input"
          v-model="queryParams.planName"
          placeholder="请输入计划名称模糊查询"
          clearable
          @keyup.enter="handleQuery"
        />
      </el-form-item>
      <el-form-item label="质控类型" prop="qcType">
        <el-select class="custom-select" v-model="queryParams.qcType" placeholder="请选择质控类型" clearable>
          <el-option
            v-for="t in QC_TYPE_OPTIONS"
            :key="t.value"
            :label="t.label"
            :value="t.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select class="custom-select" v-model="queryParams.status" placeholder="请选择状态" clearable>
          <el-option
            v-for="s in STATUS_OPTIONS"
            :key="s.value"
            :label="s.label"
            :value="s.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="Plus" @click="handleAdd">新建计划</el-button>
      </el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="displayPlans" class="qc-plan-table">
      <!-- 行展开收纳低频信息：上次触发 / 校准策略 / 创建人 / 创建时间（主列一屏不丢信息） -->
      <el-table-column type="expand">
        <template #default="scope">
          <el-descriptions :column="2" border size="small" class="qc-plan-expand-desc">
            <el-descriptions-item label="上次触发时间">{{ formatDateTime(scope.row.lastFireTime) }}</el-descriptions-item>
            <el-descriptions-item label="校准策略">{{ calibrationPolicyLabel(scope.row.calibrationPolicy) }}</el-descriptions-item>
            <el-descriptions-item label="创建人">{{ scope.row.createdBy || '—' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatDateTime(scope.row.createTime) }}</el-descriptions-item>
          </el-descriptions>
        </template>
      </el-table-column>
      <!-- 列宽策略：名称/调度/下次触发为弹性列按 280/170/150 比例分摊剩余宽度（名称不再独吞），操作/状态固定宽 -->
      <el-table-column label="计划名称" prop="planName" min-width="280" align="left">
        <template #default="scope">
          <div class="qc-plan-name-cell">
            <div class="qc-plan-name-main" :title="scope.row.planName">{{ scope.row.planName }}</div>
            <div class="qc-plan-name-sub">
              <span class="qc-plan-name-subtext">{{ qcTypeLabel(scope.row.qcType) }} · {{ instrumentsLabel(scope.row.instruments) }}</span>
              <el-tag
                v-if="scope.row.sameDayPriority === 'LOW' || scope.row.sameDayPriority === 'HIGH'"
                size="small"
                :type="scope.row.sameDayPriority === 'HIGH' ? 'warning' : 'info'"
                class="qc-plan-priority-tag"
              >{{ sameDayPriorityLabel(scope.row.sameDayPriority) }}</el-tag>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="调度" align="center" prop="scheduleSummary" min-width="170" show-overflow-tooltip />
      <el-table-column label="下次触发" align="center" prop="nextFireTime" min-width="150">
        <template #default="scope">
          <div v-if="fireParts(scope.row.nextFireTime)" class="qc-plan-fire-cell">
            <div>{{ fireParts(scope.row.nextFireTime)[0] }}</div>
            <div class="qc-plan-fire-time">{{ fireParts(scope.row.nextFireTime)[1] }}</div>
          </div>
          <span v-else>{{ scope.row.nextFireTime || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" align="center" prop="status" width="80">
        <template #default="scope">
          <el-tag :type="statusTagType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" width="200" class-name="small-padding fixed-width">
        <template #default="scope">
          <template v-if="scope.row.status === 'ACTIVE'">
            <el-button link type="warning" @click="handleStatus(scope.row, 'pause')">暂停</el-button>
            <el-button link type="success" @click="handleRun(scope.row)">执行</el-button>
          </template>
          <el-button v-else-if="scope.row.status === 'PAUSED'" link type="success" @click="handleStatus(scope.row, 'enable')">启用</el-button>
          <el-button
            v-if="scope.row.status === 'ACTIVE' || scope.row.status === 'PAUSED'"
            link
            type="primary"
            @click="handleEdit(scope.row)"
          >编辑</el-button>
          <!-- 低频动作（删除）收纳进「···」更多下拉，主列保持内联三项一屏 -->
          <el-dropdown trigger="click" @command="cmd => handleRowCommand(cmd, scope.row)">
            <el-button link type="primary" class="qc-plan-more-btn">···</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="delete">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
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

    <PlanEditDialog
      ref="planEditRef"
      @success="getList"
    />
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, ref, reactive, toRefs } from 'vue';
import { ElMessageBox, ElMessage } from 'element-plus';
import { listPlan, getPlan, delPlan, changePlanStatus, runPlan } from '@/api/quality_control/plan';
import PlanEditDialog from './PlanEditDialog.vue';

defineOptions({ name: 'QcmPlanPage' });

const { proxy } = getCurrentInstance();

/** 质控类型闭集（QualityControlTypeEnum name ↔ 中文标签，FR-01-27） */
const QC_TYPE_OPTIONS = [
  { value: 'zero_check', label: '零点检查' },
  { value: 'span_check', label: '跨度检查' },
  { value: 'multi_check', label: '多点检查' },
  { value: 'precision_check', label: '精密度检查' },
  { value: 'accuracy_check', label: '准确度检查' },
  { value: 'conversion_check', label: '转换率检查' },
  { value: 'audit_span_check', label: '人工核查' },
  { value: 'multi_zero_check', label: '多仪器零点质控' }
];

/** 计划状态闭集（FR-01-15 状态机） */
const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '启用' },
  { value: 'PAUSED', label: '暂停' },
  { value: 'FINISHED', label: '已完成' }
];

const planList = ref([]);
const loading = ref(true);
const showSearch = ref(true);
const total = ref(0);
const planEditRef = ref(null);

const data = reactive({
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    planName: null,
    qcType: null,
    status: null
  }
});

const { queryParams } = toRefs(data);

function qcTypeLabel(value) {
  const hit = QC_TYPE_OPTIONS.find(t => t.value === value);
  return hit ? hit.label : (value || '—');
}

/** 仪器代码键 NO2 展示为 NOx（与后端候选闭集展示语义一致） */
function instrumentsLabel(instrumentsJson) {
  if (!instrumentsJson) {
    return '—';
  }
  try {
    const arr = JSON.parse(instrumentsJson);
    if (!Array.isArray(arr)) {
      return instrumentsJson;
    }
    return arr.map(i => (i === 'NO2' ? 'NOx' : i)).join('、') || '—';
  } catch (e) {
    return instrumentsJson;
  }
}

/** 校准策略列：NULL/STANDARD=标准判定（落库缺省语义），CALIBRATE_LOW_DRIFT=低偏差也校准 */
function calibrationPolicyLabel(policy) {
  if (policy === 'CALIBRATE_LOW_DRIFT') {
    return '低偏差也校准';
  }
  return '标准判定';
}

/** 同日优先级列：NULL/NONE=不参与（落库缺省语义） */
function sameDayPriorityLabel(priority) {
  if (priority === 'LOW') {
    return '低优先级';
  }
  if (priority === 'HIGH') {
    return '高优先级';
  }
  return '不参与';
}

/**
 * 集合快捷方式命名模板：{集合名}-{四气|气种「、」拼接}-{零点|跨度}。
 * 前缀聚合排序（仅展示分组，非实体）：命中模板的行按集合前缀聚到一起，
 * 组间保持原有先后（整体时间序不动），组内零点行在前、跨度按槽位序 O3/CO/NOx/SO2。
 */
const COLLECTION_NAME_PATTERN = /^(.+)-(四气|(?:O3|NOx|CO|SO2)(?:、(?:O3|NOx|CO|SO2))*)-(零点|跨度)$/;
const SPAN_SLOT_ORDER = ['O3', 'CO', 'NOx', 'SO2'];

/** 行的集合前缀（命中模板才有；组内排序键：零点行 0，跨度行按槽位序 1..4） */
function collectionGroup(row) {
  const m = COLLECTION_NAME_PATTERN.exec(row.planName || '');
  if (!m) {
    return null;
  }
  return {
    key: m[1],
    rank: m[3] === '零点' ? 0 : 1 + SPAN_SLOT_ORDER.indexOf(m[2])
  };
}

/** 前缀聚合排序后的展示列表（页内展示层重排，不动服务端分页与排序语义） */
const displayPlans = computed(() => {
  const rows = planList.value;
  const groupOrder = [];
  const groups = new Map();
  rows.forEach(row => {
    const g = collectionGroup(row);
    const key = g ? g.key : '@single:' + row.id + ':' + row.planName;
    if (!groups.has(key)) {
      groups.set(key, { rows: [] });
      groupOrder.push(key);
    }
    groups.get(key).rows.push(row);
  });
  const result = [];
  for (const key of groupOrder) {
    const group = groups.get(key);
    group.rows.sort((a, b) => {
      const ra = collectionGroup(a);
      const rb = collectionGroup(b);
      return (ra ? ra.rank : 0) - (rb ? rb.rank : 0);
    });
    result.push(...group.rows);
  }
  return result;
});

function statusLabel(status) {
  const hit = STATUS_OPTIONS.find(s => s.value === status);
  return hit ? hit.label : (status || '—');
}

function statusTagType(status) {
  if (status === 'ACTIVE') {
    return 'success';
  }
  if (status === 'PAUSED') {
    return 'info';
  }
  if (status === 'FINISHED') {
    return 'primary';
  }
  return 'info';
}

/** 查询计划列表 */
function getList() {
  loading.value = true;
  listPlan(queryParams.value).then(response => {
    planList.value = response.rows;
    total.value = response.total;
    loading.value = false;
  });
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

/** 新建计划（弹窗内首行选创建方式：日常/周核查集合或自定义单计划） */
function handleAdd() {
  planEditRef.value.open(null);
}

/** 触发时间 ISO 形（T 或空格分隔）拆日期/时间两段；非该形态返回 null 由调用方原样展示 */
const FIRE_TIME_PATTERN = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/;

function fireParts(value) {
  if (!value) {
    return null;
  }
  const m = FIRE_TIME_PATTERN.exec(String(value));
  return m ? [m[1], m[2]] : null;
}

/** 展开行时间统一单行展示：ISO 形截到分钟，其余原样 */
function formatDateTime(value) {
  const parts = fireParts(value);
  return parts ? parts[0] + ' ' + parts[1] : (value || '—');
}

/** 操作列「···」更多下拉命令分发（当前仅删除） */
function handleRowCommand(command, row) {
  if (command === 'delete') {
    handleDelete(row);
  }
}

/** 编辑计划：拉取详情回填（列表行 jsonb 为字符串，详情同源更稳） */
function handleEdit(row) {
  getPlan(row.id).then(response => {
    if (response.code !== 200) {
      proxy.$modal.msgError(response.msg || '加载计划详情失败');
      return;
    }
    planEditRef.value.open(response.data);
  });
}

/** 启用 / 暂停（二次确认，FR-01-23） */
function handleStatus(row, action) {
  const actionText = action === 'pause' ? '暂停' : '启用';
  ElMessageBox.confirm('是否确认' + actionText + '计划「' + row.planName + '」？', '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    changePlanStatus(row.id, action).then(response => {
      if (response.code === 200) {
        proxy.$modal.msgSuccess(actionText + '成功');
        getList();
      } else {
        proxy.$modal.msgError(response.msg || actionText + '失败');
      }
    });
  }).catch(() => {});
}

/** 立即执行（确认框展示参数摘要，FR-01-24；冲突 BatchResult rejected 逐因展示） */
function handleRun(row) {
  const summary = [
    '类型：' + qcTypeLabel(row.qcType),
    '仪器：' + instrumentsLabel(row.instruments),
    '浓度：' + (row.concentrationPpb != null ? row.concentrationPpb + ' ppb' : '无'),
    '流量：' + (row.flowRateLpm != null ? row.flowRateLpm + ' L/min' : '无'),
    '调度：' + (row.scheduleSummary || '—')
  ].join('\n');
  ElMessageBox.confirm(summary, '确认立即执行该质控计划？', {
    confirmButtonText: '立即执行',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    runPlan(row.id).then(response => {
      if (response.code !== 200) {
        proxy.$modal.msgError(response.msg || '触发失败');
        return;
      }
      const result = response.data || {};
      if (result.status === 'ACCEPTED') {
        proxy.$modal.msgSuccess('已受理，批次 ' + (result.batchId || ''));
      } else if (result.failureReason === 'EXECUTOR_BUSY_CONFLICT') {
        ElMessage.error('执行器忙，已有任务运行中');
      } else {
        ElMessage.error('触发被拒绝：' + (result.failureReason || '未知原因'));
      }
      getList();
    });
  }).catch(() => {});
}

/** 删除计划（物理删除，二次确认，FR-01-18/23） */
function handleDelete(row) {
  ElMessageBox.confirm('是否确认删除计划「' + row.planName + '」？删除后不可恢复。', '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    delPlan(row.id).then(response => {
      if (response.code === 200) {
        proxy.$modal.msgSuccess('删除成功');
        getList();
      } else {
        proxy.$modal.msgError(response.msg || '删除失败');
      }
    });
  }).catch(() => {});
}

onMounted(() => {
  getList();
});
</script>

<style>
.qc-plan-filter-input {
  width: 200px;
}

/* 计划名称列两行单元格：主行名称截断悬浮 title 看全量，副行小字收纳类型/仪器/同日优先级 */
.qc-plan-name-cell {
  line-height: 1.4;
}

.qc-plan-name-main {
  font-weight: 600;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.qc-plan-name-sub {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
  overflow: hidden;
}

.qc-plan-priority-tag {
  flex-shrink: 0;
  transform: scale(0.9);
}

.qc-plan-fire-cell {
  line-height: 1.4;
  font-size: 13px;
}

.qc-plan-fire-time {
  font-size: 12px;
  color: #909399;
}

.qc-plan-expand-desc {
  padding: 4px 24px;
}

/* 操作列收紧按钮间距：链接按钮默认 12px 左距，四项动作（含「···」下拉）单行排布 */
.qc-plan-table .el-button + .el-button {
  margin-left: 6px;
}

.qc-plan-table .qc-plan-more-btn {
  margin-left: 6px;
}
</style>
