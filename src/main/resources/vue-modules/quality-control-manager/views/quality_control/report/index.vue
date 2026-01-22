<!-- ReportList.vue -->
<template>
  <div class="report-list-container">
    <!-- 查询条件区域 -->
    <el-form
      :model="searchParams"
      ref="queryRef"
      :inline="true"
      v-show="showSearch"
      class="search-bar"
      label-width="68px"
    >
      <el-form-item label="报表名称" prop="reportName" class="search-item w-160">
        <el-input v-model="searchParams.reportName" placeholder="报表名称" clearable />
      </el-form-item>
      <el-form-item label="报表类型" class="search-item w-160">
        <el-input v-model="searchParams.reportType" placeholder="报表类型" clearable />
      </el-form-item>
      <el-form-item label="标气类型" class="search-item w-160">
        <el-select
          v-model="searchParams.gasType"
          placeholder="标气类型"
          clearable
          @change="handleSearch"
        >
          <el-option
            v-for="option in gasTypeOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="仪器名称" class="search-item w-160">
        <el-input v-model="searchParams.instrumentName" placeholder="仪器名称" clearable />
      </el-form-item>
      <el-form-item label="报表日期" class="search-item w-160">
        <el-date-picker
          v-model="searchParams.reportDate"
          type="date"
          placeholder="报表生成日期"
          clearable
        />
      </el-form-item>
      <el-form-item label="仪器编号" class="search-item w-160" v-if="false">
        <el-input v-model="searchParams.instrumentNo" placeholder="仪器编号" clearable />
      </el-form-item>
      <el-form-item label="填表人" class="search-item w-160" v-if="false">
        <el-input v-model="searchParams.filer" placeholder="填表人" clearable />
      </el-form-item>
      <!-- 按钮组 -->
      <el-form-item class="search-buttons">
        <el-button type="primary" icon="Search" plain @click="handleSearch">查询</el-button>
        <el-button icon="Refresh" plain @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 报表列表区域 -->
    <el-table
      :data="tableData"
      border
      style="width: 100%; margin-top: 20px;"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="55" />
      <el-table-column prop="reportName" label="报表名称" />
      <el-table-column prop="reportDisplayType" label="报表类型" />
      <el-table-column prop="reportDate" label="报表生成日期" />
      <el-table-column prop="instrumentName" label="仪器名称" />
      <el-table-column prop="instrumentNo" label="仪器编号" />
      <el-table-column label="标气类型">
        <template #default="{ row }">
          {{ getGasLabel(row.gasType) }}
        </template>
      </el-table-column>
      <el-table-column prop="filer" label="填表人" />
      <el-table-column prop="reviewer" label="审核人" />
      <el-table-column label="操作" width="180">
        <template #default="scope">
          <el-button link @click="handleView(scope.row)">查看</el-button>
          <el-button link @click="handleEdit(scope.row)" v-if="false">编辑</el-button>
          <el-button link @click="handleDelete(scope.row.id)" v-if="false">删除</el-button>
          <el-button link @click="handleExportSingle(scope.row, 'xlsx')" v-if="false">导出Excel</el-button>
          <el-button link @click="handleExportSingle(scope.row, 'pdf')" v-if="false">导出PDF</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页区域 -->
    <pagination
      v-show="total > 0"
      :total="total"
      v-model:page="currentPage"
      v-model:limit="pageSize"
      @pagination="handlePagination"
    />

    <!-- 批量操作按钮 -->
    <div class="batch-operation" v-if="selectedRows.length > 0">
      <el-button type="primary" @click="handleBatchEdit" v-if="false">批量编辑</el-button>
      <el-button type="danger" @click="handleBatchDelete" v-if="false">批量删除</el-button>
      <el-button type="info" @click="handleBatchExport('xlsx')" v-if="false">批量导出Excel</el-button>
      <el-button type="success" @click="handleBatchExport('pdf')">批量导出PDF</el-button>
    </div>

    <!-- 查看报表弹窗 -->
    <el-dialog v-model="viewDialogVisible" title="报表详情" width="70%">
      <div ref="reportDialogContent" class="report-dialog-content">
        <component :is="currentReportComponent" :report-data="currentReportData" />
      </div>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="viewDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="handleExportCurrent('xlsx')" v-if="false">导出Excel</el-button>
          <el-button type="primary" @click="handleExportCurrent('pdf')">导出PDF</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue';
