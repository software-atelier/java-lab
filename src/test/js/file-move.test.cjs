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
    notebookPath:(folder,name)=>folder?folder+'/'+name:name,
    saveNotebook:async n=>calls.push(['save',n.path]),
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

test('file browser shows only native notebooks and folders',()=>{
  const start=code.indexOf('function renderFiles()');
  const end=code.indexOf('function renderTabs()',start);
  const nodes={'#file-search':{value:''},'#file-list':{innerHTML:''},'#recent-files':{innerHTML:''}};
  const context={
    state:{folder:'',folders:[{name:'archive',path:'archive'}],files:[
      {name:'Example.jnb',type:'notebook',modified:10},
      {name:'data.csv',type:'file',modified:20},
      {name:'Legacy.ipynb',type:'notebook',modified:30},
      {name:'.hidden.jnb',type:'notebook',modified:40}
    ]},
    $:selector=>nodes[selector],active:()=>null,notebookPath:(folder,name)=>folder?folder+'/'+name:name,
    icon:()=>'<svg></svg>',escapeHtml:value=>String(value),dateLabel:()=> 'Today'
  };
  vm.runInNewContext(code.slice(start,end),context);
  context.renderFiles();
  assert.match(nodes['#file-list'].innerHTML,/data-folder="archive"/);
  assert.match(nodes['#file-list'].innerHTML,/data-file="Example\.jnb"/);
  assert.doesNotMatch(nodes['#file-list'].innerHTML,/data\.csv|Legacy\.ipynb|\.hidden\.jnb/);
  assert.match(nodes['#recent-files'].innerHTML,/Example\.jnb/);
  assert.doesNotMatch(nodes['#recent-files'].innerHTML,/data\.csv|Legacy\.ipynb|\.hidden\.jnb/);
});
