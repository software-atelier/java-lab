const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');

const code=fs.readFileSync(path.join(__dirname,'../../main/resources/static/app.js'),'utf8');
const start=code.indexOf('async function moveFile(');
const end=code.indexOf('async function deleteNotebook(',start);

function harness(response,failed=false) {
  const notebook={name:'Example.jnb',folder:'old',path:'old/Example.jnb'};
  const calls=[];
  const context={
    state:{notebooks:[notebook]},
    openDocuments:()=>context.state.notebooks,
    notebookPath:(folder,name)=>folder?folder+'/'+name:name,
    saveNotebook:async n=>calls.push(['save',n.path]),
    window:{CsvEditor:{save:async n=>{calls.push(['save-csv',n.path]);return !failed;}}},
    setFileOperation:(doc,value)=>{doc.fileOperation=value;doc.busy=value;},
    api:async (url,method,body)=>{calls.push(['api',url,method,body]);if(failed)throw Error('Name already exists');return response;},
    refreshFiles:async()=>calls.push(['refresh']),
    renderWorkspace:()=>calls.push(['render']),
    notify:message=>calls.push(['notify',message])
  };
  vm.runInNewContext(code.slice(start,end),context);
  return {notebook,calls,move:context.moveFile};
}

test('moving an open notebook saves first and updates subsequent save path',async()=>{
  const h=harness({path:'new/Example.jnb',name:'Example.jnb'});
  await h.move('Example.jnb','old','new');
  assert.deepEqual(h.calls[0],['save','old/Example.jnb']);
  assert.deepEqual([...h.calls[1].slice(0,3),JSON.stringify(h.calls[1][3])],['api','/notebooks/move','POST',JSON.stringify({path:'old/Example.jnb',folder:'new'})]);
  assert.equal(h.notebook.path,'new/Example.jnb');
  assert.equal(h.notebook.folder,'new');
});

test('failed move retains open notebook path, and same-folder drop does nothing',async()=>{
  const h=harness(null,true);
  await assert.rejects(h.move('Example.jnb','old','new'),/Name already exists/);
  assert.equal(h.notebook.path,'old/Example.jnb');
  const count=h.calls.length;
  await h.move('Example.jnb','old','old');
  assert.equal(h.calls.length,count);
});

test('moving an open CSV saves it first and updates the same tab document',async()=>{
  const h=harness({path:'new/data.csv',name:'data.csv'});
  Object.assign(h.notebook,{type:'csv',name:'data.csv',path:'old/data.csv'});
  await h.move('data.csv','old','new');
  assert.deepEqual(h.calls[0],['save-csv','old/data.csv']);
  assert.equal(h.notebook.path,'new/data.csv');assert.equal(h.notebook.folder,'new');
});

test('a CSV save conflict prevents moving the file and keeps its tab path',async()=>{
  const h=harness(null,true);Object.assign(h.notebook,{type:'csv',name:'data.csv',path:'old/data.csv'});
  await assert.rejects(h.move('data.csv','old','new'),/Save the CSV/);
  assert.equal(h.notebook.path,'old/data.csv');assert.ok(!h.calls.some(c=>c[0]==='api'));
});

test('file browser shows all visible files while recent files remain notebooks',()=>{
  const start=code.indexOf('function renderFiles()');
  const end=code.indexOf('function renderTabs()',start);
  const nodes={'#file-search':{value:''},'#file-list':{innerHTML:''},'#recent-files':{innerHTML:''}};
  const context={
    state:{folder:'',folders:[{name:'archive',path:'archive'}],files:[
      {name:'Example.jnb',type:'notebook',modified:10},
      {name:'data.csv',type:'csv',modified:20},
      {name:'UPPER.CSV',type:'csv',modified:20},
      {name:'notes.txt',type:'file',modified:20},
      {name:'data.json',type:'text',modified:20},{name:'data.xml',type:'text',modified:20},
      {name:'database.sqlite',type:'file',modified:20},
      {name:'photo.png',type:'image',modified:20},{name:'query.sql',type:'text',modified:20},
      {name:'config.yaml',type:'text',modified:20},{name:'sample.constructor',type:'file',modified:20},
      {name:'Legacy.ipynb',type:'notebook',modified:30},
      {name:'.hidden.jnb',type:'notebook',modified:40}
    ]},
    $:selector=>nodes[selector],activeDocument:()=>null,notebookPath:(folder,name)=>folder?folder+'/'+name:name,
    icon:name=>`<svg data-icon="${name}"></svg>`,escapeHtml:value=>String(value),dateLabel:()=> 'Today'
  };
  vm.runInNewContext(code.slice(start,end),context);
  context.renderFiles();
  assert.match(nodes['#file-list'].innerHTML,/data-folder="archive"/);
  assert.match(nodes['#file-list'].innerHTML,/data-file="Example\.jnb"/);
   assert.match(nodes['#file-list'].innerHTML,/data-file="data\.csv" data-file-type="csv"/);
   assert.match(nodes['#file-list'].innerHTML,/UPPER\.CSV/);
   assert.match(nodes['#file-list'].innerHTML,/notes\.txt|Legacy\.ipynb/);
   assert.doesNotMatch(nodes['#file-list'].innerHTML,/\.hidden\.jnb/);
  assert.match(nodes['#recent-files'].innerHTML,/Example\.jnb/);
   assert.match(nodes['#recent-files'].innerHTML,/Legacy\.ipynb/);
   assert.doesNotMatch(nodes['#recent-files'].innerHTML,/data\.csv|\.hidden\.jnb/);
   const index=fs.readFileSync(path.join(__dirname,'../../main/resources/static/index.html'),'utf8');
   for(const match of nodes['#file-list'].innerHTML.matchAll(/data-icon="([^"]+)"/g))assert.ok(index.includes(`id="i-${match[1]}"`),match[1]);
   assert.match(nodes['#file-list'].innerHTML,/data-file="data\.json"[^]*?<svg data-icon="braces"/);
   assert.match(nodes['#file-list'].innerHTML,/data-file="photo\.png"[^]*?<svg data-icon="image"/);
   assert.match(nodes['#file-list'].innerHTML,/data-file="data\.xml"[^]*?<svg data-icon="xml"/);
   assert.match(nodes['#file-list'].innerHTML,/data-file="database\.sqlite"[^]*?<svg data-icon="database"/);
   assert.match(nodes['#file-list'].innerHTML,/data-file="UPPER\.CSV"[^]*?<svg data-icon="table"/);
   assert.match(nodes['#file-list'].innerHTML,/data-file="sample\.constructor"[^]*?<svg data-icon="file"/);
});

test('launcher creates useful document types without individual source-format cards',()=>{
  const html=fs.readFileSync(path.join(__dirname,'../../main/resources/static/index.html'),'utf8');
  const start=html.indexOf('<div class="launcher-cards">'),end=html.indexOf('</div>',start);
  const cards=html.slice(start,end);
  for(const action of ['new','example','new-csv','new-json','new-xml','new-txt','new-yaml','new-sql','new-image'])assert.match(cards,new RegExp(`data-action="${action}"`));
  for(const action of ['new-md','new-java','new-js','new-css','new-html','new-properties','new-log'])assert.doesNotMatch(cards,new RegExp(`data-action="${action}"`));
});