import { ElMessage, ElMessageBox, ElDialog, ElTable, ElTableColumn, ElInput, ElDatePicker, ElButton } from 'element-plus';
import FileSaver from 'file-saver';
import * as XLSX from 'xlsx';
import html2canvas from 'html2canvas';
import jsPDF from 'jspdf';
import {
  listReport,
  exportReport,
  getReportDetail,
  addReport,
  updateReport,
  deleteReport,
  batchExportReports
} from '@/api/quality_control/report';

// 引入各报表详情组件
import ReportD1 from './ReportD1AuditSpanCheck.vue';
import ReportD2 from './ReportD2ZeroSpanCheck.vue';
import ReportD3 from './ReportD3MultiCheck.vue';
import ReportD4 from './ReportD4PrecisionCheck.vue';
import ReportD5 from './ReportD5AccuracyCheck.vue';
import ReportD6 from './ReportD6ConversionCheck.vue';
import ReportD7 from './ReportD7TransferAndTrackCheck.vue';

// 模拟后端返回的报表数据
const mockTableData = [
  {
    id: 1,
    reportName: '仪器运行状况检查记录表',
    reportType: 'D.2',
    reportDate: '2025-06-26',
    instrumentName: '仪器A',
    instrumentNo: 'NO001',
    gasType: '标气1',
    filer: '张三',
    reviewer: '李四',
    component: 'ReportD2',
    reportData: {
      title: '表D.2 （ ）仪器运行状况检查/校准记录表',
      instrument_info: {
        instrument_name: '仪器A',
        instrument_no: 'NO001',
        calibration_date: '2025-06-26',
        gas_source: '标气源1',
        gas_no: 'GS001',
        gas_concentration: '100ppm',
        full_scale: '200ppm'
      },
      calibration_points: [
        {
          point_name: '零点',
          start_time: '09:00',
          end_time: '09:10',
          standard_concentration: '0ppm',
          display_value: '0ppm',
          calibration_value: '0ppm'
        },
        {
          point_name: '满量程的80%',
          start_time: '09:20',
          end_time: '09:30',
          standard_concentration: '160ppm',
          display_value: '158ppm',
          calibration_value: '160ppm'
        }
      ],
      drift_info: {
        zero_drift: '0ppm',
        span_drift: '1%'
      },
      key_parameters: [
        {
          param_name: '参数1',
          checked_value: '正常',
          normal_range: '正常范围',
          processing_record: '无'
        }
      ],
      remark: '无特殊备注',
      filer: '张三',
      reviewer: '李四'
    }
  },
  {
    id: 2,
    reportName: '仪器多点校准记录表',
    reportType: 'D.3',
    reportDate: '2025-06-26',
    instrumentName: '仪器B',
    instrumentNo: 'NO002',
    gasType: '标气2',
    filer: '张三',
    reviewer: '李四',
    component: 'ReportD3',
    reportData: {
      title: '表D.3 （ ）仪器多点校准记录表',
      instrument_info: {
        instrument_name: '仪器B',
        instrument_no: 'NO002',
        calibration_date: '2025-06-26',
        gas_source: '标气源2',
        gas_no: 'GS002',
        gas_concentration: '200ppm'
      },
      gas_concentrations_input: ['50ppm', '100ppm', '150ppm'],
      instrument_responses: ['48ppm', '98ppm', '147ppm'],
      calibration_curve: {
        formula: 'Y = aX + b',
        a: '0.98',
        b: '1',
        r: '0.999'
      },
      calibration_result: '合格',
      filer: '张三',
      reviewer: '李四'
    }
  },
  {
    id: 7,
    reportName: '仪器运行状况检查记录表',
    reportType: 'D.1',
    reportDate: '2025-06-26',
    instrumentName: '仪器A',
    instrumentNo: 'NO001',
    gasType: '标气1',
    filer: '张三',
    reviewer: '李四',
    component: 'ReportD2',
    reportData: {
      title: '表D.1 （ ）仪器运行状况检查/校准记录表',
      instrument_info: {
        instrument_name: '仪器A',
        instrument_no: 'NO001',
        calibration_date: '2025-06-26',
        gas_source: '标气源1',
        gas_no: 'GS001',
        gas_concentration: '100ppm',
        full_scale: '200ppm'
      },
      calibration_points: [
        {
          point_name: '自定义标点',
          check_time: '09:00',
          check_concentration: '0ppm',
          standard_concentration: '0ppm',
          check_data: '0ppm',
          calibration_value: '0ppm'
        },
        {
          point_name: '自定义标点',
          check_time: '09:20',
          check_concentration: '160ppm',
          standard_concentration: '160ppm',
          check_data: '158ppm',
          calibration_value: '160ppm'
        }
      ],
      drift_info: {
        zero_drift: '0ppm',
        span_drift: '1%'
      },
      key_parameters: [
        {
          param_name: '参数1',
          checked_value: '正常',
          normal_range: '正常范围',
          processing_record: '无'
        }
      ],
      remark: '无特殊备注',
      filer: '张三',
      reviewer: '李四'
    }
  },
  // 继续补充其他报表数据...
];

