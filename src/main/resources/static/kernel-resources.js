/* Background JVM samples stay separate from cell execution and its output stream. */
window.KernelResources = (() => {
  const samples=new Map();
  let timer=null,polling=false,lastPoll=0;
  const visible=()=>!document.hidden&&!$('#sidebar').hidden&&!$('#running-panel').hidden;
  const bytes=value=>value<1073741824?`${(value/1048576).toFixed(0)} MiB`:`${(value/1073741824).toFixed(1)} GiB`;
  function paint(n) {
    const label=$(`[data-kernel-resources="${n.uid}"]`);if(!label)return;
    label.hidden=!n.kernel||!!n.kernelStarting;
    const sample=samples.get(n.kernel);
    if(!sample?.available||Date.now()-sample.sampledAt>10000){label.textContent='Resources unavailable';label.title='Resource samples are temporarily unavailable.';return;}
    const cpu=sample.cpuPercent==null?'…':`${sample.cpuPercent.toFixed(1)}%`;
    label.textContent=`Heap ${bytes(sample.heapUsedBytes)} / ${sample.heapMaxBytes>0?bytes(sample.heapMaxBytes):'—'} · CPU ${cpu} · PID ${sample.pid}`;
    label.title=`JVM heap used / maximum; committed: ${bytes(sample.heapCommittedBytes)}. CPU: 100% equals one fully used core and can exceed 100% with multiple threads. PID: ${sample.pid}. Updated every 2 seconds.`;
  }
  function refresh() {
    const ids=new Set(state.notebooks.map(n=>n.kernel).filter(Boolean));
    for(const id of samples.keys())if(!ids.has(id))samples.delete(id);
    state.notebooks.forEach(paint);
    if(!visible()||!ids.size||timer!==null||polling)return;
    timer=setTimeout(poll,Math.max(0,2000-(Date.now()-lastPoll)));
  }
  async function poll() {
    timer=null;
    if(!visible())return;
    polling=true;lastPoll=Date.now();
    try {
      await Promise.all(state.notebooks.filter(n=>n.kernel&&!n.kernelStarting).map(async n=>{
        const id=n.kernel;
        try {
          const sample=await api(`/kernels/${id}/resources`,'GET',undefined,{cache:'no-store',signal:AbortSignal.timeout(3000)});
          if(state.notebooks.includes(n)&&n.kernel===id)samples.set(id,sample);
        } catch(_) { if(n.kernel===id)samples.delete(id); }
        if(state.notebooks.includes(n))paint(n);
      }));
    } finally {polling=false;refresh();}
  }
  document.addEventListener('visibilitychange',refresh);
  refresh();
  return {refresh};
})();
