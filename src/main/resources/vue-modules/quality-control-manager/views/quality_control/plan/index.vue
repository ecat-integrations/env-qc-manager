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

    <el-table v-loading="loading" :data="planList">
      <el-table-column label="计划名称" align="center" prop="planName" min-width="200" show-overflow-tooltip />
      <el-table-column label="质控类型" align="center" prop="qcType" width="110">
        <template #default="scope">
          <span>{{ qcTypeLabel(scope.row.qcType) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="仪器" align="center" prop="instruments" width="120">
        <template #default="scope">
          <span>{{ instrumentsLabel(scope.row.instruments) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="调度摘要" align="center" prop="scheduleSummary" min-width="260" show-overflow-tooltip />
      <el-table-column label="状态" align="center" prop="status" width="90">
        <template #default="scope">
          <el-tag :type="statusTagType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="下次触发时间" align="center" prop="nextFireTime" width="170" />
      <el-table-column label="上次触发时间" align="center" prop="lastFireTime" width="170" />
      <el-table-column label="创建人" align="center" prop="createdBy" width="110" />
      <el-table-column label="操作" align="center" width="230" class-name="small-padding fixed-width">
        <template #default="scope">
          <template v-if="scope.row.status === 'ACTIVE'">
            <el-button link type="warning" @click="handleStatus(scope.row, 'pause')">暂停</el-button>
            <el-button link type="success" @click="handleRun(scope.row)">立即执行</el-button>
          </template>
          <template v-else-if="scope.row.status === 'PAUSED'">
            <el-button link type="success" @click="handleStatus(scope.row, 'enable')">启用</el-button>
          </template>
          <el-button
            v-if="scope.row.status === 'ACTIVE' || scope.row.status === 'PAUSED'"
            link
            type="primary"
            @click="handleEdit(scope.row)"
          >编辑</el-button>
          <el-button link type="danger" @click="handleDelete(scope.row)">删除</el-button>
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
import { getCurrentInstance, onMounted, ref, reactive, toRefs } from 'vue';
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

/** 新建计划 */
function handleAdd() {
  planEditRef.value.open(null);
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
</style>
