/* ECharts is bundled as a WebJar; this renderer is also embedded in HTML exports. */
window.NotebookCharts = (() => {
  const runtimeUrl = 'webjars/echarts/6.0.0/dist/echarts.min.js';
  let runtime;

  // Keep this function self-contained: export uses its source without the application UI.
  function mount(root = document) {
    root.querySelectorAll('.echarts-chart').forEach(element => {
      if(element.closest('[hidden]'))return;
      if (window.echarts.getInstanceByDom(element)) return;
      try {
        const option = JSON.parse(element.getAttribute('data-echarts'));
        // Tooltips are canvas text, so JSON labels never become arbitrary HTML.
        if (option.tooltip && typeof option.tooltip === 'object') option.tooltip.renderMode = 'richText';
        const series = Array.isArray(option.series) ? option.series : option.series ? [option.series] : [];
        series.forEach(item => { if (item.tooltip && typeof item.tooltip === 'object') item.tooltip.renderMode = 'richText'; });
        if (option.animation === undefined) option.animation = false;
        const chart = window.echarts.init(element, null, {renderer:'canvas'});
        chart.setOption(option);
        element.dataset.chartReady = 'true';
        if (typeof ResizeObserver !== 'undefined') {
          const observer = new ResizeObserver(() => {
            if (!chart.isDisposed() && element.clientWidth > 0 && element.clientHeight > 0) chart.resize();
          });
          observer.observe(element);element.chartResizeObserver = observer;
        }
      } catch (error) {
        const chart = window.echarts.getInstanceByDom(element);chart?.dispose();
        element.textContent = 'Could not render ECharts option: ' + error.message;
        element.classList.add('chart-render-error');
      }
    });
  }

  function dispose(root) {
    root.querySelectorAll('.echarts-chart').forEach(element => {
      element.chartResizeObserver?.disconnect();
      window.echarts.getInstanceByDom(element)?.dispose();
    });
  }

  async function exportAssets() {
    if (!runtime) runtime = fetch(runtimeUrl, {cache:'force-cache'}).then(async response => {
      if (!response.ok) throw new Error('Could not load the bundled ECharts runtime for export');
      return response.text();
    }).catch(error => {runtime=null;throw error;});
    return {runtime:await runtime,bootstrap:`(${mount.toString()})(document);`};
  }
  return {mount,dispose,exportAssets};
})();
