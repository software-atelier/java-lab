const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const script=fs.readFileSync(path.join(__dirname,'../../main/resources/static/kernel-resources.js'),'utf8');
function harness(){
  const nodes=new Map(),timers=[],calls=[];
  const n={uid:'one',kernel:'old'},state={notebooks:[n]};
  const element=selector=>{if(!nodes.has(selector))nodes.set(selector,{hidden:false,textContent:'',title:''});return nodes.get(selector);};
  const context={window:{},state,$:element,document:{hidden:false,addEventListener(){}},Date,AbortSignal,
    setTimeout:(fn,delay)=>{timers.push({fn,delay});return timers.length;},
    api:async url=>{calls.push(url);return {available:true,pid:1234,sampledAt:Date.now(),heapUsedBytes:32*1048576,heapCommittedBytes:64*1048576,heapMaxBytes:1024*1048576,cpuPercent:125.5};}};
  element('#running-panel').hidden=true;
  vm.createContext(context);vm.runInContext(script,context);
  return {context,n,state,element,timers,calls,poll:async()=>{await timers.shift().fn();}};
}
test('resource polling only runs for visible kernels and renders heap, CPU and PID as text',async()=>{
  const h=harness();assert.equal(h.timers.length,0);
  h.element('#running-panel').hidden=false;h.context.window.KernelResources.refresh();
  assert.equal(h.timers.length,1);await h.poll();
  assert.deepEqual(h.calls,['/kernels/old/resources']);
  const label=h.element('[data-kernel-resources="one"]');
  assert.equal(label.textContent,'Heap 32 MiB / 1.0 GiB · CPU 125.5% · PID 1234');
  assert.match(label.title,/committed: 64 MiB/);
  assert.equal(h.timers.length,1);
  h.element('#running-panel').hidden=true;await h.poll();assert.equal(h.timers.length,0);
});
test('late samples cannot overwrite a restarted or closed kernel',async()=>{
  const h=harness();let ready;
  h.context.api=()=>new Promise(resolve=>{ready=resolve;});
  h.element('#running-panel').hidden=false;h.context.window.KernelResources.refresh();
  const poll=h.poll();h.n.kernel='new';
  ready({available:true,pid:999,sampledAt:Date.now(),heapUsedBytes:1,heapMaxBytes:10,cpuPercent:50});await poll;
  assert.doesNotMatch(h.element('[data-kernel-resources="one"]').textContent,/999/);
  h.n.kernel=null;h.context.window.KernelResources.refresh();assert.equal(h.element('[data-kernel-resources="one"]').hidden,true);
});
test('failed samples show an unavailable state without interrupting polling or cell execution',async()=>{
  const h=harness();h.n.running=true;
  h.context.api=async()=>{throw new Error('offline');};
  h.element('#running-panel').hidden=false;h.context.window.KernelResources.refresh();await h.poll();
  assert.equal(h.element('[data-kernel-resources="one"]').textContent,'Resources unavailable');
  assert.equal(h.n.running,true);assert.equal(h.timers.length,1);
  h.context.document.hidden=true;await h.poll();assert.equal(h.timers.length,0);
});