const tableData = ref(mockTableData);
const loading = ref(false);
const showSearch = ref(true);
const searchParams = ref({
  reportName: '',
  reportType: '',
  reportDate: null,
  instrumentName: '',
  instrumentNo: '',
  gasType: '',
  filer: '',
  reviewer: ''
});
const reportList = ref([]);
const selectedIds = ref([]);
const selectedRows = ref([]);
const viewDialogVisible = ref(false);
const currentReportComponent = ref(null);
const currentReportData = ref({});
const reportDialogContent = ref(null);
// 分页相关状态
const currentPage = ref(1);
const pageSize = ref(10); // 每页显示数量
const total = ref(0); // 总数据量

onMounted(() => {
  // 实际应调用接口替换 mockTableData，如 fetchTableData();
  fetchTableData();
});

const fetchTableData = async () => {
  try {
    loading.value = true;

    const params = {
      ...searchParams.value,
      pageNum: currentPage.value,
      pageSize: pageSize.value
    };

    const response = await listReport(params);

    if (response.code === 200) {
      console.log('查询成功:', response);
      reportList.value = response.rows || [];
      tableData.value = reportList.value.filter(item => {
        return (
          item.reportName.includes(searchParams.value.reportName || '') &&
          item.reportType.includes(searchParams.value.reportType || '') &&
          (!searchParams.value.reportDate || item.reportDate === searchParams.value.reportDate) &&
          item.instrumentName.includes(searchParams.value.instrumentName || '') &&
          item.instrumentNo.includes(searchParams.value.instrumentNo || '') &&
          item.gasType.includes(searchParams.value.gasType || '') &&
          item.filer.includes(searchParams.value.filer || '')
        );
      });

      total.value = response.total || tableData.value.length;
    } else {
      ElMessage.error(response.message || '查询失败');
    }
  } catch (error) {
    console.error('加载报表列表异常:', error);
    ElMessage.error('加载数据失败，请稍后重试');
  } finally {
    loading.value = false;
  }
};


 // 新增
const showAddDialog = ref(false);
const newReportForm = ref({});

const handleAdd = () => {
  showAddDialog.value = true;
};

const submitAddForm = () => {
  addReport(newReportForm.value)
    .then(() => {
      ElMessage.success('新增成功');
      showAddDialog.value = false;
      loadReportList();
    })
    .catch(() => {
      ElMessage.error('新增失败');
    });
};

