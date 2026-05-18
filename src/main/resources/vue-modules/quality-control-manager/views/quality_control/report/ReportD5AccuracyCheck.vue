<!--仪器准确度审核记录表-->
<template>
  <div class="report-d5">
    <div v-if="info.qc_stamp" class="qc-stamp" :class="'qc-stamp--' + info.qc_stamp">
      {{ info.qc_stamp === 'pass' ? '合格' : '不合格' }}
    </div>
    <h2 style="text-align: center;">{{ reportData.title }}</h2>
    <table class="report-table">
      <tbody>
      <tr>
        <td>仪器名称：{{ info.instrument_name }}</td>
        <td>仪器编号：{{ info.instrument_no }}</td>
        <td>审核日期：{{ info.report_date }}</td>
      </tr>
      <tr>
        <td>标气来源：{{ info.gas_source }}</td>
        <td>标气编号：{{ info.gas_no }}</td>
        <td>标气浓度：{{ info.gas_concentration }}</td>
      </tr>
      <tr>
        <th>通入仪器标气浓度</th>
        <th colspan="2">仪器响应值</th>
      </tr>
      <tr v-for="(concentration, index) in (reportData.gas_concentrations_input || [])" :key="index">
        <td>{{ concentration }}</td>
        <td colspan="2">{{ (reportData.instrument_responses || [])[index] || '-' }}</td>
      </tr>
      <tr>
        <td colspan="3">平均相对误差：{{ reportData.average_relative_error }}</td>
      </tr>
      <tr>
        <td>校准曲线：</td>
        <td colspan="2">
          公式：{{ reportData.calibration_curve.formula }}<br>
          a值：{{ reportData.calibration_curve.a }}<br>
          b值：{{ reportData.calibration_curve.b }}<br>
          相关系数r：{{ reportData.calibration_curve.r }}
        </td>
      </tr>
      <tr>
        <td colspan="3">审核结果：{{ reportData.audit_result }}</td>
      </tr>
      <tr>
        <td colspan="3">备注：{{ remarkDisplay }}</td>
      </tr>
      <tr>
        <td>填表人</td>
        <td colspan="2">{{ reportData.filer }}</td>
      </tr>
      <tr>
        <td>复核人</td>
        <td colspan="2">{{ reportData.reviewer }}</td>
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
</script>

<style scoped>
.report-d5 {
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
}
.report-table th,
.report-table td {
  border: 1px solid #000;
  padding: 8px;
  text-align: left;
}
.report-table th {
  background-color: #f4f4f4;
}
</style>
