const {test}=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
function harness(options={}){
  const nodes=new Map(),calls=[],documents=[],notices=[];
  const $=id=>{if(!nodes.has(id))nodes.set(id,{value:'',hidden:false,disabled:false,open:false,handlers:{},addEventListener(name,fn){this.handlers[name]=fn;},showModal(){this.open=true;},close(){this.open=false;},focus(){}});return nodes.get(id);};
  const doc={path:'query.sql',content:'SELECT 1; SELECT 2;',sqlConfig:{maven:'example:jdbc:1',url:'jdbc:example:db'},busy:false};documents.push(doc);
  const textEditor={applySql(target,draft){if(target.content!==draft.source)return false;target.content=target.content.slice(0,draft.start??0)+draft.code+target.content.slice(draft.end??target.content.length);target.dirty=true;return true;},refresh:target=>calls.push({refresh:target})};
  const context={window:{},$,AbortController,clearTimeout,TextEditor:textEditor,TextEditing:{highlight:source=>source.replaceAll('<','&lt;')},openDocuments:()=>documents,
    notify:message=>notices.push(message),api:async(url,method,body,settings)=>{calls.push({url,method,body,settings});
      if(url==='/ai/config')return {configured:options.configured!==false,provider:'Test API',model:'test/model'};
      if(url==='/sql/generate')return options.generate?options.generate(body):{code:'SELECT name FROM people;',diagnostics:[]};throw Error('Unexpected endpoint '+url);}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/vibecode.js'),'utf8'),context);
  return {$,doc,calls,notices,documents,ai:context.window.Vibecode,open:(start=0,end=0)=>context.window.Vibecode.openSql(doc,{selectionStart:start,selectionEnd:end}),
    generate:()=>$('#vibe-form').handlers.submit({preventDefault(){}})};
}
test('SQL uses the Java Vibecode dialog and directly applies the captured selection with AI undo',async()=>{
  const h=harness(),original=h.doc.content;await h.open(10,19);
  assert.equal(h.$('#vibe-dialog').open,true);assert.equal(h.$('#vibe-language').textContent,'SQL · JDBC');
  assert.equal(h.$('#vibe-target-label').textContent,'REPLACE SELECTED SQL');assert.equal(h.$('#vibe-target-code').innerHTML,'SELECT 2;');
  assert.equal(h.$('#vibe-notebook-option').hidden,true);assert.equal(h.$('#vibe-sql-context').hidden,false);
  h.$('#vibe-prompt').value='List people';h.$('#vibe-context-notes').value='Only active people';await h.generate();
  const request=h.calls.find(c=>c.url==='/sql/generate').body;
  assert.equal(request.start,10);assert.equal(request.end,19);assert.equal(request.notes,'Only active people');assert.equal(request.source,original);
  assert.equal(h.doc.content,'SELECT 1; SELECT name FROM people;');assert.equal(h.$('#vibe-dialog').open,false);
  assert.equal(h.ai.canUndoSql(h.doc),true);h.ai.undoSql(h.doc);assert.equal(h.doc.content,original);assert.equal(h.ai.canUndoSql(h.doc),false);
  assert.equal(h.calls.some(c=>c.url==='/sql/execute'),false);
});
test('Cancel aborts generation and late SQL results never change the source',async()=>{
  let finish;const pending=new Promise(resolve=>{finish=resolve;});const h=harness({generate:()=>pending});await h.open();
  h.$('#vibe-prompt').value='List people';const generating=h.generate();
  const call=h.calls.find(c=>c.url==='/sql/generate');h.$('#vibe-cancel').onclick();assert.equal(call.settings.signal.aborted,true);
  finish({code:'SELECT name FROM people;'});await generating;assert.equal(h.doc.content,'SELECT 1; SELECT 2;');assert.equal(h.ai.canUndoSql(h.doc),false);
});
test('edits or connection changes during SQL generation are retained and show an error',async()=>{
  for(const change of [h=>h.doc.content='SELECT 3;',h=>h.doc.sqlConfig.url='jdbc:other:db']){
    let finish;const pending=new Promise(resolve=>{finish=resolve;});const h=harness({generate:()=>pending});await h.open();h.$('#vibe-prompt').value='List people';
    const generating=h.generate();change(h);const original=h.doc.content;finish({code:'SELECT name FROM people;'});await generating;
    assert.equal(h.doc.content,original);assert.match(h.$('#vibe-error').textContent,/target changed/);assert.equal(h.$('#vibe-dialog').open,true);
  }
});
test('SQL configuration failures and provider errors leave the original code in the dialog',async()=>{
  const unconfigured=harness({configured:false});await unconfigured.open();assert.equal(unconfigured.$('#vibe-generate').disabled,true);assert.match(unconfigured.$('#vibe-error').textContent,/AI_MODEL/);
  const failed=harness({generate:async()=>{throw Error('Provider unavailable');}});await failed.open();failed.$('#vibe-prompt').value='List people';await failed.generate();
  assert.equal(failed.doc.content,'SELECT 1; SELECT 2;');assert.equal(failed.$('#vibe-generate').disabled,false);assert.equal(failed.$('#vibe-error').textContent,'Provider unavailable');
});