// 编辑
const showEditDialog = ref(false);
const editReportForm = ref({});

const handleEdit = (row) => {
  editReportForm.value = { ...row };
  showEditDialog.value = true;
};

const submitEditForm = () => {
  updateReport(editReportForm.value)
    .then(() => {
      ElMessage.success('更新成功');
      showEditDialog.value = false;
      loadReportList();
    })
    .catch(() => {
      ElMessage.error('更新失败');
    });
};

// 导出
const handleExport = (format = 'xlsx') => {
  if (selectedIds.value.length === 0) {
    ElMessage.warning('请选择要导出的记录');
    return;
  }

  batchExportReports(selectedIds.value, format).then(response => {
    const url = window.URL.createObjectURL(new Blob([response]));
    const link = document.createElement('a');
    link.href = url;
    link.setAttribute('download', `reports.${format}`);
    document.body.appendChild(link);
    link.click();
    link.remove();
  }).catch(() => {
    ElMessage.error('导出失败');
  });
};

// 分页
const handlePagination = () => {
  fetchTableData();
};

// 重置
function resetQuery() {
  proxy.resetForm("queryRef");
  handleSearch();
}

// 查询
const handleSearch = () => {
  fetchTableData();
};

const handleSelectionChange = (val) => {
  selectedRows.value = val;
};

const handleView = (row) => {
  switch (row.component) {
    case 'ReportD1':
      currentReportComponent.value = ReportD1;
      break;
    case 'ReportD2':
      currentReportComponent.value = ReportD2;
      break;
    case 'ReportD3':
      currentReportComponent.value = ReportD3;
      break;
    case 'ReportD4':
      currentReportComponent.value = ReportD4;
      break;
    case 'ReportD5':
      currentReportComponent.value = ReportD5;
      break;
    case 'ReportD6':
      currentReportComponent.value = ReportD6;
      break;
    case 'ReportD7':
      currentReportComponent.value = ReportD7;
      break;
    default:
      return;
  }
  currentReportData.value = row.reportData;
  viewDialogVisible.value = true;
};

// const handleEdit = (row) => {
//   ElMessage.warning('编辑功能待实现，可依据 row 数据跳转编辑页');
// };

const handleDelete = (id) => {
  ElMessageBox.confirm(
    '此操作将永久删除该报表, 是否继续?',
    '提示',
    {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }
  ).then(() => {
    tableData.value = tableData.value.filter(item => item.id!== id);
    deleteReport(id)
    ElMessage.success('删除成功!');
  }).catch(() => {
    ElMessage.info('已取消删除');
  });
};

const handleBatchEdit = () => {
  ElMessage.warning('批量编辑功能待实现，可依据 selectedRows 处理');
};

const handleBatchDelete = () => {
  ElMessageBox.confirm(
    '此操作将永久删除选中的报表, 是否继续?',
    '提示',
    {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }
  ).then(() => {
    const ids = selectedRows.value.map(item => item.id);
    tableData.value = tableData.value.filter(item =>!ids.includes(item.id));
    ElMessage.success('批量删除成功!');
  }).catch(() => {
    ElMessage.info('已取消删除');
  });
};

