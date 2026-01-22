<!--仪器多点校准记录表-->
<template>
  <div class="report-d3">
    <h2 style="text-align: center;">{{ reportData.title }}</h2>
    <table class="report-table">
      <!-- 表头部分 -->
      <tbody>
      <tr>
        <td>仪器名称及编号</td>
        <td colspan="3">{{ reportData.instrument_info.instrument_name + ' ' + reportData.instrument_info.instrument_no }}</td>
        <td>校准日期</td>
        <td colspan="2">{{reportData.instrument_info.report_date }}</td>
      </tr>
      <tr>
        <td>标气来源及编号</td>
        <td colspan="3">{{ reportData.instrument_info.gas_source + ' ' + reportData.instrument_info.gas_no }}</td>
        <td>标气浓度</td>
        <td colspan="2">{{ reportData.instrument_info.gas_concentration }}</td>
      </tr>

      <!-- 通入仪器标气浓度和仪器响应值部分 -->
      <tr>
        <th>通入仪器标气浓度</th>
        <td v-for="(concentration, index) in reportData.gas_concentrations_input" :key="index">
          {{ concentration }}
        </td>
      </tr>
      <tr>
        <th>仪器响应值</th>
          <td v-for="(instrument_response, index) in reportData.instrument_responses" :key="index">
            {{ instrument_response }}
          </td>
      </tr>

      <!-- 校准曲线部分 -->
      <tr>
        <td>校准曲线：</td>
        <td colspan="6">
          公式：{{ reportData.calibration_curve.formula }}<br>
          a值：{{ reportData.calibration_curve.a }}<br>
          b值：{{ reportData.calibration_curve.b }}<br>
          相关系数r：{{ reportData.calibration_curve.r }}
        </td>
      </tr>

      <!-- 校准结果部分 -->
      <tr>
        <td>校准结果：</td>
        <td colspan="6">{{ reportData.calibration_result }}</td>
      </tr>

      <!-- 备注部分 -->
      <tr>
        <td colspan="7">备注：{{ reportData.remark }}</td>
      </tr>

      <!-- 填表人和复核人部分 -->
      <tr>
        <td>填表人：</td>
        <td>{{ reportData.filer }}</td>
        <td>复核人：</td>
        <td>{{ reportData.reviewer }}</td>
      </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
import { defineProps } from 'vue';

const props = defineProps({
  reportData: {
    type: Object,
    required: true
  }
});
</script>

<style scoped>
.report-d3 {
  font-family: Arial, sans-serif;
}
.report-table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 10px;
}
.report-table th,
.report-table td {
  border: 1px solid #ccc;
  padding: 8px;
  text-align: center; /* 内容居中 */
}
.report-table th {
  background-color: #f4f4f4;
}
/* 调整特定单元格的宽度 */
.report-table td:first-child,
.report-table th:first-child {
  width: 20%;
}

/* 单元格内容垂直居中 */
.report-table td, .report-table th {
  vertical-align: middle;
}

/* 调整边框样式 */
.report-table {
  border: 2px solid #000; /* 外边框加粗 */
}
.report-table th, .report-table td {
  border: 1px solid #000; /* 内边框细化 */
}

/* 设置表格宽度和高度 */
.report-table {
  width: 100%; /* 或者指定具体的宽度，如 800px */
  max-width: 100%;
}
</style>
