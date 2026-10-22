const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const app=fs.readFileSync(path.join(__dirname,'../../main/resources/static/app.js'),'utf8');
const controls=fs.readFileSync(path.join(__dirname,'../../main/resources/static/notebook-controls.js'),'utf8');
const section=(text,start,end)=>text.slice(text.indexOf(start),text.indexOf(end,text.indexOf(start)));

function harness(){
  const nodes=new Map(),buttons=new Map(),calls=[];
  const state={tabs:[],notebooks:[],opening:new Map(),active:null,launcher:false,folder:''};
  function element(selector){
    if(!nodes.has(selector))nodes.set(selector,{textContent:'',innerHTML:'',value:'',hidden:false,disabled:false,classList:{toggle(){}}});
    return nodes.get(selector);
  }
  const context={state,$:element,$$:selector=>{
    if(!buttons.has(selector))buttons.set(selector,[{disabled:false}]);return buttons.get(selector);
  },window:{CsvEditor:{activate:doc=>calls.push(['activate',doc?.uid]),save:async doc=>calls.push(['save-csv',doc.uid]),close:async doc=>calls.push(['close-csv',doc.uid])},
    TextEditor:{activate:doc=>calls.push(['activate-text',doc?.uid]),save:async doc=>calls.push(['save-text',doc.uid]),close:async doc=>calls.push(['close-text',doc.uid])},
    ImageViewer:{activate:doc=>calls.push(['activate-image',doc?.uid]),close:async doc=>calls.push(['close-image',doc.uid])},
    NotebookControls:{indexes:()=>[],restoreFocus(){},closed:doc=>calls.push(['closed',doc.uid])},JavaEditor:{dismiss(){}}},
    editingTitle:false,titleNotebook:null,finishNotebookTitle(){},renderCells:()=>calls.push(['render-cells']),renderFiles(){},
    hydrateFormValues(){},crypto:{randomUUID:()=>String(Math.random())},clearTimeout(){},
    icon:name=>`<svg data-icon="${name}"></svg>`,escapeHtml:s=>String(s).replaceAll('<','&lt;').replaceAll('>','&gt;'),
    folderQuery:folder=>'?folder='+folder,notebookPath:(folder,name)=>folder?folder+'/'+name:name,
    api:async(url,method)=>{calls.push([url,method]);if(url==='/kernels'&&method==='POST')return {id:'kernel'};return {cells:[],metadata:{}};},
    saveNotebook:async doc=>calls.push(['save-notebook',doc.uid]),notify(){}};
  vm.createContext(context);
  vm.runInContext(section(app,'function openDocuments()','function dateLabel(')
    +section(app,'function renderTabs()','function markDirty(')
    +section(app,'async function closeNotebook(','function modal('),context);
  return {context,state,element,buttons,calls};
}
const notebook=()=>({uid:'java',name:'Analysis.jnb',path:'Analysis.jnb',folder:'',document:{cells:[]},selected:0,kernel:'kernel',dirty:false});
const csv=()=>({uid:'csv',type:'csv',name:'sales.csv',path:'sales.csv',folder:'',table:{rows:[['id'],['001']]},dirty:true});

function deletionHarness(){
  const h=harness();
  h.context.modal=async()=>true;h.context.refreshFiles=async()=>{};
  h.context.setFileOperation=(doc,value)=>{doc.busy=value;};
  vm.runInContext(section(app,'async function deleteFile(','function help('),h.context);
  return h;
}

function duplicationHarness(){
  const h=harness();
  h.context.refreshFiles=async()=>h.calls.push(['refresh']);
  h.context.setFileOperation=(doc,value)=>{doc.busy=value;};
  h.context.api=async(url,method,body)=>{h.calls.push([url,method,body]);return {name:'data-copy.csv'};};
  vm.runInContext(section(app,'async function duplicateFile(','function setFileOperation('),h.context);
  return h;
}

