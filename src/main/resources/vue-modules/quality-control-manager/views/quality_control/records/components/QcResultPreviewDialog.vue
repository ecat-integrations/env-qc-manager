<template>
  <el-dialog
    v-model="visible"
    width="75%"
    append-to-body
    destroy-on-close
    class="qc-result-dialog"
    @closed="onClosed"
  >
    <template #header>
      <div class="qc-result-header">
        <span class="qc-result-title">质控结果</span>
        <el-button
          v-if="downloadId"
          type="primary"
          link
          @click="handleExportPdf"
        >导出 PDF</el-button>
      </div>
    </template>
    <div v-loading="loading" class="qc-result-dialog-body">
      <div ref="pdfRoot" class="qc-result-pdf-root">
        <component
          v-if="reportComponent"
          :is="reportComponent"
          :report-data="reportData"
        />
      </div>
    </div>
  </el-dialog>
</template>

<script setup name="QcResultPreviewDialog">
import { getCurrentInstance, nextTick, ref } from 'vue';
import { getReportPreview } from '@/api/quality_control/records';
import { exportElementToPdf } from '../../composables/usePdfExport';
import ReportD1 from '../../report/ReportD1AuditSpanCheck.vue';
import ReportD2 from '../../report/ReportD2ZeroSpanCheck.vue';
import ReportD3 from '../../report/ReportD3MultiCheck.vue';
import ReportD4 from '../../report/ReportD4PrecisionCheck.vue';
import ReportD5 from '../../report/ReportD5AccuracyCheck.vue';
import ReportD6 from '../../report/ReportD6ConversionCheck.vue';
import ReportD7 from '../../report/ReportD7TransferAndTrackCheck.vue';

const { proxy } = getCurrentInstance();

const visible = ref(false);
const loading = ref(false);
const reportComponent = ref(null);
const reportData = ref({});
const pdfRoot = ref(null);
const downloadId = ref(null);

/** 七类报告预览组件映射（ReportD1~D7，与后端 report.component 契约一致） */
function resolveReportComponent(componentName) {
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

function onClosed() {
  reportComponent.value = null;
  reportData.value = {};
  loading.value = false;
  downloadId.value = null;
}

async function handleExportPdf() {
  const id = downloadId.value;
  if (!id) {
    return;
  }
  const el = pdfRoot.value;
  if (!el || !reportComponent.value) {
    proxy.$modal.msgWarning('请等待报表渲染完成后再导出');
    return;
  }
  try {
    await nextTick();
    await exportElementToPdf(el, { fileName: `qc_report_preview_${id}` });
    proxy.$modal.msgSuccess('PDF 已导出');
  } catch (e) {
    proxy.$modal.msgError('导出 PDF 失败，请稍后重试');
  }
}

/** 打开弹窗并加载单条成功记录的报告预览（与定时任务报表生成同源） */
function open(row) {
  visible.value = true;
  loading.value = true;
  reportComponent.value = null;
  reportData.value = {};
  downloadId.value = row.id;
  getReportPreview(row.id).then((response) => {
    loading.value = false;
    if (response.code !== 200) {
      proxy.$modal.msgError(response.msg || '加载失败');
      return;
    }
    const payload = response.data || {};
    const comp = resolveReportComponent(payload.component);
    if (!comp) {
      proxy.$modal.msgError('不支持的报表组件: ' + (payload.component || '(空)'));
      return;
    }
    reportComponent.value = comp;
    reportData.value = payload.reportData || {};
  }).catch(() => {
    loading.value = false;
    proxy.$modal.msgError('加载质控结果失败');
  });
}

defineExpose({ open });
</script>

<style>
.qc-result-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding-right: 28px;
  box-sizing: border-box;
}

.qc-result-title {
  font-weight: 600;
}

.qc-result-dialog-body {
  min-height: 200px;
}

.qc-result-pdf-root {
  position: relative;
  background: #fff;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}

.qc-result-pdf-root > div {
  width: 100% !important;
  max-width: 100% !important;
  box-sizing: border-box;
}
</style>
