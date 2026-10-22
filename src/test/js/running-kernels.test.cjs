const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const app=fs.readFileSync(path.join(__dirname,'../../main/resources/static/app.js'),'utf8');
const section=(start,end)=>app.slice(app.indexOf(start),app.indexOf(end,app.indexOf(start)));
function harness(){
  const calls=[],nodes=new Map();let tick,cleared=false;
  const n={uid:'background',name:'Analysis.jnb',path:'nested/Analysis.jnb',kernel:'old',document:{cells:[{id:'one',cell_type:'code',source:'42',outputs:[{}]}]}};
  const active={uid:'other'};
  const context={window:{},state:{notebooks:[n]},active:()=>active,
    $:selector=>{if(!nodes.has(selector))nodes.set(selector,{});return nodes.get(selector);},
    icon:()=>'',escapeHtml:s=>String(s).replaceAll('<','&lt;'),Date,
    modal:async()=>true,notify:message=>calls.push(['notice',message]),updateStatus(){},renderCells(){throw new Error('Background notebook rendered over active tab');},
    saveNotebook:async()=>calls.push(['save']),api:async(url,method)=>{calls.push([url,method]);return {id:'new'};},
    setInterval:fn=>{tick=fn;return 1;},clearInterval:()=>{cleared=true;},source:c=>c.source,
    formValues:()=>({}),hydrateFormValues(){},clearStaleThrough(){},markDirty(){}};
  vm.createContext(context);
  vm.runInContext(section('function renderKernels()','async function openNotebook(')+section('async function startKernel(','async function closeNotebook(')+section('async function evaluateCell(','async function runSelected('),context);
  return {context,n,calls,nodes,tick:()=>tick(),cleared:()=>cleared};
}
test('sidebar controls target their notebook and shutdown keeps cells, outputs and tabs intact',async()=>{
  const h=harness(),cells=h.n.document.cells;
  h.context.renderKernels();assert.match(h.nodes.get('#kernel-list').innerHTML,/data-tab="background"/);
  await h.context.shutdownKernel(h.n);
  assert.equal(h.n.kernel,null);assert.strictEqual(h.n.document.cells,cells);assert.equal(cells[0].outputs.length,1);
  assert.equal(h.context.state.notebooks.length,1);assert.ok(h.n.staleCells.has('one'));
  assert.match(h.nodes.get('#kernel-list').innerHTML,/data-kernel-action="start"/);
  await h.context.startKernel(h.n);assert.equal(h.n.kernel,'new');assert.equal(h.n.kernelStarting,false);
  assert.deepEqual(h.calls.filter(c=>c[0].startsWith('/')), [['/kernels/old','DELETE'],['/kernels','POST']]);
});
test('restart preserves the old session on failure and prevents duplicate starts',async()=>{
  const h=harness();let reject;
  h.context.api=()=>new Promise((resolve,fail)=>{reject=fail;});
  const first=h.context.startKernel(h.n),second=h.context.startKernel(h.n);
  assert.equal(h.n.kernelStarting,true);reject(new Error('Startup failed'));
  await assert.rejects(first,/Startup failed/);await assert.rejects(second,/Startup failed/);
  assert.equal(h.n.kernel,'old');assert.equal(h.n.kernelStarting,false);
});
test('interrupt cancels queued cells on the target notebook and busy shutdown is rejected',async()=>{
  const h=harness();h.n.running=true;
  await assert.rejects(h.context.shutdownKernel(h.n),/Interrupt execution/);
  await h.context.interruptKernel(h.n);assert.equal(h.n.cancelRun,true);
  assert.ok(h.calls.some(c=>c[0]==='/kernels/old/interrupt'&&c[1]==='POST'));
});
test('execution timing follows the current cell and is cleaned up after request failure',async()=>{
  const h=harness();let reject;
  h.context.api=()=>new Promise((resolve,fail)=>{reject=fail;});
  const execution=h.context.evaluateCell(h.n,h.n.document.cells[0]);
  assert.match(h.context.kernelTiming(h.n),/Cell 1 · 0s/);
  h.n.cellStartedAt=Date.now()-65000;h.tick();
  assert.match(h.nodes.get('[data-kernel-timing="background"]').textContent,/Cell 1 · 1m 5s/);
  reject(new Error('Execution failed'));await assert.rejects(execution,/Execution failed/);
  assert.equal(h.cleared(),true);assert.equal(h.n.executingCell,undefined);assert.equal(h.n.document.cells[0].busy,undefined);
});
test('failed shutdown retains its kernel and clears lifecycle locks',async()=>{
  const h=harness();h.context.api=async()=>{throw new Error('Shutdown failed');};
  await assert.rejects(h.context.shutdownKernel(h.n),/Shutdown failed/);
  assert.equal(h.n.kernel,'old');assert.equal(h.n.kernelStarting,false);assert.equal(h.n.kernelStopping,undefined);
});
test('running a stopped notebook starts a fresh kernel before executing its cells',async()=>{
  const h=harness();h.n.kernel=null;
  Object.assign(h.context,{validVisibleForms:()=>true,window:{},evaluateCell:async(n,c)=>{h.calls.push(['execute',n.kernel,c.id]);return true;}});
  vm.runInContext(section('async function runAll(','async function applyFormAndRun('),h.context);
  await h.context.runAll(0,h.n);
  assert.equal(h.n.running,false);assert.equal(h.n.kernel,'new');
  assert.deepEqual(h.calls.filter(c=>c[0]==='/kernels'||c[0]==='execute'),[['/kernels','POST'],['execute','new','one']]);
});
test('restarting a background notebook replaces only its session and marks saved outputs stale',async()=>{
  const h=harness();await h.context.restartKernel(false,h.n);
  assert.equal(h.n.kernel,'new');assert.ok(h.n.staleCells.has('one'));
  assert.ok(h.calls.some(c=>c[0]==='/kernels/old'&&c[1]==='DELETE'));
  assert.equal(h.context.active().uid,'other');
});