test('duplicating an open file saves its current edits first and preserves the active tab',async()=>{
  for(const type of ['csv','text','notebook','image']){
    const h=duplicationHarness(),doc={...notebook(),type,folder:'nested',path:'nested/Analysis.jnb'};
    h.state.tabs.push(doc);h.state.active=doc;
    h.context.window.CsvEditor.save=h.context.window.TextEditor.save=async()=>{h.calls.push(['save-editable']);return true;};
    await h.context.duplicateFile(doc.name,doc.folder);
    const index=h.calls.findIndex(call=>call[0]==='/files/duplicate');
    assert.ok(index>=0);assert.equal(h.calls[index][1],'POST');assert.equal(h.calls[index][2].path,doc.path);
    if(type!=='image')assert.match(h.calls[index-1][0],/^save-/);
    assert.strictEqual(h.state.active,doc);assert.equal(h.state.tabs.length,1);assert.equal(doc.busy,false);
  }
});

test('duplication stops on save failure or execution and clears busy state after API failures',async()=>{
  const h=duplicationHarness(),doc=csv();h.state.tabs.push(doc);h.state.active=doc;
  h.context.window.CsvEditor.save=async()=>false;
  await assert.rejects(h.context.duplicateFile(doc.name),/Save the file/);
  assert.ok(!h.calls.some(call=>call[0]==='/files/duplicate'));
  doc.running=true;await assert.rejects(h.context.duplicateFile(doc.name),/Wait for execution/);
  doc.running=false;h.context.window.CsvEditor.save=async()=>true;
  h.context.api=async()=>{throw new Error('Copy failed');};
  await assert.rejects(h.context.duplicateFile(doc.name),/Copy failed/);
  assert.equal(doc.busy,false);assert.strictEqual(h.state.active,doc);
});

test('closed files can be duplicated without opening an editor or kernel',async()=>{
  const h=duplicationHarness();await h.context.duplicateFile('archive.bin','nested');
  assert.equal(h.calls[0][0],'/files/duplicate');assert.equal(h.calls[0][2].path,'nested/archive.bin');
  assert.equal(h.state.tabs.length,0);
});

test('deleting data files closes their tabs without saving discarded changes',async()=>{
  for(const type of ['csv','text','image','file']){
    const h=deletionHarness(),java=notebook(),doc={uid:type,type,name:'data file',path:'nested/data file',folder:'nested',dirty:true};
    h.state.tabs.push(java,doc);h.state.notebooks.push(java);h.state.active=doc;
    await h.context.deleteFile(doc.name,doc.folder);
    assert.ok(h.calls.some(call=>call[0]==='/files?path=nested%2Fdata%20file'&&call[1]==='DELETE'));
    assert.strictEqual(h.state.active,java);assert.equal(h.state.tabs.length,1);
    assert.ok(!h.calls.some(call=>String(call[0]).startsWith('save-')));
  }
});

test('cancelled, busy or failed deletions preserve open files',async()=>{
  const h=deletionHarness(),doc=csv();h.state.tabs.push(doc);h.state.active=doc;
  h.context.modal=async()=>false;await h.context.deleteFile(doc.name);assert.equal(h.calls.length,0);
  h.context.modal=async()=>true;doc.busy=true;
  await assert.rejects(h.context.deleteFile(doc.name),/Wait for file operations/);assert.equal(h.calls.length,0);
  doc.busy=false;h.context.api=async()=>{throw new Error('Deletion failed');};
  await assert.rejects(h.context.deleteFile(doc.name),/Deletion failed/);
  assert.strictEqual(h.state.active,doc);assert.strictEqual(h.state.tabs[0],doc);assert.equal(doc.busy,false);assert.equal(doc.dirty,true);
});

test('notebook cells open before a slow kernel finishes, with one shared startup',async()=>{
  const h=harness();let ready,starts=0;
  h.context.api=async(url)=>{
    if(url==='/kernels'){starts++;return new Promise(resolve=>{ready=resolve;});}
    return {cells:[],metadata:{}};
  };
  const [first,second]=await Promise.all([h.context.openNotebook('Slow.jnb',''),h.context.openNotebook('Slow.jnb','')]);
  assert.strictEqual(first,second);assert.equal(starts,1);assert.equal(first.kernel,null);
  assert.equal(first.kernelStarting,true);assert.equal(h.state.tabs.length,1);
  assert.ok(h.calls.some(call=>call[0]==='render-cells'));
  assert.match(h.element('#kernel-status').textContent,/Starting/);
  assert.equal(h.buttons.get('[data-action="run"]')[0].disabled,true);
  ready({id:'ready-kernel'});await first.kernelPromise;
  assert.equal(first.kernel,'ready-kernel');assert.equal(first.kernelStarting,false);
  assert.equal(h.buttons.get('[data-action="run"]')[0].disabled,false);
  assert.match(h.element('#kernel-status').textContent,/Idle/);
});

