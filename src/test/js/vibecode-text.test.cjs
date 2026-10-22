const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');

function harness({language='json',cellType,generate}={}){
  const nodes=new Map(),calls=[];
  const $=id=>{if(!nodes.has(id))nodes.set(id,{value:'',checked:true,open:false,selectionStart:0,selectionEnd:0,handlers:{},addEventListener(name,fn){this.handlers[name]=fn;},showModal(){this.open=true;},close(){this.open=false;},focus(){}});return nodes.get(id);};
  const original='before target after';
  const doc={path:'example.'+language,format:language,content:original,busy:false};
  const prior={cell_type:'code',source:['int answer = 42;'],outputs:[{output_type:'stream',text:'42',name:'stdout'}]};
  const cell={cell_type:cellType,source:[original]};
  const notebook={name:'example.jnb',kernel:'12345678-1234-1234-1234-123456789012',selected:1,document:{cells:[prior,cell]},running:false};
  const editor=$('#cells [data-cell="1"] textarea');editor.selectionStart=7;editor.selectionEnd=13;
  const context={window:{NotebookSettings:{aiContext:()=>({kind:'notebook-settings',title:'Settings',content:'shared settings',metadata:{}})}},$,AbortController,clearTimeout,
    TextEditing:{highlight:text=>text.replaceAll('<','&lt;')},LabPresentation:{highlightJava:text=>text},
    active:()=>notebook,state:{notebooks:[notebook]},source:c=>c.source.join(''),openDocuments:()=>[doc],
    markDirty:n=>n.dirty=true,renderCells(){},selectCell(){},notify(){},
    TextEditor:{applyAi(target,draft){if(target.content!==draft.source)return false;target.content=target.content.slice(0,draft.start??0)+draft.code+target.content.slice(draft.end??target.content.length);return true;},refresh(){}},
    api:async(url,method,body,settings)=>{calls.push({url,body,settings});if(url==='/ai/config')return {configured:true};if(url==='/ai/generate')return generate?generate(body):{code:cellType?'**result**':'{"answer":42}'};throw Error(url);}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/vibecode.js'),'utf8'),context);
  return {$,doc,cell,notebook,calls,original,ai:context.window.Vibecode,
    open:()=>cellType?context.window.Vibecode.open():context.window.Vibecode.openText(doc,{selectionStart:7,selectionEnd:13}),
    submit:()=>{$('#vibe-prompt').value='Convert or summarize';return $('#vibe-form').handlers.submit({preventDefault(){}});}};
}

test('JSON/XML/YAML file AI edits use the shared dialog, target language and captured selection with undo',async()=>{
  for(const language of ['json','xml','yaml']){
    const h=harness({language});await h.open();
    assert.equal(h.$('#vibe-language').textContent,language.toUpperCase());
    assert.equal(h.$('#vibe-notebook-option').hidden,true);
    await h.submit();const request=h.calls.find(c=>c.url==='/ai/generate').body;
    assert.equal(request.language,language);assert.equal(request.target.start,7);assert.equal(request.target.end,13);
    assert.equal(h.doc.content,'before {"answer":42} after');
    h.ai.undoSql(h.doc);assert.equal(h.doc.content,h.original);
  }
});

test('Markdown and raw text cells share code-cell notebook settings, preceding outputs and kernel tools',async()=>{
  for(const cellType of ['markdown','raw']){
    const h=harness({cellType});await h.open();await h.submit();
    const request=h.calls.find(c=>c.url==='/ai/generate').body;
    assert.equal(request.language,cellType==='raw'?'text':'markdown');
    assert.equal(request.kernelId,h.notebook.kernel);
    assert.equal(request.context[0].content,'shared settings');
    const prior=JSON.parse(request.context.find(c=>c.kind==='notebook').content);
    assert.equal(prior[0].source,'int answer = 42;');assert.equal(prior[0].outputs[0].text,'42');
    assert.equal(h.cell.source.join(''),'before **result** after');
    assert.equal(h.cell.outputs,undefined);h.ai.undo();assert.equal(h.cell.source.join(''),h.original);
  }
});

test('Cancel and edits during file generation keep the current content',async()=>{
  for(const cancel of [true,false]){
    let finish;const h=harness({generate:()=>new Promise(resolve=>finish=resolve)});await h.open();const pending=h.submit();
    if(cancel)h.$('#vibe-cancel').onclick();else h.doc.content='manual edit';
    finish({code:'replacement'});await pending;
    assert.equal(h.doc.content,cancel?h.original:'manual edit');
    if(cancel)assert.equal(h.calls.find(c=>c.url==='/ai/generate').settings.signal.aborted,true);
    else assert.match(h.$('#vibe-error').textContent,/target changed/);
  }
});
