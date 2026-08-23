import { ref, onBeforeUnmount } from 'vue';

/**
 * 执行状态轮询 composable（G-VUE-3：自 records 执行详情弹窗抽出）。
 * 确定性启停：start 前/重复 start 均先清旧定时器；组件卸载自动停止，防泄漏。
 *
 * @param {Object} options
 * @param {Function} options.tick 每次轮询执行的异步任务
 * @param {Function} [options.shouldContinue] 轮询前置判断（返回 false 时本轮后停止）
 * @param {number} [options.intervalMs=5000] 轮询间隔
 */
export function useExecutionPolling({ tick, shouldContinue, intervalMs = 5000 }) {
  const timer = ref(null);

  function stop() {
    if (timer.value != null) {
      clearInterval(timer.value);
      timer.value = null;
    }
  }

  function start() {
    stop();
    timer.value = setInterval(() => {
      void (async () => {
        if (shouldContinue && !shouldContinue()) {
          stop();
          return;
        }
        await tick();
        if (shouldContinue && !shouldContinue()) {
          stop();
        }
      })();
    }, intervalMs);
  }

  onBeforeUnmount(stop);

  return { start, stop };
}