test('closing during startup cleans up the kernel once it becomes available',async()=>{
  const h=harness();let ready;
  h.context.api=async(url,method)=>{
    h.calls.push([url,method]);
    if(url==='/kernels')return new Promise(resolve=>{ready=resolve;});
    return {cells:[],metadata:{}};
  };
  const n=await h.context.openNotebook('Slow.jnb','');
  const closing=h.context.closeNotebook(n);
  ready({id:'late-kernel'});await closing;
  assert.equal(h.state.notebooks.length,0);assert.equal(h.state.tabs.length,0);
  assert.ok(h.calls.some(call=>call[0]==='/kernels/late-kernel'&&call[1]==='DELETE'));
});

test('failed background startup retains readable cells and allows settings repair',async()=>{
  const h=harness();let fail;const notices=[];h.context.notify=message=>notices.push(message);
  h.context.api=async(url)=>url==='/kernels'?new Promise((resolve,reject)=>{fail=reject;}):{cells:[],metadata:{}};
  const n=await h.context.openNotebook('Broken.jnb','');
  fail(new Error('Missing library'));await n.kernelPromise;
  assert.equal(n.kernelStarting,false);assert.equal(n.configPending,true);assert.equal(n.kernel,null);
  assert.strictEqual(h.state.active,n);assert.equal(h.element('#notebook').hidden,false);
  assert.equal(h.buttons.get('[data-action="settings"]')[0].disabled,false);
  assert.equal(h.buttons.get('[data-action="run"]')[0].disabled,true);
  assert.match(notices[0],/Missing library/);
});

test('background startup completion preserves the active CSV tab',async()=>{
  const h=harness();let ready;
  h.context.api=async(url)=>url==='/kernels'?new Promise(resolve=>{ready=resolve;}):{cells:[],metadata:{}};
  const n=await h.context.openNotebook('Slow.jnb','');
  const data=csv();h.state.tabs.push(data);h.state.active=data;h.context.renderWorkspace();
  ready({id:'ready-kernel'});await n.kernelPromise;
  assert.strictEqual(h.state.active,data);assert.equal(h.element('#kernel-status').textContent,'CSV · No kernel');
});

