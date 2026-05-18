/**
 * 报表「备注」列展示：避免整段 execution_log JSON；若为 JSON 形态则提示用户看阶段详情。
 * @param {unknown} raw
 * @returns {string}
 */
export function formatReportRemarkDisplay(raw) {
  if (raw == null) {
    return '';
  }
  const s = String(raw).trim();
  if (!s) {
    return '';
  }
  if ((s.startsWith('{') && s.endsWith('}')) || (s.startsWith('[') && s.endsWith(']'))) {
    return '不合格原因请通过质控记录「阶段详情」查看执行日志。';
  }
  return s;
}
