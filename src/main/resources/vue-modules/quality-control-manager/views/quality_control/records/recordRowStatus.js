/**
 * 质控记录行级状态判定（纯函数，G-VUE 拆分自主 index.vue）。
 * 列表操作列与执行详情弹窗共用同一套判定，避免两处漂移。
 */

/** 执行状态 → 状态色 CSS 类（0 等待/1 执行中/2 成功/3 失败/4 手动中止） */
export function getExecutionLogStatusClass(executionStatus) {
  const status = String(executionStatus);
  switch (status) {
    case '0':
      return 'status-waiting';
    case '1':
      return 'status-running';
    case '2':
      return 'status-success';
    case '3':
      return 'status-failed';
    case '4':
      return 'status-aborted';
    default:
      return 'status-default';
  }
}

/** 用户手动中止信号：结果评价或执行日志错误信息中含手动终止标记 */
export function rawUserManualAbortSignal(row) {
  if (!row) {
    return false;
  }
  const ev = String(row.resultEvaluation || '');
  if (/手动终止|流程被用户手动终止|用户手动终止|已中止/.test(ev)) {
    return true;
  }
  const log = row.executionLog;
  if (!log || typeof log !== 'string') {
    return false;
  }
  const t = log.trim();
  if (!t.startsWith('{')) {
    return false;
  }
  try {
    const p = JSON.parse(t);
    const em = String((p.statusMap && p.statusMap.errorMessage) || p.errorMessage || '');
    return /手动终止|流程被用户手动终止/.test(em);
  } catch (e) {
    return false;
  }
}

/** 中止中(4) 或 已落库为失败(3)但执行日志/评价表明为用户手动中止 */
export function isQcManualAbortEndRow(row) {
  if (!row) {
    return false;
  }
  const s = String(row.executionStatus);
  if (s === '4') {
    return true;
  }
  return s === '3' && rawUserManualAbortSignal(row);
}

/** 等待中 / 执行中：可中止（与历史逻辑一致：非成功/失败/状态4） */
export function canStopQualityControl(row) {
  if (!row) {
    return false;
  }
  const s = String(row.executionStatus);
  return s !== '2' && s !== '3' && s !== '4';
}

/** 执行失败且非用户手动中止：第二格透明占位，与成功行对齐 */
export function showQcResultGhostPlaceholder(row) {
  if (!row) {
    return false;
  }
  return String(row.executionStatus) === '3' && !isQcManualAbortEndRow(row);
}

/** 执行中：第二格固定为「中止质控」（与「阶段详情」同一行） */
export function canShowAbortInOpColumn(row) {
  if (!row) {
    return false;
  }
  return String(row.executionStatus) === '1';
}

/** 阶段详情按钮配色：手动中止（含终态失败但带手动中止标记）用橘黄 */
export function opPhaseDetailStatusClass(row) {
  if (isQcManualAbortEndRow(row)) {
    return 'status-aborted';
  }
  return getExecutionLogStatusClass(row.executionStatus);
}

/** 成功结束：可打开质控结果（报告预览） */
export function canShowQcResultPreview(row) {
  if (!row) {
    return false;
  }
  return String(row.executionStatus) === '2';
}

/** 详情弹窗内轮询：等待中 / 执行中 / 中止中 需拉取阶段与最新 execution_log */
export function shouldPollExecutionDetailRow(row) {
  if (!row) {
    return false;
  }
  const rs = String(row.executionStatus);
  return rs === '0' || rs === '1' || rs === '4';
}

/** 列表/详情结束时间展示：手动中止等新数据以 end_time 为准；历史缺省可回退 update_time */
export function displayRecordEndTime(row) {
  if (!row) {
    return '';
  }
  if (row.endTime != null && String(row.endTime).trim() !== '') {
    return row.endTime;
  }
  if (isQcManualAbortEndRow(row) && row.updateTime) {
    return row.updateTime;
  }
  return '';
}

/** 阶段预估时长（秒）→ 「1时2分3秒」可读格式 */
export function formatEstimatedSeconds(sec) {
  const s = Number(sec);
  if (!Number.isFinite(s) || s < 0) {
    return '—';
  }
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = Math.floor(s % 60);
  const parts = [];
  if (h > 0) {
    parts.push(`${h}小时`);
  }
  if (m > 0) {
    parts.push(`${m}分钟`);
  }
  if (r > 0 || parts.length === 0) {
    parts.push(`${r}秒`);
  }
  return parts.join('');
}
