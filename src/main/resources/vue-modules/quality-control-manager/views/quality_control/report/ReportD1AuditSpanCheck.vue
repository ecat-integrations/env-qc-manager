<!--仪器运行状况检查记录表-->
<template>
  <div class="report-d1">
    <h2 style="text-align: center;">{{ reportData.title }}</h2>
    <table class="report-table">
      <!-- 表头部分 -->
      <tbody>
      <tr>
        <td>仪器名称及编号</td>
        <td colspan="2">{{ reportData.instrument_info.instrument_name + " " + reportData.instrument_info.instrument_no }}</td>
        <td>校准日期</td>
        <td colspan="2">{{reportData.instrument_info.report_date }}</td>
      </tr>
      <tr>
        <td>标气来源及编号</td>
        <td colspan="2">{{ reportData.instrument_info.gas_source + " " + reportData.instrument_info.gas_no }}</td>
        <td>标气浓度</td>
        <td colspan="2">{{ reportData.instrument_info.gas_concentration }}</td>
      </tr>
      <tr>
        <td>使用满量程</td>
        <td colspan="5">{{ reportData.full_span }}</td>
      </tr>

      <!-- 校准点部分 -->
      <tr>
        <th rowspan="2">校准点</th>
        <th rowspan="2">取数时间</th>
        <th rowspan="2">核查浓度</th>
        <th rowspan="2">标准浓度</th>
        <th>显示值</th>
        <th>标定值</th>
      </tr>
      <tr>
        <th>响应浓度</th>
        <th>响应浓度</th>
      </tr>
      <tr v-for="(point, index) in reportData.calibration_points" :key="index">
        <td>{{ point.point_name }}</td>
        <td>{{ point.check_time }}</td>
        <td>{{ point.check_concentration }}</td>
        <td>{{ point.standard_concentration }}</td>
        <td>{{ point.check_data }}</td>
        <td>{{ point.calibration_value }}</td>
      </tr>

      <!-- 漂移部分 -->
      <tr>
        <td>跨度漂移(%)</td>
        <td colspan="5">{{reportData.span_drift_result}}</td>
      </tr>

      <!-- 关键参数部分：正常范围占 2 列 -->
      <tr>
        <th class="kp-th-name">关键参数列表</th>
        <th class="kp-th-value">检查值</th>
        <th colspan="2" class="kp-th-range">正常范围</th>
        <th colspan="2" class="kp-th-remark">处理记录</th>
      </tr>
      <tr v-for="(param, index) in reportData.key_parameters" :key="index">
        <td class="kp-cell-name">{{ param.tName }}</td>
        <td class="kp-cell-value">{{ param.tValue }}</td>
        <td colspan="2" class="kp-cell-range">{{ param.tRange }}</td>
        <td colspan="2" class="kp-cell-remark">{{ param.tRemark }}</td>
      </tr>
      <tr>
        <td></td>
        <td></td>
        <td colspan="2"></td>
        <td colspan="2"></td>
      </tr>

      <!-- 备注部分 -->
      <tr>
        <td colspan="6" class="report-remark-cell">备注：{{ reportRemarkDisplay }}</td>
      </tr>
      <!-- 填表人和复核人部分 -->
      <tr>
        <td>填表人：</td>
        <td colspan="2">{{ reportData.filer }}</td>
        <td>复核人：</td>
        <td colspan="2">{{ reportData.reviewer }}</td>
      </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
import { computed, defineProps } from 'vue';
import { formatReportRemarkDisplay } from './formatReportRemarkDisplay.js';

const props = defineProps({
  reportData: {
    type: Object,
    required: true,

    default: () => ({
      // title: '默认标题',
      // calibration_points: [],
      // 其他默认值...
    })
  }
});

const reportRemarkDisplay = computed(() => formatReportRemarkDisplay(props.reportData && props.reportData.remark));
</script>

<style scoped>
tr {
  height: 40px;
}
.report-d1 {
  font-family: Arial, sans-serif;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}

.report-remark-cell {
  text-align: left;
  white-space: pre-wrap;
  word-break: break-word;
}

.report-table {
  width: 100%;
  max-width: 100%;
  table-layout: fixed;
  border-collapse: collapse;
  margin-top: 10px;
  border: 2px solid #000;
}

.report-table .kp-th-name,
.report-table .kp-cell-name {
  width: 16%;
}

.report-table .kp-th-value,
.report-table .kp-cell-value {
  width: 14%;
}

.report-table .kp-th-range,
.report-table .kp-cell-range {
  width: 38%;
  word-break: break-word;
  white-space: normal;
  text-align: left;
}

.report-table .kp-th-remark,
.report-table .kp-cell-remark {
  width: 32%;
  word-break: break-word;
  white-space: normal;
  text-align: left;
}

.report-table th,
.report-table td {
  border: 1px solid #000;
  padding: 8px;
  text-align: center;
}

.report-table th {
  background-color: #f4f4f4;
}

/* 单元格内容垂直居中 */
.report-table td, .report-table th {
  vertical-align: middle;
}

/* 左对齐 */
.left-align {
  text-align: left;
}
</style>
