const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');

function harness({poll=async()=>({done:false,elapsedMillis:2400,events:[{elapsedMillis:1200,activity:'Inspecting variable: customers (nested value)…'}]})}={}) {
  const elements=new Map(),timers=new Map(),calls=[];
  let timerId=0,resolveGeneration,rejectGeneration;
  const generated=new Promise((resolve,reject)=>{resolveGeneration=resolve;rejectGeneration=reject;});
  const cell={cell_type:'code',source:['int original = 1;'],outputs:[]};
  const notebook={name:'test.jnb',kernel:'12345678-1234-1234-1234-123456789012',document:{cells:[cell]},selected:0,running:false};
  function element(selector) {
    if(!elements.has(selector))elements.set(selector,{
      value:'',checked:false,hidden:false,disabled:false,textContent:'',innerHTML:'',handlers:{},
      selectionStart:0,selectionEnd:0,addEventListener(name,handler){this.handlers[name]=handler;},focus(){},showModal(){},close(){}
    });
    return elements.get(selector);
  }
  const requestId='aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
  const context={window:{crypto:{randomUUID:()=>requestId}},$:element,active:()=>notebook,source:c=>c.source.join(''),
    LabPresentation:{highlightJava:s=>s},AbortController,
    state:{notebooks:[notebook]},markDirty(){},renderCells(){},selectCell(){},notify(){},
    setTimeout(fn){const id=++timerId;timers.set(id,fn);return id;},clearTimeout(id){timers.delete(id);},
    api:async(endpoint,method,body,options)=>{
      calls.push({endpoint,method,body,options});
      if(endpoint==='/ai/config')return {configured:true};
      if(endpoint==='/ai/generate')return generated;
      if(endpoint===`/ai/progress/${requestId}`)return poll();
      throw new Error('Unexpected endpoint '+endpoint);
    }
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/vibecode.js'),'utf8'),context);
  return {element,timers,calls,cell,requestId,ai:context.window.Vibecode,
    async start(){await context.window.Vibecode.open();element('#vibe-prompt').value='Update';return {pending:element('#vibe-form').handlers.submit({preventDefault(){}})};},
    async tick(){const [id,fn]=timers.entries().next().value;timers.delete(id);await fn();},
    finish:resolveGeneration,fail:rejectGeneration};
}

test('live progress uses the generation ID, updates safe text, and stops polling when applied',async()=>{
  const h=harness();
  const {pending}=await h.start();
  assert.equal(h.calls.find(c=>c.endpoint==='/ai/generate').body.requestId,h.requestId);
  assert.equal(h.element('#vibe-progress').hidden,false);
  await h.tick();
  assert.match(h.element('#vibe-progress-activity').textContent,/Inspecting variable: customers/);
  assert.equal(h.element('#vibe-progress-elapsed').textContent,'Elapsed: 2s');
  assert.match(h.element('#vibe-progress-log').textContent,/1s · Inspecting/);
  assert.equal(h.element('#vibe-progress-event-count').textContent,'(1)');
  assert.equal(h.element('#vibe-progress-history').open,false);
  const poll=h.calls.find(c=>c.endpoint.startsWith('/ai/progress/'));
  assert.equal(poll.options.cache,'no-store');
  assert.equal(h.timers.size,1);
  h.finish({code:'int updated = 2;'});
  await pending;
  assert.equal(h.cell.source.join(''),'int updated = 2;');
  assert.equal(h.timers.size,0);
  assert.equal(poll.options.signal.aborted,true);
});

test('late progress after Cancel cannot update the popup or restart polling',async()=>{
  let resolvePoll;
  const response=new Promise(resolve=>{resolvePoll=resolve;});
  const h=harness({poll:()=>response});
  const {pending}=await h.start();
  const polling=h.tick();
  const before=h.element('#vibe-progress-activity').textContent;
  h.element('#vibe-cancel').onclick();
  resolvePoll({events:[{activity:'obsolete activity',elapsedMillis:100}],elapsedMillis:100,done:false});
  await polling;
  assert.equal(h.element('#vibe-progress-activity').textContent,before);
  assert.equal(h.timers.size,0);
  h.finish({code:'// late replacement'});
  await pending;
  assert.equal(h.cell.source.join(''),'int original = 1;');
});

test('failed polls are retried without failing generation, and API failures stop the timer',async()=>{
  const h=harness({poll:async()=>{throw new Error('progress not registered yet');}});
  const {pending}=await h.start();
  await h.tick();
  assert.equal(h.timers.size,1);
  h.fail(new Error('AI service unavailable'));
  await pending;
  assert.equal(h.timers.size,0);
  assert.equal(h.element('#vibe-progress').hidden,true);
  assert.match(h.element('#vibe-error').textContent,/AI service unavailable/);
});

test('Escape dismisses only the popup, focus reopens it, and activity is never inserted as HTML',async()=>{
  const h=harness({poll:async()=>({done:true,elapsedMillis:1000,events:[{elapsedMillis:900,activity:'<img src=x onerror=alert(1)>'}]})});
  const {pending}=await h.start();
  await h.tick();
  assert.equal(h.element('#vibe-progress-activity').textContent,'<img src=x onerror=alert(1)>');
  assert.equal(h.element('#vibe-progress-activity').innerHTML,'');
  assert.equal(h.timers.size,0);
  let prevented=false,stopped=false;
  h.element('#vibe-progress').handlers.keydown({key:'Escape',preventDefault(){prevented=true;},stopPropagation(){stopped=true;}});
  assert.equal(prevented,true);assert.equal(stopped,true);
  assert.equal(h.element('#vibe-progress-popup').hidden,true);
  assert.equal(h.calls.find(c=>c.endpoint==='/ai/generate').options.signal.aborted,false);
  h.element('#vibe-progress').handlers.focusin();
  assert.equal(h.element('#vibe-progress-popup').hidden,false);
  h.finish({code:'int done = 3;'});await pending;
});

const diagnostic={request:1,configuredModel:'requested/model',reportedModel:'actual/model',maxTokens:128000,
  finishReason:'length',inputTokens:4500,outputTokens:1200,reasoningTokens:800};

test('popup displays reported model, limit, finish reason and token usage with explicit unknown values',async()=>{
  const h=harness({poll:async()=>({done:false,elapsedMillis:1000,events:[],diagnostics:[diagnostic,
    {...diagnostic,request:2,reportedModel:null,finishReason:null,inputTokens:null,outputTokens:null,reasoningTokens:null}]})});
  const {pending}=await h.start();
  await h.tick();
  const text=h.element('#vibe-progress-diagnostics').textContent;
  assert.match(text,/Configured model: requested\/model/);
  assert.match(text,/Reported model: actual\/model/);
  assert.match(text,/Output limit: 128000 tokens/);
  assert.match(text,/Finish reason: length/);
  assert.match(text,/Input tokens: 4500/);
  assert.match(text,/Output tokens: 1200/);
  assert.match(text,/Reasoning tokens: 800/);
  assert.match(text,/Reported model: not reported/);
  assert.match(text,/Finish reason: not reported/);
  assert.match(text,/Output tokens: not reported/);
  assert.equal(h.element('#vibe-progress-diagnostics').hidden,false);
  assert.equal(h.element('#vibe-progress-model-details').hidden,false);
  assert.equal(h.element('#vibe-progress-model-details').open,false);
  assert.equal(h.element('#vibe-progress-request-count').textContent,'(2 requests)');
  h.finish({code:'int done = 3;',diagnostics:[diagnostic]});await pending;
});

test('a fast successful response preserves final diagnostics for the next opening without needing a poll',async()=>{
  const h=harness();
  const {pending}=await h.start();
  h.finish({code:'int done = 3;',diagnostics:[{...diagnostic,finishReason:'stop',reportedModel:'<img src=x onerror=alert(1)>'}]});
  await pending;
  assert.equal(h.calls.some(c=>c.endpoint.startsWith('/ai/progress/')),false);
  await h.ai.open();
  assert.equal(h.element('#vibe-diagnostics').hidden,false);
  assert.match(h.element('#vibe-diagnostics-text').textContent,/Finish reason: stop/);
  assert.match(h.element('#vibe-diagnostics-text').textContent,/<img src=x/);
  assert.equal(h.element('#vibe-diagnostics-text').innerHTML,'');
});

test('failed correction keeps source and outputs and fetches final diagnostics without masking the error',async()=>{
  const h=harness({poll:async()=>({done:true,events:[],diagnostics:[diagnostic,{...diagnostic,request:2,finishReason:'stop'}]})});
  const outputs=[{output_type:'stream',text:'keep this output'}];h.cell.outputs=outputs;
  const {pending}=await h.start();
  h.fail(new Error('Incomplete Java again after one correction attempt'));
  await pending;await new Promise(resolve=>setImmediate(resolve));
  assert.equal(h.cell.source.join(''),'int original = 1;');
  assert.strictEqual(h.cell.outputs,outputs);
  assert.match(h.element('#vibe-error').textContent,/after one correction attempt/);
  assert.equal(h.element('#vibe-diagnostics').hidden,false);
  assert.equal(h.element('#vibe-diagnostics').open,true);
  assert.match(h.element('#vibe-diagnostics-text').textContent,/Request 2/);
  assert.match(h.element('#vibe-diagnostics-text').textContent,/Finish reason: length/);
  assert.equal(h.timers.size,0);
});

test('closing the dialog aborts a pending final diagnostics fetch and ignores its late response',async()=>{
  let finishPoll;
  const response=new Promise(resolve=>{finishPoll=resolve;});
  const h=harness({poll:()=>response});
  const {pending}=await h.start();
  h.fail(new Error('Generation failed'));
  await pending;
  const fetch=h.calls.find(c=>c.endpoint.startsWith('/ai/progress/'));
  h.element('#vibe-cancel').onclick();
  assert.equal(fetch.options.signal.aborted,true);
  finishPoll({done:true,diagnostics:[diagnostic]});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(h.element('#vibe-diagnostics').hidden,true);
});