test('CSV is an active document but never an active Java notebook or a running kernel',()=>{
  const h=harness(),java=notebook(),data=csv();h.state.notebooks.push(java);h.state.tabs.push(java,data);h.state.active=data;
  h.context.renderWorkspace();
  assert.strictEqual(h.context.activeDocument(),data);assert.equal(h.context.active(),null);
  assert.equal(h.element('#notebook').hidden,true);assert.equal(h.element('#csv-editor').hidden,false);
  assert.equal(h.element('#kernel-status').textContent,'CSV · No kernel');
  assert.match(h.element('#status-message').textContent,/Unsaved CSV/);
  assert.match(h.element('#tabs').innerHTML,/tab active" data-tab="csv"/);
  assert.match(h.element('#tabs').innerHTML,/dirty-dot/);
  assert.match(h.element('#kernel-list').innerHTML,/Analysis/);assert.doesNotMatch(h.element('#kernel-list').innerHTML,/sales\.csv/);
  assert.equal(h.buttons.get('[data-action="run"]')[0].disabled,true);
  assert.equal(h.buttons.get('[data-action="settings"]')[0].disabled,true);
  assert.ok(!h.calls.some(c=>c[0]==='render-cells'));
});

test('opening a notebook registers one tab and switching back to Java restores notebook-only actions',async()=>{
  const h=harness(),data=csv();h.state.tabs.push(data);h.state.active=data;
  await h.context.openNotebook('Analysis.jnb','');
  assert.equal(h.state.tabs.length,2);assert.equal(h.state.notebooks.length,1);
  assert.strictEqual(h.state.tabs[1],h.state.notebooks[0]);
  assert.equal(h.element('#notebook').hidden,false);assert.equal(h.element('#csv-editor').hidden,true);
  assert.equal(h.buttons.get('[data-action="run"]')[0].disabled,false);
  await h.context.openNotebook('Analysis.jnb','');
  assert.equal(h.state.tabs.length,2);assert.equal(h.state.notebooks.length,1);
  assert.equal(h.calls.filter(c=>c[0]==='/kernels').length,1);
});

test('closing Java while CSV remains selects the CSV and only stops the Java kernel',async()=>{
  const h=harness(),java=notebook(),data=csv();h.state.tabs.push(java,data);h.state.notebooks.push(java);h.state.active=java;
  await h.context.closeDocument(java);
  assert.strictEqual(h.state.active,data);assert.equal(h.state.launcher,false);
  assert.equal(h.state.tabs.length,1);assert.equal(h.state.notebooks.length,0);
  assert.ok(h.calls.some(c=>c[0]==='/kernels/kernel'&&c[1]==='DELETE'));
  await h.context.closeDocument(data);
  assert.ok(h.calls.some(c=>c[0]==='close-csv'&&c[1]==='csv'));
});

test('keyboard tab cycling traverses mixed documents without treating CSV as a Java session',()=>{
  const h=harness(),java=notebook(),data=csv();h.state.tabs.push(java,data);h.state.notebooks.push(java);h.state.active=java;
  h.context.command=()=>{};
  vm.runInContext(section(controls,'function switchTab(','async function saveAs('),h.context);
  h.context.switchTab(1);assert.strictEqual(h.state.active,data);assert.equal(h.context.active(),null);
  h.context.switchTab(1);assert.strictEqual(h.state.active,java);assert.strictEqual(h.context.active(),java);
});

test('Save routes to the active document type and dirty background CSVs prevent losing edits on unload',async()=>{
  const h=harness(),java=notebook(),data=csv();h.state.tabs.push(java,data);h.state.notebooks.push(java);h.state.active=data;
  await h.context.saveDocument();assert.ok(h.calls.some(c=>c[0]==='save-csv'&&c[1]==='csv'));
  assert.ok(!h.calls.some(c=>c[0]==='save-notebook'));
  h.state.active=java;await h.context.saveDocument();assert.ok(h.calls.some(c=>c[0]==='save-notebook'&&c[1]==='java'));
  const handlers={};h.context.window.addEventListener=(name,fn)=>handlers[name]=fn;
  vm.runInContext(section(app,"window.addEventListener('beforeunload'","window.addEventListener('pagehide'"),h.context);
  let prevented=false;handlers.beforeunload({preventDefault(){prevented=true;}});
  assert.equal(prevented,true);
});

test('JSON/XML/TXT tabs route to the text editor and never expose notebook execution',async()=>{
  const h=harness(),data={uid:'text',type:'text',name:'data.json',path:'data.json',format:'json',content:'{}',dirty:true};
  h.state.tabs.push(data);h.state.active=data;h.context.renderWorkspace();
  assert.equal(h.context.active(),null);assert.equal(h.element('#text-editor').hidden,false);assert.equal(h.element('#notebook').hidden,true);
  assert.equal(h.element('#kernel-status').textContent,'JSON · No kernel');
  await h.context.saveDocument();await h.context.closeDocument();
  assert.ok(h.calls.some(c=>c[0]==='save-text'));assert.ok(h.calls.some(c=>c[0]==='close-text'));assert.ok(!h.calls.some(c=>c[0]==='save-notebook'));
});

test('image tabs use preview state, can be renamed, and never save through a notebook API',async()=>{
  const h=harness(),image={uid:'photo',type:'image',name:'photo.png',path:'photo.png',width:30,height:20};h.state.tabs.push(image);h.state.active=image;
  h.context.renderWorkspace();assert.equal(h.context.active(),null);assert.equal(h.element('#image-viewer').hidden,false);assert.equal(h.element('#kernel-status').textContent,'Image · No kernel');
  assert.equal(h.buttons.get('[data-action="rename"]')[0].disabled,false);assert.equal(h.buttons.get('[data-action="save"]')[0].disabled,true);
  await h.context.saveDocument();await h.context.closeDocument();assert.ok(h.calls.some(c=>c[0]==='close-image'));assert.ok(!h.calls.some(c=>c[0]==='save-notebook'));
});
