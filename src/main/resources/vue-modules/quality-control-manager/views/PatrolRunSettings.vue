<template>
  <div class="tw:min-h-screen tw:bg-gray-50">
    <!-- 主内容区 -->
    <main class="tw:max-w-7xl tw:mx-auto tw:px-4 sm:tw:px-6 lg:tw:px-8 tw:py-6">
      <!-- 巡检查询 -->
      <div v-if="activeTab === 'query'" class="tw:bg-white tw:rounded-lg tw:shadow">
        <!-- 搜索区域 -->
        <div class="tw:p-4 tw:border-b">
          <div class="tw:flex tw:flex-wrap tw:items-end tw:gap-4">
            <div class="tw:w-full sm:tw:w-auto">
              <label class="tw:block tw:text-sm tw:font-medium tw:text-gray-700 tw:mb-1">巡检类型</label>
              <el-select
                v-model="queryParams.type"
                placeholder="请选择巡检类型"
                class="w-full sm:w-48"
                clearable
              >
                <el-option
                  v-for="type in inspectionTypes"
                  :key="type.value"
                  :label="type.label"
                  :value="type.value"
                />
              </el-select>
            </div>
            <div class="tw:w-full sm:tw:w-auto">
              <label class="tw:block tw:text-sm tw:font-medium tw:text-gray-700 tw:mb-1">时间范围</label>
              <el-date-picker
                v-model="queryParams.dateRange"
                type="daterange"
                range-separator="至"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                class="w-full sm:w-64"
              />
            </div>
            <div class="tw:flex tw:space-x-2">
              <el-button type="primary" @click="handleSearch">查询</el-button>
              <el-button @click="resetSearch">重置</el-button>
            </div>
          </div>
        </div>
        <!-- 表格区域 -->
        <div class="tw:overflow-x-auto">
          <el-table :data="inspectionList" style="width: 100%">
            <el-table-column prop="id" label="巡检ID" width="120" />
            <el-table-column prop="createTime" label="生成时间" width="180">
              <template #default="{row}">
                {{ formatDate(row.createTime) }}
              </template>
            </el-table-column>
            <el-table-column prop="type" label="巡检类型" width="180">
              <template #default="{row}">
                <el-tag :type="getTagType(row.type)">
                  {{ getTypeLabel(row.type) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="巡检备注" />
            <el-table-column prop="content" label="巡检内容" width="220">
              <template #default="{row}">
                <a
                  href="#"
                  class="tw:text-blue-600 tw:hover:text-blue-800 tw:hover:underline"
                  @click.prevent="downloadFile(row.content)"
                >
                  《{{ row.content }}》
                </a>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <!-- 分页区域 -->
        <div class="tw:p-4 tw:border-t tw:flex tw:items-center tw:justify-between">
          <div class="tw:text-sm tw:text-gray-600">
            共 {{ total }} 条记录
          </div>
          <el-pagination
            v-model:current-page="queryParams.page"
            v-model:page-size="queryParams.pageSize"
            :page-sizes="[10, 20, 50, 100]"
            layout="prev, pager, next, sizes"
            :total="total"
            @size-change="handleSizeChange"
            @current-change="handleCurrentChange"
          />
        </div>
      </div>
      <!-- 巡检设置 -->
      <div v-if="activeTab === 'settings'" class="tw:space-y-4">
        <div
          v-for="setting in inspectionSettings"
          :key="setting.type"
          class="tw:bg-white tw:rounded-lg tw:shadow tw:p-6"
        >
          <div class="tw:flex tw:justify-between tw:items-start tw:mb-4">
            <div>
              <h3 class="tw:text-lg tw:font-medium tw:text-gray-900">{{ setting.label }}</h3>
              <p class="tw:text-sm tw:text-gray-500 tw:mt-1">
                下次运行时间: {{ calculateNextRunTime(setting) || '未设置' }}
              </p>
            </div>
            <el-switch
              v-model="setting.enabled"
              active-text="开启"
              inactive-text="关闭"
              @change="handleSettingChange(setting)"
            />
          </div>
          <div class="tw:mt-4">
            <div class="tw:grid tw:grid-cols-1 md:tw:grid-cols-2 tw:gap-4">
              <div>
                <label class="tw:block tw:text-sm tw:font-medium tw:text-gray-700 tw:mb-1">运行星期</label>
                <el-checkbox-group v-model="setting.selectedDays" :disabled="!setting.enabled">
                  <el-checkbox v-for="day in weekDays" :key="day.value" :label="day.value">
                    {{ day.label }}
                  </el-checkbox>
                </el-checkbox-group>
              </div>
              <div>
                <label class="tw:block tw:text-sm tw:font-medium tw:text-gray-700 tw:mb-1">运行时间</label>
                <el-time-picker
                  v-model="setting.runTime"
                  placeholder="选择运行时间"
                  class="w-full"
                  :disabled="!setting.enabled"
                />
              </div>
            </div>
          </div>
        </div>
      </div>
      <!-- 物资管理 -->
      <div v-if="activeTab === 'materials'" class="tw:bg-white tw:rounded-lg tw:shadow">
        <!-- 操作区域 -->
        <div class="tw:p-4 tw:border-b tw:flex tw:justify-between tw:items-center">
          <div class="tw:flex tw:space-x-2">
            <el-button type="primary" @click="showAddMaterialDialog">
              <el-icon class="tw:mr-1"><Plus /></el-icon>
              新增物资
            </el-button>
            <el-button @click="exportMaterials">
              <el-icon class="tw:mr-1"><Download /></el-icon>
              导出
            </el-button>
          </div>
          <div class="tw:w-64">
            <el-input
              v-model="materialQuery.keyword"
              placeholder="搜索物资名称"
              clearable
              @clear="handleMaterialSearch"
              @keyup.enter="handleMaterialSearch"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
          </div>
        </div>
        <!-- 表格区域 -->
        <div class="tw:overflow-x-auto">
          <el-table :data="materialList" style="width: 100%">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="name" label="物资名称" width="180" />
            <el-table-column prop="type" label="物资类型" width="120">
              <template #default="{row}">
                <el-tag :type="row.type === 'gas' ? 'success' : 'warning'">
                  {{ row.type === 'gas' ? '标准气体' : '颗粒物滤纸带' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="spec" label="规格" width="150" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{row}">
                <el-tag :type="row.status === 'active' ? 'success' : 'info'">
                  {{ row.status === 'active' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="quantity" label="数量" width="100" />
            <el-table-column prop="lastUsed" label="最后使用时间" width="180">
              <template #default="{row}">
                {{ row.lastUsed ? formatDate(row.lastUsed) : '未使用' }}
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" />
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{row}">
                <el-button
                  size="small"
                  @click="editMaterial(row)"
                >
                  编辑
                </el-button>
                <el-button
                  size="small"
                  type="danger"
                  @click="deleteMaterial(row.id)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <!-- 分页区域 -->
        <div class="tw:p-4 tw:border-t tw:flex tw:items-center tw:justify-between">
          <div class="tw:text-sm tw:text-gray-600">
            共 {{ materialTotal }} 条记录
          </div>
          <el-pagination
            v-model:current-page="materialQuery.page"
            v-model:page-size="materialQuery.pageSize"
            :page-sizes="[10, 20, 50, 100]"
            layout="prev, pager, next, sizes"
            :total="materialTotal"
            @size-change="handleMaterialSizeChange"
            @current-change="handleMaterialCurrentChange"
          />
        </div>
      </div>
    </main>
    <!-- 新增/编辑物资弹窗 -->
    <el-dialog
      v-model="materialDialog.visible"
      :title="materialDialog.isEdit ? '编辑物资' : '新增物资'"
      width="600px"
    >
      <el-form
        ref="materialFormRef"
        :model="materialDialog.form"
        :rules="materialRules"
        label-width="100px"
      >
        <el-form-item label="物资名称" prop="name">
          <el-input v-model="materialDialog.form.name" placeholder="请输入物资名称" />
        </el-form-item>
        <el-form-item label="物资类型" prop="type">
          <el-radio-group v-model="materialDialog.form.type">
            <el-radio label="gas">标准气体</el-radio>
            <el-radio label="filter">颗粒物滤纸带</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="规格" prop="spec">
          <el-input v-model="materialDialog.form.spec" placeholder="请输入规格" />
        </el-form-item>
        <el-form-item label="数量" prop="quantity">
          <el-input-number
            v-model="materialDialog.form.quantity"
            :min="1"
            controls-position="right"
            class="w-full"
          />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="materialDialog.form.status">
            <el-radio label="active">启用</el-radio>
            <el-radio label="inactive">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input
            v-model="materialDialog.form.remark"
            type="textarea"
            :rows="3"
            placeholder="请输入备注信息"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="materialDialog.visible = false">取消</el-button>
          <el-button type="primary" @click="submitMaterialForm">确认</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, reactive, onMounted, nextTick } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Download, Search } from '@element-plus/icons-vue';

// 巡检类型
const inspectionTypes = [
  { value: 'station', label: '子站巡检' },
  { value: 'instrument_status', label: '仪器运行状况' },
  { value: 'multi_point', label: '仪器多点校准' },
  { value: 'precision', label: '仪器精密度审核' },
  { value: 'accuracy', label: '仪器准确度审核' },
  { value: 'nox_efficiency', label: '氮氧化物分析仪转换效率测试' },
  { value: 'ozone_calibration', label: '臭氧校准设备量值传递' }
];

// 频率选项
const frequencyOptions = [
  { value: 'hourly', label: '每小时执行一次' },
  { value: 'hourly_5', label: '每小时5分执行一次' },
  { value: 'daily', label: '每天执行一次' },
  { value: 'weekly', label: '每周执行一次' }
];

// 标签页
const tabs = [
  { id: 'settings', name: '巡检设置' }
];
const activeTab = ref('settings');

// 巡检查询相关
const queryParams = reactive({
  type: '',
  dateRange: [],
  page: 1,
  pageSize: 10
});
const inspectionList = ref([]);
const total = ref(0);

// 星期选项
const weekDays = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 0, label: '周日' }
];

// 巡检设置相关
const inspectionSettings = ref([
  {
    type: 'station',
    label: '子站巡检',
    enabled: true,
    selectedDays: [1, 2, 3, 4, 5], // 默认周一到周五
    runTime: new Date(2023, 5, 1, 8, 0, 0)
  },
  {
    type: 'instrument_status',
    label: '仪器运行状况',
    enabled: true,
    selectedDays: [1, 2, 3, 4, 5, 6, 0], // 默认每天
    runTime: new Date(2023, 5, 1, 9, 0, 0)
  }
]);

// 物资管理相关
const materialQuery = reactive({
  keyword: '',
  page: 1,
  pageSize: 10
});
const materialList = ref([]);
const materialTotal = ref(0);
const materialDialog = reactive({
  visible: false,
  isEdit: false,
  form: {
    id: '',
    name: '',
    type: 'gas',
    spec: '',
    quantity: 1,
    status: 'active',
    remark: ''
  }
});
const materialFormRef = ref();
const materialRules = reactive({
  name: [{ required: true, message: '请输入物资名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择物资类型', trigger: 'change' }],
  spec: [{ required: true, message: '请输入规格', trigger: 'blur' }],
  quantity: [{ required: true, message: '请输入数量', trigger: 'blur' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
});

// 格式化日期
const formatDate = (dateStr) => {
  if (!dateStr) return '';
  const date = new Date(dateStr);
  return `${date.getFullYear()}-${padZero(date.getMonth() + 1)}-${padZero(date.getDate())} ${padZero(date.getHours())}:${padZero(date.getMinutes())}`;
};
const padZero = (num) => {
  return num < 10 ? `0${num}` : num;
};

// 获取巡检类型标签
const getTypeLabel = (type) => {
  const item = inspectionTypes.find(t => t.value === type);
  return item ? item.label : type;
};

// 获取标签类型
const getTagType = (type) => {
  const types = {
    station: '',
    instrument_status: 'info',
    multi_point: 'warning',
    precision: 'success',
    accuracy: 'danger',
    nox_efficiency: '',
    ozone_calibration: 'info'
  };
  return types[type] || '';
};

// 下载文件
const downloadFile = (filename) => {
  ElMessage.success(`开始下载: ${filename}`);
  // 实际项目中这里应该是调用下载API
};

// 处理搜索
const handleSearch = () => {
  queryParams.page = 1;
  fetchInspections();
};

// 重置搜索
const resetSearch = () => {
  queryParams.type = '';
  queryParams.dateRange = [];
  handleSearch();
};

// 分页大小变化
const handleSizeChange = (size) => {
  queryParams.pageSize = size;
  fetchInspections();
};

// 当前页变化
const handleCurrentChange = (page) => {
  queryParams.page = page;
  fetchInspections();
};

// 获取巡检列表
const fetchInspections = () => {
  // 模拟API调用
  setTimeout(() => {
    const mockData = [];
    const types = inspectionTypes.map(t => t.value);
    for (let i = 0; i < 35; i++) {
      const type = types[Math.floor(Math.random() * types.length)];
      mockData.push({
        id: `INSP${1000 + i}`,
        createTime: new Date(Date.now() - Math.random() * 30 * 24 * 60 * 60 * 1000),
        type,
        remark: `这是关于${getTypeLabel(type)}的备注信息`,
        content: `${getTypeLabel(type)}报告_${i + 1}.doc`
      });
    }

    // 模拟筛选
    let filteredData = [...mockData];
    if (queryParams.type) {
      filteredData = filteredData.filter(item => item.type === queryParams.type);
    }
    if (queryParams.dateRange && queryParams.dateRange.length === 2) {
      const [start, end] = queryParams.dateRange;
      filteredData = filteredData.filter(item => {
        const time = new Date(item.createTime).getTime();
        return time >= start.getTime() && time <= end.getTime();
      });
    }

    // 模拟分页
    const start = (queryParams.page - 1) * queryParams.pageSize;
    const end = start + queryParams.pageSize;
    inspectionList.value = filteredData.slice(start, end);
    total.value = filteredData.length;
  }, 300);
};

// 计算下次运行时间
const calculateNextRunTime = (setting) => {
  if (!setting.enabled || !setting.selectedDays?.length || !setting.runTime) {
    return null;
  }

  const now = new Date();
  const runTime = new Date(setting.runTime);
  const currentDay = now.getDay();
  const currentHour = now.getHours();
  const currentMinute = now.getMinutes();

  // 找到下一个符合条件的日期
  for (let i = 0; i < 7; i++) {
    const nextDay = (currentDay + i) % 7;
    if (setting.selectedDays.includes(nextDay)) {
      const daysToAdd = i === 0 ?
        (currentHour < runTime.getHours() ||
          (currentHour === runTime.getHours() && currentMinute < runTime.getMinutes())) ? 0 : 7 : i;

      const nextDate = new Date(now);
      nextDate.setDate(now.getDate() + daysToAdd);
      nextDate.setHours(runTime.getHours(), runTime.getMinutes(), 0, 0);

      return formatDate(nextDate);
    }
  }

  return null;
};

// 处理设置变更
const handleSettingChange = (setting) => {
  if (setting.enabled) {
    ElMessage.success(`${setting.label} 已开启`);
  } else {
    ElMessage.warning(`${setting.label} 已关闭`);
  }
  // 实际项目中这里应该是调用API保存设置
};

// 获取物资列表
const fetchMaterials = () => {
  // 模拟API调用
  setTimeout(() => {
    const mockData = [];
    const types = ['gas', 'filter'];
    const statuses = ['active', 'inactive'];
    for (let i = 0; i < 25; i++) {
      const type = types[Math.floor(Math.random() * types.length)];
      mockData.push({
        id: `MAT${1000 + i}`,
        name: type === 'gas' ? `标准气体 ${i + 1}` : `颗粒物滤纸带 ${i + 1}`,
        type,
        spec: type === 'gas' ? `SO2-${Math.floor(Math.random() * 100)}ppm` : `直径${Math.floor(Math.random() * 10) + 40}mm`,
        status: statuses[Math.floor(Math.random() * statuses.length)],
        quantity: Math.floor(Math.random() * 100) + 1,
        lastUsed: Math.random() > 0.3 ? new Date(Date.now() - Math.random() * 30 * 24 * 60 * 60 * 1000) : null,
        remark: `这是关于${type === 'gas' ? '标准气体' : '颗粒物滤纸带'}的备注信息`
      });
    }

    // 模拟筛选
    let filteredData = [...mockData];
    if (materialQuery.keyword) {
      filteredData = filteredData.filter(item =>
        item.name.includes(materialQuery.keyword)
      );
    }

    // 模拟分页
    const start = (materialQuery.page - 1) * materialQuery.pageSize;
    const end = start + materialQuery.pageSize;
    materialList.value = filteredData.slice(start, end);
    materialTotal.value = filteredData.length;
  }, 300);
};

// 处理物资搜索
const handleMaterialSearch = () => {
  materialQuery.page = 1;
  fetchMaterials();
};

// 物资分页大小变化
const handleMaterialSizeChange = (size) => {
  materialQuery.pageSize = size;
  fetchMaterials();
};

// 物资当前页变化
const handleMaterialCurrentChange = (page) => {
  materialQuery.page = page;
  fetchMaterials();
};

// 显示新增物资弹窗
const showAddMaterialDialog = () => {
  materialDialog.visible = true;
  materialDialog.isEdit = false;
  materialDialog.form = {
    id: '',
    name: '',
    type: 'gas',
    spec: '',
    quantity: 1,
    status: 'active',
    remark: ''
  };
  nextTick(() => {
    materialFormRef.value?.resetFields();
  });
};

// 编辑物资
const editMaterial = (row) => {
  materialDialog.visible = true;
  materialDialog.isEdit = true;
  materialDialog.form = { ...row };
};

// 删除物资
const deleteMaterial = (id) => {
  ElMessageBox.confirm('确定要删除该物资吗?', '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    ElMessage.success('删除成功');
    fetchMaterials();
  }).catch(() => {
    ElMessage.info('已取消删除');
  });
};

// 提交物资表单
const submitMaterialForm = () => {
  materialFormRef.value.validate((valid) => {
    if (valid) {
      ElMessage.success(materialDialog.isEdit ? '编辑成功' : '新增成功');
      materialDialog.visible = false;
      fetchMaterials();
    }
  });
};

// 导出物资
const exportMaterials = () => {
  ElMessage.success('导出成功');
  // 实际项目中这里应该是调用导出API
};

// 初始化数据
onMounted(() => {
  fetchInspections();
  fetchMaterials();
});
</script>
<style scoped>
/* 自定义样式 */
:deep(.el-table .cell) {
  white-space: nowrap;
}
:deep(.el-table .cell a) {
  text-decoration: none;
}
:deep(.el-dialog__body) {
  padding: 20px;
}
:deep(.el-form-item) {
  margin-bottom: 20px;
}
</style>
