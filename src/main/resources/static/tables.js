/* Bundled Grid.js rendering, shared with standalone HTML exports. */
window.NotebookTables = (() => {
  const base = '/webjars/gridjs/6.2.0/dist/';
  let assets;

  function mount(root = document) {
    root.querySelectorAll('.grid-table').forEach(element => {
      if(element.closest('[hidden]'))return;
      if (element.gridInstance) return;
      try {
        const {title, ...option} = JSON.parse(element.getAttribute('data-grid'));
        if (!Array.isArray(option.columns) || !Array.isArray(option.data)) throw new Error('Columns and data must be arrays');
        // Only local JSON data and plain text cells are supported.
        delete option.server;delete option.from;
        const table = new window.gridjs.Grid(option);
        element.gridInstance = table;
        const ready = () => {
          const body=element.querySelector('.gridjs-tbody');
          const rows=body?[...body.querySelectorAll('tr')]:[];
          const hasData=option.data.length===0?rows.length>0:rows.length>0&&rows.every(row=>row.querySelectorAll('td').length===option.columns.length&&!row.querySelector('td[colspan]'));
          if(hasData&&!element.querySelector('.gridjs-loading,.gridjs-loading-bar')) {
            element.dataset.tableReady='true';element.gridReadyObserver?.disconnect();
          }
        };
        element.gridReadyObserver=new MutationObserver(ready);
        element.gridReadyObserver.observe(element,{childList:true,subtree:true});
        table.render(element);
        ready();
      } catch (error) {
        element.gridReadyObserver?.disconnect();
        element.gridInstance?.destroy();element.gridInstance=null;
        element.textContent = 'Could not render Grid.js table: ' + error.message;
        element.classList.add('table-render-error');
      }
    });
  }

  function dispose(root) {
    root.querySelectorAll('.grid-table').forEach(element => {
      element.gridReadyObserver?.disconnect();
      element.gridInstance?.destroy();element.gridInstance=null;
    });
  }

  async function exportAssets() {
    if (!assets) assets = Promise.all(['gridjs.umd.js','theme/mermaid.min.css'].map(async file => {
      const response=await fetch(base+file,{cache:'force-cache'});
      if(!response.ok)throw new Error('Could not load bundled Grid.js export assets');return response.text();
    })).catch(error=>{assets=null;throw error;});
    const [runtime,css]=await assets;
    return {gridRuntime:runtime,gridCss:css,gridBootstrap:`(${mount.toString()})(document);`};
  }
  return {mount,dispose,exportAssets};
})();
