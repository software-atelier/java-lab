const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');

const script=fs.readFileSync(path.join(__dirname,'../../main/resources/static/vibecode.js'),'utf8');

function harness(cells,{configured=true,generate=async()=>({code:'int answer = 42;'})}={}) {
  const notebook={name:'errors.jnb',document:{metadata:{},cells},selected:0,running:false};
  const elements=new Map();
  function element(selector) {
    if(!elements.has(selector))elements.set(selector,{
      value:'',checked:false,hidden:false,disabled:false,textContent:'',innerHTML:'',
      selectionStart:0,selectionEnd:0,handlers:{},showCount:0,
      addEventListener(name,callback){this.handlers[name]=callback;},
      focus(){},showModal(){this.showCount++;},close(){}
    });
    return elements.get(selector);
  }
  const calls=[];let dirty=0,rendered=0,notifications=[];
  const context={window:{JavaEditor:{dismiss(){}}},$:element,active:()=>notebook,
    source:c=>Array.isArray(c.source)?c.source.join(''):c.source,
    LabPresentation:{highlightJava:source=>source},
    api:async(endpoint,method,body)=>{
      calls.push({endpoint,method,body});
      if(endpoint==='/ai/config')return {configured};
      if(endpoint==='/ai/generate')return generate(body);
      throw new Error('Unexpected endpoint '+endpoint);
    },
    state:{notebooks:[notebook]},markDirty(){dirty++;},renderCells(){rendered++;},
    selectCell(){},notify(message,error){notifications.push({message,error});},AbortController};
  vm.runInNewContext(script,context);
  return {notebook,element,calls,ai:context.window.Vibecode,
    get dirty(){return dirty;},get rendered(){return rendered;},get notifications(){return notifications;}};
}

function failedCell() {
  return {cell_type:'code',source:['String result = query("users");'],execution_count:7,
    outputs:[{output_type:'error',ename:'SQLException',evalue:'relation users does not exist',
      traceback:['SQLException: relation users does not exist','  at query:7']} ]};
}

test('one click repairs the entire failed code cell using its error, and Undo restores the failed run',async()=>{
  const cell=failedCell();
  const originalSource=cell.source,originalOutputs=cell.outputs;
  const h=harness([cell]);
  // A selection must not turn the one-click failure repair into a partial edit.
  const editor=h.element('#cells [data-cell="0"] textarea');
  editor.selectionStart=7;editor.selectionEnd=13;

  await h.ai.fixError();

  const requests=h.calls.filter(call=>call.endpoint==='/ai/generate');
  assert.equal(requests.length,1,'the button generates without another prompt click');
  const request=requests[0].body;
  assert.equal(request.target.source,'String result = query("users");');
  assert.equal(request.target.start,null);
  assert.equal(request.target.end,null);
  assert.match(request.prompt,/fix|repair|correct/i);
  assert.match(JSON.stringify(request.context),/relation users does not exist/);
  assert.equal(h.element('#vibe-dialog').showCount,1,'the existing AI dialog shows progress, but no extra prompt click is required');
  assert.equal(cell.source.join(''),'int answer = 42;');
  assert.equal(cell.outputs.length,0);
  assert.equal(cell.execution_count,null);
  assert.equal(h.ai.canUndo(cell),true);
  assert.equal(h.dirty,1);

  h.ai.undo();
  assert.strictEqual(cell.source,originalSource);
  assert.strictEqual(cell.outputs,originalOutputs);
  assert.equal(cell.execution_count,7);
  assert.equal(h.ai.canUndo(cell),false);
  assert.equal(h.dirty,2);
});

test('one-click repair is unavailable for a successful cell or markdown',async()=>{
  const success={cell_type:'code',source:['System.out.println("ok");'],outputs:[{output_type:'stream',name:'stdout',text:'ok'}]};
  const markdown={cell_type:'markdown',source:['# Note'],outputs:[{output_type:'error',evalue:'not code'}]};
  for(const cell of [success,markdown]) {
    const h=harness([cell]);
    await h.ai.fixError();
    assert.equal(h.calls.length,0);
    assert.equal(h.dirty,0);
  }
});

test('unconfigured AI leaves the failed cell unchanged without a generation request',async()=>{
  const cell=failedCell(),h=harness([cell],{configured:false});
  await h.ai.fixError();
  assert.equal(h.calls.filter(call=>call.endpoint==='/ai/generate').length,0);
  assert.equal(cell.source.join(''),'String result = query("users");');
  assert.equal(cell.outputs.length,1);
  assert.equal(h.dirty,0);
});

test('an edit made while AI is generating is not overwritten',async()=>{
  let complete;
  const response=new Promise(resolve=>{complete=resolve;});
  const cell=failedCell(),h=harness([cell],{generate:()=>response});
  const pending=h.ai.fixError();
  // Let the configuration request and generation request settle into the pending response.
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(h.calls.filter(call=>call.endpoint==='/ai/generate').length,1);
  cell.source=['// my own fix'];
  complete({code:'// AI fix'});
  await pending;
  assert.equal(cell.source.join(''),'// my own fix');
  assert.equal(cell.outputs.length,1);
  assert.equal(h.ai.canUndo(cell),false);
  assert.equal(h.dirty,0);
});

test('a new run or AI service failure keeps the original code and errors',async()=>{
  const first=failedCell();let complete;
  const response=new Promise(resolve=>{complete=resolve;});
  const rerun=harness([first],{generate:()=>response});
  const pending=rerun.ai.fixError();
  await new Promise(resolve=>setImmediate(resolve));
  first.outputs=[{output_type:'error',evalue:'different failure'}];
  complete({code:'// obsolete fix'});
  await pending;
  assert.equal(first.source.join(''),'String result = query("users");');
  assert.equal(first.outputs[0].evalue,'different failure');
  assert.equal(rerun.dirty,0);

  const second=failedCell();
  const failed=harness([second],{generate:async()=>{throw new Error('AI service unavailable');}});
  await failed.ai.fixError();
  assert.equal(second.source.join(''),'String result = query("users");');
  assert.equal(second.outputs.length,1);
  assert.equal(failed.dirty,0);
  assert.match(failed.element('#vibe-error').textContent,/AI service unavailable/);
});

test('Vibecode binds inspection to the active kernel and discards results after a kernel restart',async()=>{
  let complete;
  const response=new Promise(resolve=>{complete=resolve;});
  const cell=failedCell(),h=harness([cell],{generate:()=>response});
  h.notebook.kernel='original-kernel';
  const pending=h.ai.fixError();
  await new Promise(resolve=>setImmediate(resolve));
  const request=h.calls.find(call=>call.endpoint==='/ai/generate').body;
  assert.equal(request.kernelId,'original-kernel');
  h.notebook.kernel='replacement-kernel';
  complete({code:'// based on obsolete session values'});
  await pending;
  assert.equal(cell.source.join(''),'String result = query("users");');
  assert.equal(h.dirty,0);
  assert.match(h.element('#vibe-error').textContent,/changed while generating/);
});
