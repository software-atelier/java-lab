const {test}=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const editing=require('../../main/resources/static/text-editing.js');
function harness(content='{"id":"001","number":9007199254740993}',options={}){
  const nodes=new Map(),calls=[],saved=new Map(),state={tabs:[],active:null,launcher:true};let uid=0,failure=null,discard=true;
  const document={activeElement:null};
  function element(id){if(!nodes.has(id))nodes.set(id,{value:'',textContent:'',innerHTML:'',hidden:false,disabled:false,style:{},handlers:{},selectionStart:0,selectionEnd:0,scrollTop:0,scrollLeft:0,
    addEventListener(name,fn){this.handlers[name]=fn;},querySelectorAll:()=>[...nodes.values()],focus(){document.activeElement=this;},select(){this.selectionStart=0;this.selectionEnd=this.value.length;},setSelectionRange(start,end){this.selectionStart=start;this.selectionEnd=end;}});return nodes.get(id);}
  const window={};const context={window,document,state,$:element,TextEditing:editing,structuredClone,crypto:{randomUUID:()=>String(++uid)},
    openDocuments:()=>state.tabs,notebookPath:(folder,name)=>folder?folder+'/'+name:name,
    escapeHtml:s=>String(s).replaceAll('<','&lt;').replaceAll('>','&gt;'),renderTabs(){},updateStatus(){},refreshFiles:async()=>{},notify(){},modal:async()=>discard,
    renderWorkspace(){window.TextEditor.activate(state.launcher||state.active?.type!=='text'?null:state.active);},
    api:async(url,method,body)=>{calls.push({url,method,body});const file=new URL('http://local'+url).searchParams.get('path');
      if(url==='/files/validate-xml')return options.validateXml?await options.validateXml(body.content):{valid:true,message:'Valid XML'};
      if(url==='/files/validate-yaml')return options.validateYaml?await options.validateYaml(body.content):{valid:true,message:'Valid YAML'};
      let data=saved.get(file)||{path:file,format:file.endsWith('.json')?'json':file.endsWith('.xml')?'xml':/\.ya?ml$/.test(file)?'yaml':'text',content,bom:true,recordSeparator:'\r\n',revision:'a'.repeat(64)};
      if(method==='PUT'){if(options.onPut)await options.onPut();if(failure)throw Error(failure);data={...data,...body,revision:'b'.repeat(64)};saved.set(file,data);}return structuredClone(data);}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/text-editor.js'),'utf8'),context);
  return {nodes,element,calls,state,open:(name='data.json')=>window.TextEditor.open(name,'Data'),save:()=>window.TextEditor.save(),close:()=>window.TextEditor.close(),
    type(value){element('#text-input').handlers.beforeinput();element('#text-input').value=value;element('#text-input').setSelectionRange(value.length,value.length);element('#text-input').handlers.input({inputType:'insertFromPaste'});},
    key(key,modifiers={}){let prevented=false;element('#text-editor').handlers.keydown({key,target:element('#text-input'),...modifiers,preventDefault(){prevented=true;},stopPropagation(){}});return prevented;},
    applySql:(doc,draft)=>window.TextEditor.applySql(doc,draft),failSave:message=>{failure=message;},allowDiscard:value=>{discard=value;}};
}
test('JSON formatting, undo/redo and saving preserve numeric literals and source format',async()=>{
  const h=harness();await h.open();const original=h.state.active.content;
  h.element('#text-format').onclick();assert.match(h.state.active.content,/9007199254740993/);assert.match(h.state.active.content,/\n/);
  assert.equal(h.state.active.dirty,true);h.key('z',{ctrlKey:true});assert.equal(h.state.active.content,original);assert.equal(h.state.active.dirty,false);
  h.key('z',{ctrlKey:true,shiftKey:true});await h.save();
  const request=h.calls.find(c=>c.method==='PUT');assert.match(request.body.content,/9007199254740993/);assert.equal(request.body.bom,true);assert.equal(request.body.recordSeparator,'\r\n');
  assert.equal(h.state.active.dirty,false);
});
test('invalid JSON remains editable/saveable, and highlighted markup never executes',async()=>{
  const h=harness();await h.open();h.type('{ "value": "</textarea><script>x</script>"');
  const draft=h.state.active.content;h.element('#text-format').onclick();assert.equal(h.state.active.content,draft);assert.equal(h.element('#text-error').hidden,false);
  assert.doesNotMatch(h.element('#text-highlight').innerHTML,/<script>/);await h.save();assert.equal(h.calls.at(-1).body.content,draft);
});
test('text tabs preserve drafts and background saves never replace the active document',async()=>{
  let finish;const response=new Promise(resolve=>{finish=resolve;});const h=harness(undefined,{onPut:()=>response});
  await h.open();const first=h.state.active;h.type('first draft');const saving=h.save();
  await h.open('other.txt');const second=h.state.active;h.type('second draft');finish();await saving;
  assert.strictEqual(h.state.active,second);assert.equal(second.dirty,true);assert.equal(first.dirty,false);assert.equal(h.element('#text-input').value,'second draft');
  await h.open();assert.strictEqual(h.state.active,first);assert.equal(h.state.tabs.length,2);assert.equal(h.element('#text-input').value,'first draft');
});
test('save conflict preserves draft and closing requires discard confirmation',async()=>{
  const h=harness();await h.open();h.type('draft');h.failSave('File changed on disk');await h.save();
  assert.equal(h.state.active.content,'draft');assert.equal(h.state.active.dirty,true);assert.match(h.element('#text-error').textContent,/changed on disk/);
  h.allowDiscard(false);await h.close();assert.equal(h.state.tabs.length,1);h.allowDiscard(true);await h.close();assert.equal(h.state.tabs.length,0);
});
test('indent assistance and find work without triggering notebook commands',async()=>{
  const h=harness('{}');await h.open();h.element('#text-input').setSelectionRange(1,1);assert.equal(h.key('Enter'),true);assert.equal(h.state.active.content,'{\n  \n}');
  h.type('one two one');h.element('#text-find').value='one';h.element('#text-input').setSelectionRange(0,0);
  h.element('#text-find-previous').onclick();assert.equal(h.element('#text-input').selectionStart,8);
  assert.equal(h.key('f',{ctrlKey:true}),true);
});
test('SQL draft application replaces the captured range, supports undo, and rejects stale source',async()=>{
  const h=harness('SELECT 1; SELECT 2;'),doc=await h.open('query.sql');
  const original=doc.content,draft={source:original,start:10,end:19,code:'SELECT name FROM people;'};
  assert.equal(h.applySql(doc,draft),true);assert.equal(doc.content,'SELECT 1; SELECT name FROM people;');assert.equal(doc.dirty,true);
  h.key('z',{ctrlKey:true});assert.equal(doc.content,original);assert.equal(doc.dirty,false);
  h.type('SELECT 3;');assert.equal(h.applySql(doc,draft),false);assert.equal(doc.content,'SELECT 3;');assert.match(doc.error,/source changed/);
});

test('search actions share keyboard behavior, close with Escape, and restore each tab independently',async()=>{
  const h=harness('one two one');await h.open('first.txt');
  assert.equal(h.element('#text-search').hidden,true);
  h.element('#text-find-toggle').onclick();assert.equal(h.element('#text-search').hidden,false);
  assert.equal(h.element('#text-find-toggle').ariaExpanded,'true');
  h.element('#text-find').value='one';h.element('#text-find').handlers.input();
  await h.open('second.txt');assert.equal(h.element('#text-search').hidden,true);
  await h.open('first.txt');assert.equal(h.element('#text-search').hidden,false);assert.equal(h.element('#text-find').value,'one');
  h.element('#text-search').handlers.keydown({key:'Escape',preventDefault(){},stopPropagation(){}});
  assert.equal(h.element('#text-search').hidden,true);assert.equal(h.element('#text-find-toggle').ariaExpanded,'false');
  h.key('f',{ctrlKey:true});assert.equal(h.element('#text-search').hidden,false);
  assert.equal(h.element('#text-format').hidden,true);assert.equal(h.element('#text-validate').hidden,true);
});

test('XML validation checks the current draft on the server and displays line/column errors',async()=>{
  const h=harness('<root/>',{validateXml:async text=>({valid:false,message:'Mismatched closing tag',line:2,column:8})});
  await h.open('data.xml');h.type('<root>\n<value></root>');
  await h.element('#text-validate').onclick();
  assert.match(h.element('#text-error').textContent,/line 2, column 8/);
  assert.equal(h.state.active.content,'<root>\n<value></root>');
  assert.equal(h.calls.find(c=>c.url==='/files/validate-xml').body.content,h.state.active.content);
});

test('a delayed XML validation result stays with its original tab',async()=>{
  let finish;const response=new Promise(resolve=>{finish=resolve;});
  const h=harness('<root/>',{validateXml:()=>response});await h.open('data.xml');const first=h.state.active;
  const validating=h.element('#text-validate').onclick();await h.open('notes.txt');const second=h.state.active;
  finish({valid:false,message:'Original XML error',line:1,column:4});await validating;
  assert.strictEqual(h.state.active,second);assert.match(first.error,/Original XML error/);assert.equal(second.error,null);
  assert.equal(h.element('#text-title').textContent,'Data/notes.txt');
});
test('YAML and YML validation checks unsaved drafts, preserves invalid text and clears resolved errors',async()=>{
  for(const extension of ['yaml','yml']){
    let valid=false;
    const h=harness('name: Ada',{validateYaml:async()=>({valid,message:'Unclosed sequence',line:2,column:4})});
    await h.open('config.'+extension);
    assert.equal(h.element('#text-validate').hidden,false);assert.equal(h.element('#text-validate').disabled,false);
    assert.equal(h.element('#text-format').hidden,true);
    h.type('name: Ada\nitems: [');const draft=h.state.active.content;
    await h.element('#text-validate').onclick();
    assert.match(h.element('#text-error').textContent,/Invalid YAML at line 2, column 4/);
    assert.equal(h.calls.find(c=>c.url==='/files/validate-yaml').body.content,draft);
    assert.equal(h.state.active.content,draft);await h.save();assert.equal(h.state.active.content,draft);
    valid=true;await h.element('#text-validate').onclick();assert.equal(h.element('#text-error').hidden,true);
  }
});
test('a delayed YAML validation error stays with its original tab',async()=>{
  let finish;const response=new Promise(resolve=>{finish=resolve;});
  const h=harness('items: [',{validateYaml:()=>response});await h.open('config.yaml');const first=h.state.active;
  const validating=h.element('#text-validate').onclick();await h.open('notes.txt');const second=h.state.active;
  finish({valid:false,message:'Original YAML error',line:1,column:9});await validating;
  assert.strictEqual(h.state.active,second);assert.match(first.error,/Original YAML error/);assert.equal(second.error,null);
});
