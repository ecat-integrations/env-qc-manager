<!--仪器多点校准记录表-->
<template>
  <div class="report-d3">
    <div v-if="info.qc_stamp" class="qc-stamp" :class="'qc-stamp--' + info.qc_stamp">
      {{ info.qc_stamp === 'pass' ? '合格' : '不合格' }}
    </div>
    <h2 style="text-align: center;">{{ reportData.title }}</h2>
    <table class="report-table">
      <tbody>
      <tr>
        <td>仪器名称及编号</td>
        <td :colspan="instrumentMetaColspan">{{ info.instrument_name }} {{ info.instrument_no }}</td>
        <td>校准日期</td>
        <td colspan="2">{{ info.report_date }}</td>
      </tr>
      <tr>
        <td>标气来源及编号</td>
        <td :colspan="instrumentMetaColspan">{{ info.gas_source }} {{ info.gas_no }}</td>
        <td>标气浓度</td>
        <td colspan="2">{{ info.gas_concentration }}</td>
      </tr>

      <tr>
        <th>通入仪器标气浓度</th>
        <td v-for="(concentration, index) in (reportData.gas_concentrations_input || [])" :key="'c-' + index">
          {{ concentration }}
        </td>
      </tr>
      <tr>
        <th>仪器响应值</th>
        <td v-for="(instrument_response, index) in (reportData.instrument_responses || [])" :key="'r-' + index">
          {{ instrument_response }}
        </td>
      </tr>

      <tr>
        <td>校准曲线：</td>
        <td :colspan="totalCols - 1">
          公式：{{ reportData.calibration_curve.formula }}<br>
          a值：{{ reportData.calibration_curve.a }}<br>
          b值：{{ reportData.calibration_curve.b }}<br>
          相关系数r：{{ reportData.calibration_curve.r }}
        </td>
      </tr>

      <tr>
        <td>校准结果：</td>
        <td :colspan="totalCols - 1">{{ reportData.calibration_result }}</td>
      </tr>

      <tr>
        <td :colspan="totalCols">备注：{{ remarkDisplay }}</td>
      </tr>

      <tr>
        <td>填表人：</td>
        <td colspan="2">{{ reportData.filer }}</td>
        <td>复核人：</td>
        <td :colspan="Math.max(1, totalCols - 4)">{{ reportData.reviewer }}</td>
      </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
import { defineProps, computed } from 'vue';
import { formatReportRemarkDisplay } from './formatReportRemarkDisplay.js';

const props = defineProps({
  reportData: {
    type: Object,
    required: true
  }
});

const info = computed(() => props.reportData.instrument_info || {});

const remarkDisplay = computed(() => formatReportRemarkDisplay(props.reportData && props.reportData.remark));

const totalCols = computed(() => {
  const n = Number(props.reportData.table_total_column_count);
  if (Number.isFinite(n) && n > 0) {
    return n;
  }
  return 7;
});

const instrumentMetaColspan = computed(() => {
  const n = Number(props.reportData.instrument_meta_colspan);
  if (Number.isFinite(n) && n > 0) {
    return n;
  }
  return Math.max(1, totalCols.value - 4);
});
</script>

<style scoped>
.report-d3 {
  font-family: Arial, sans-serif;
  position: relative;
}
.qc-stamp {
  position: absolute;
  right: 18px;
  top: 10px;
  width: 86px;
  height: 86px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  letter-spacing: 2px;
  transform: rotate(-12deg);
  opacity: 0.92;
  pointer-events: none;
  border: 4px solid;
  font-size: 22px;
}
.qc-stamp--pass {
  color: #0a7a32;
  border-color: rgba(10, 122, 50, 0.55);
  background: rgba(103, 194, 58, 0.12);
}
.qc-stamp--fail {
  color: #c0392b;
  border-color: rgba(192, 57, 43, 0.55);
  background: rgba(245, 108, 108, 0.12);
}
.report-table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 10px;
  border: 2px solid #000;
}
.report-table th,
.report-table td {
  border: 1px solid #000;
  padding: 8px;
  text-align: center;
  vertical-align: middle;
}
.report-table th {
  background-color: #f4f4f4;
}
.report-table td:first-child,
.report-table th:first-child {
  width: 20%;
}
</style>
