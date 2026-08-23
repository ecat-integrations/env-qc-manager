/**
 * PDF 导出 composable（G-VUE-2：records 质控结果预览与 report 报表列表共用同一导出实现）。
 * html2canvas 截图 → jsPDF A4 页面居中嵌入，多页长图缩放为单页适配。
 */
import html2canvas from 'html2canvas';
import jsPDF from 'jspdf';

/** A4 内边距（mm），略大于常见默认打印边距，便于装订与阅读 */
const A4_MARGIN_X_MM = 14;
const A4_MARGIN_Y_MM = 16;

/**
 * 将已渲染的 DOM 元素导出为 A4 PDF。
 * @param {HTMLElement} element 目标元素（须已渲染完成）
 * @param {Object} options
 * @param {string} options.fileName 保存文件名（不含扩展名）
 * @param {number} [options.scale=2] html2canvas 放大倍率（保证小字号清晰）
 * @returns {Promise<void>}
 */
export async function exportElementToPdf(element, { fileName, scale = 2 } = {}) {
  const canvas = await html2canvas(element, { scale, useCORS: true, logging: false });
  const imgData = canvas.toDataURL('image/png');
  const pdf = new jsPDF('p', 'mm', 'a4');
  const pageW = pdf.internal.pageSize.getWidth();
  const pageH = pdf.internal.pageSize.getHeight();
  const contentW = pageW - 2 * A4_MARGIN_X_MM;
  const contentH = pageH - 2 * A4_MARGIN_Y_MM;
  const imgRatio = canvas.height / canvas.width;
  let drawW = contentW;
  let drawH = drawW * imgRatio;
  if (drawH > contentH) {
    drawH = contentH;
    drawW = drawH / imgRatio;
  }
  const x = A4_MARGIN_X_MM + (contentW - drawW) / 2;
  const y = A4_MARGIN_Y_MM + (contentH - drawH) / 2;
  pdf.addImage(imgData, 'PNG', x, y, drawW, drawH);
  pdf.save(`${fileName}.pdf`);
}