// 导出为 Excel
const exportToExcel = (data, reportName) => {
  const worksheet = XLSX.utils.json_to_sheet([data]);
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(workbook, worksheet, 'Sheet1');
  const excelBuffer = XLSX.write(workbook, { bookType: 'xlsx', type: 'array' });
  const blob = new Blob([excelBuffer], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=UTF-8' });
  FileSaver.saveAs(blob, `${reportName}_${new Date().getTime()}.xlsx`);
};

// 导出为 PDF
const exportToPDF = async (element, reportName) => {
  const canvas = await html2canvas(element);
  const imgData = canvas.toDataURL('image/png');
  const pdf = new jsPDF('p', 'mm', 'a4');
  const width = pdf.internal.pageSize.getWidth();
  const height = pdf.internal.pageSize.getHeight();
  pdf.addImage(imgData, 'PNG', 0, 0, width, height * (canvas.height / canvas.width));
  pdf.save(`${reportName}_${new Date().getTime()}.pdf`);
};

const handleExportSingle = (row, format) => {
  if (format === 'xlsx') {
    exportToExcel(row.reportData, row.reportName);
  } else if (format === 'pdf') {
    const element = document.createElement('div');
    // 模拟渲染报表内容用于导出，实际可优化直接用组件渲染的 DOM
    // 这里简单处理，若复杂可复用组件渲染逻辑
    switch (row.component) {
      case 'ReportD2':
        element.innerHTML = `
          <h2>${row.reportData.title}</h2>
          <!-- 这里复制 ReportD2.vue 的表格渲染逻辑生成 HTML -->
        `;
        break;
      // 其他报表类似...
    }
    exportToPDF(element, row.reportName);
  }
};

const handleBatchExport = (format) => {
  selectedRows.value.forEach(row => {
    if (format === 'xlsx') {
      exportToExcel(row.reportData, row.reportName);
    } else if (format === 'pdf') {
      const element = document.createElement('div');
      // 同上，简单模拟渲染报表内容
      switch (row.component) {
        case 'ReportD2':
          element.innerHTML = `
            <h2>${row.reportData.title}</h2>
            <!-- 复制 ReportD2.vue 表格逻辑 -->
          `;
          break;
        // 其他报表...
      }
      exportToPDF(element, row.reportName);
    }
  });
  ElMessage.success(`批量导出${format.toUpperCase()}完成`);
};

const handleExportCurrent = (format) => {
  if (format === 'xlsx') {
    exportToExcel(currentReportData.value, '当前报表');
  } else if (format === 'pdf') {
    exportToPDF(reportDialogContent.value, '当前报表');
  }
};

const gasTypeOptions = [
  { label: 'SO₂', value: '1' },
  { label: 'NO₂', value: '2' },
  { label: 'O₃', value: '3' },
  { label: 'CO', value: '4' }
];

const getGasLabel = (value) => {
  const matchedOption = gasTypeOptions.find(option => option.value === value);
  return matchedOption ? matchedOption.label : '';
};

</script>

<style scoped>
.report-list-container {
  padding: 20px;
}
.search-bar {
  display: flex;
  flex-wrap: wrap; /* 自动换行 */
  align-items: flex-start;
  gap: 0px;  /* label间距 */
  padding: 0; /* 移除内边距 */
  margin-bottom: 10px;
  background: none; /* 移除背景 */
}

.search-item {
  margin-bottom: 0 !important;
  flex: 0 0 auto; /* 默认不伸缩 */
}

.search-item .el-input,
.search-item .el-date-picker,
.search-item .el-select {
  width: 100%;
}

.search-buttons {
  display: flex;
  gap: 10px;
  flex: 0 0 auto;
  margin-left: 5px;
}

/* 自定义宽度类 */
.w-120 { min-width: 120px; width: 120px; }
.w-140 { min-width: 140px; width: 140px; }
.w-160 { min-width: 160px; width: 160px; }
.w-180 { min-width: 180px; width: 180px; }
.w-200 { min-width: 200px; width: 200px; }
.w-220 { min-width: 220px; width: 220px; }
.w-240 { min-width: 240px; width: 240px; }
.w-300 { min-width: 300px; width: 300px; }

/* 响应式调整 */
@media (max-width: 1200px) {
  .search-item {
    min-width: 120px !important;
  }
}

@media (max-width: 992px) {
  .search-item {
    min-width: 100px !important;
  }
}

@media (max-width: 768px) {
  .search-buttons {
    margin-left: 0;
    margin-top: 10px;
    justify-content: flex-end;
    width: 100%;
  }
}
.batch-operation {
  margin-top: 10px;
}
.dialog-footer {
  text-align: right;
}
.report-dialog-content {
  padding: 10px;
}
</style>
