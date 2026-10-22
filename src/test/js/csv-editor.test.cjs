const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');

function harness(rows=[['id','notes'],['001','value']],options={}){
  const nodes=new Map(),calls=[],savedFiles=new Map(),state={tabs:[],notebooks:[],active:null,launcher:true};let allowDiscard=true,failure=null,uid=0;
  const template={revision:'a'.repeat(64),size:30,table:{rows,delimiter:',',recordSeparator:'\r\n',bom:true,trailingNewline:false}};
  const document={activeElement:null};
  function element(selector){
    if(!nodes.has(selector))nodes.set(selector,{
      value:'',hidden:false,disabled:false,textContent:'',innerHTML:'',open:false,handlers:{},scrollTop:0,scrollLeft:0,
      addEventListener(name,fn){this.handlers[name]=fn;},querySelector:s=>element(s),querySelectorAll:()=>[...nodes.values()],
      showModal(){this.open=true;},close(){this.open=false;},focus(){document.activeElement=this;},
      select(){this.selectionStart=0;this.selectionEnd=this.value.length;},
      setSelectionRange(start,end){this.selectionStart=start;this.selectionEnd=end;}
    });
    return nodes.get(selector);
  }
  const grids=[];
  class Grid{
    constructor(config){this.config=config;this.handlers={};grids.push(this);}
    on(name,fn){this.handlers[name]=fn;return this;}
    render(host){
      this.host=host;
      const {columns,data,language}=this.config;
      host.innerHTML=`<table><thead><tr>${columns.map(col=>`<th>${col.name}</th>`).join('')}</tr></thead><tbody>${data.length?data.map(values=>{
        const row={cells:values.map(data=>({data}))};
        return `<tr>${columns.map((col,index)=>`<td>${col.formatter?col.formatter(values[index],row):values[index]}</td>`).join('')}</tr>`;
      }).join(''):`<tr><td>${language.noRecordsFound}</td></tr>`}</tbody></table>`;
      this.handlers.ready?.();return this;
    }
    destroy(){this.destroyed=true;this.host.innerHTML='';}
  }
  const window={gridjs:{Grid,html:value=>value},addEventListener(name,fn){this[name]=fn;}};
  const context={window,$:element,structuredClone,state,document,crypto:{randomUUID:()=>`csv-${++uid}`},
    openDocuments:()=>state.tabs,activeDocument:()=>state.launcher?null:state.active,
    renderTabs(){},updateStatus(){},
    renderWorkspace(){const doc=state.launcher?null:state.active;window.CsvEditor.activate(doc?.type==='csv'?doc:null);element('#csv-editor').hidden=doc?.type!=='csv';},
    notebookPath:(folder,name)=>folder?folder+'/'+name:name,
    escapeHtml:s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])),
    api:async(url,method,body)=>{
      calls.push({url,method,body:body?structuredClone(body):undefined});
      const filePath=new URL('http://local'+url).searchParams.get('path');
      if(method!=='PUT'&&options.onRead)await options.onRead(url);
      let saved=savedFiles.get(filePath)||{...structuredClone(template),path:filePath};
      if(method==='PUT'){
        if(options.onPut)await options.onPut(filePath);
        if(failure)throw new Error(failure);saved={...saved,table:structuredClone(body.table),revision:'b'.repeat(64)};savedFiles.set(filePath,saved);
      }
      return structuredClone(saved);
    },refreshFiles:async()=>{},notify(){},modal:async()=>allowDiscard};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/csv-editor.js'),'utf8'),context);
  function click(selector,dataset){return element('#csv-editor').handlers.click({target:{closest:s=>s===selector?{dataset}:null}});}
  return {element,calls,window,state,document,grids,open:(name='sales.csv',folder='Data')=>window.CsvEditor.open(name,folder),
    select(doc){state.active=doc;state.launcher=false;context.renderWorkspace();},
    launcher(){state.launcher=true;context.renderWorkspace();},
    input(row,col,value){const target={dataset:{csvRow:String(row),csvCol:String(col)},value};element('#csv-grid').handlers.input({target});return target;},
    action:name=>click('[data-csv-action]',{csvAction:name}),removeRow:row=>click('[data-csv-remove-row]',{csvRemoveRow:String(row)}),
    removeColumn:col=>click('[data-csv-remove-column]',{csvRemoveColumn:String(col)}),
    save:doc=>doc?window.CsvEditor.save(doc):element('#csv-save').onclick(),close:doc=>window.CsvEditor.close(doc),
    allowDiscard:value=>{allowDiscard=value;},failSave:value=>{failure=value;},setDisk:value=>{savedFiles.set(value.path,value);}};
}

test('editable CSV escapes values and saves exact text and format without starting a notebook kernel',async()=>{
  const h=harness([['id','notes'],['001','</textarea><script>alert(1)</script>']]);
  await h.open();
  assert.equal(h.element('#csv-editor').hidden,false);
  assert.equal(h.state.tabs.length,1);assert.equal(h.state.notebooks.length,0);
  assert.match(h.element('#csv-grid').innerHTML,/&lt;\/textarea&gt;&lt;script&gt;/);
  assert.doesNotMatch(h.element('#csv-grid').innerHTML,/<script>/);
  h.input(1,1,'first line\nquoted "value", semicolon;');
  assert.equal(h.element('#csv-state').textContent,'Unsaved changes');
  await h.save();
  const save=h.calls.find(c=>c.method==='PUT');
  assert.equal(save.body.revision,'a'.repeat(64));
  assert.equal(save.body.table.rows[1][0],'001');
  assert.equal(save.body.table.rows[1][1],'first line\nquoted "value", semicolon;');
  assert.equal(save.body.table.recordSeparator,'\r\n');
  assert.equal(save.body.table.bom,true);assert.equal(save.body.table.trailingNewline,false);
  assert.equal(h.element('#csv-state').textContent,'Saved');
  assert.ok(h.calls.every(c=>c.url.startsWith('/csv?path=Data%2Fsales.csv')));
});

test('Grid.js instances are disposed on page and tab changes and empty states remain editable',async()=>{
  const h=harness([]);await h.open();
  assert.match(h.element('#csv-grid').innerHTML,/Empty CSV/);
  const initial=h.grids.at(-1);
  h.action('add-row');assert.equal(initial.destroyed,true);
  assert.match(h.element('#csv-grid').innerHTML,/data-csv-row="0"/);
  const active=h.grids.at(-1);
  h.launcher();assert.equal(active.destroyed,true);
  await h.open();assert.notStrictEqual(h.grids.at(-1),active);
  h.element('#csv-find').handlers.input({target:{value:'missing'}});
  assert.match(h.element('#csv-grid').innerHTML,/No records match/);
  assert.equal(h.state.active.table.rows.length,1);
});

test('edits across pages and record/column operations preserve the full table',async()=>{
  const rows=Array.from({length:52},(_,i)=>[String(i).padStart(3,'0'),'text']);
  const h=harness(rows);await h.open();
  assert.doesNotMatch(h.element('#csv-grid').innerHTML,/data-csv-row="50"/);
  h.action('next');assert.match(h.element('#csv-grid').innerHTML,/data-csv-row="50"/);
  h.input(50,1,'edited on page two');
  h.action('add-column');h.action('add-row');
  h.removeRow(1);h.removeColumn(0);
  await h.save();
  const saved=h.calls.find(c=>c.method==='PUT').body.table.rows;
  assert.equal(saved.length,52);
  assert.equal(saved[49][0],'edited on page two');
  assert.equal(saved[0].length,2);
  assert.equal(saved[51].length,2);
});

test('CSV search spans pages, keeps original row identities, and clears the filter when closed',async()=>{
  const h=harness(Array.from({length:110},(_,i)=>[String(i),i%2?'match':'other']));await h.open();
  h.element('#csv-editor').handlers.keydown({ctrlKey:true,key:'f',preventDefault(){},stopPropagation(){}});
  assert.equal(h.element('#csv-search').hidden,false);
  h.element('#csv-find').handlers.input({target:{value:'MATCH'}});
  assert.match(h.element('#csv-page').textContent,/55 matching records/);
  assert.match(h.element('#csv-grid').innerHTML,/data-csv-row="1"/);
  assert.doesNotMatch(h.element('#csv-grid').innerHTML,/data-csv-row="0"/);
  h.action('next');assert.match(h.element('#csv-grid').innerHTML,/data-csv-row="101"/);
  h.input(101,1,'edited match');assert.equal(h.state.active.table.rows[101][1],'edited match');
  h.removeRow(103);assert.equal(h.state.active.table.rows[103][0],'104');
  await h.open('second.csv');assert.equal(h.element('#csv-search').hidden,true);assert.equal(h.element('#csv-find').value,'');
  await h.open();assert.equal(h.element('#csv-search').hidden,false);assert.equal(h.element('#csv-find').value,'MATCH');
  h.element('#csv-find-close').onclick();assert.equal(h.element('#csv-search').hidden,true);
  assert.equal(h.state.active.search,'');assert.match(h.element('#csv-grid').innerHTML,/data-csv-row="0"/);
  await h.save();assert.equal(h.calls.find(c=>c.method==='PUT').body.table.rows.length,109);
});

test('save conflicts keep edits and closing or reloading requires discarding unsaved changes',async()=>{
  const h=harness();await h.open();h.input(1,1,'my unsaved edit');
  h.failSave('CSV changed on disk');await h.save();
  assert.match(h.element('#csv-error').textContent,/changed on disk/);
  assert.equal(h.element('#csv-state').textContent,'Unsaved changes');
  h.allowDiscard(false);await h.close();assert.equal(h.element('#csv-editor').hidden,false);
  const before=h.calls.length;h.action('reload');await new Promise(resolve=>setImmediate(resolve));
  assert.equal(h.calls.length,before);
  h.failSave(null);await h.save();
  assert.equal(h.calls.filter(c=>c.method==='PUT')[1].body.table.rows[1][1],'my unsaved edit');
  await h.close();assert.equal(h.element('#csv-editor').hidden,true);
});

test('ragged records keep a valid empty field when a column is deleted and Ctrl-S saves',async()=>{
  const h=harness([['a','b'],['only-one']]);await h.open();h.removeColumn(0);
  let prevented=false;
  h.element('#csv-editor').handlers.keydown({ctrlKey:true,key:'s',preventDefault(){prevented=true;},stopPropagation(){}});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(prevented,true);
  assert.deepEqual(h.calls.find(c=>c.method==='PUT').body.table.rows,[['b'],['']]);
});

test('multiple CSV tabs retain independent edits, pagination and scroll without refetching or creating kernels',async()=>{
  const h=harness(Array.from({length:52},(_,i)=>[String(i),'value']));
  await h.open();const first=h.state.active;h.action('next');h.input(50,1,'first CSV draft');
  h.element('#csv-grid').scrollTop=123;h.element('#csv-grid').scrollLeft=45;
  await h.open('second.csv');const second=h.state.active;h.input(1,1,'second CSV draft');
  assert.notStrictEqual(first,second);assert.equal(h.state.tabs.length,2);
  h.launcher();assert.equal(h.element('#csv-editor').hidden,true);
  await h.open();
  assert.strictEqual(h.state.active,first);assert.equal(h.state.tabs.length,2);
  assert.equal(h.calls.filter(c=>!c.method).length,2);
  assert.equal(first.page,1);assert.equal(first.table.rows[50][1],'first CSV draft');
  assert.equal(second.table.rows[1][1],'second CSV draft');
  assert.equal(h.element('#csv-grid').scrollTop,123);assert.equal(h.element('#csv-grid').scrollLeft,45);
  assert.equal(h.state.notebooks.length,0);
  await h.save();assert.equal(h.calls.at(-1).body.table.rows[50][1],'first CSV draft');
  assert.equal(second.dirty,true);
});

test('a save completing in the background updates only its original tab',async()=>{
  let finish;
  const response=new Promise(resolve=>{finish=resolve;});
  const h=harness(undefined,{onPut:()=>response});await h.open();
  const first=h.state.active;h.input(1,1,'save me');const saving=h.save();
  await h.open('second.csv');const second=h.state.active;h.input(1,1,'keep me unsaved');
  finish();await saving;
  assert.strictEqual(h.state.active,second);
  assert.equal(first.dirty,false);assert.equal(second.dirty,true);
  assert.equal(second.table.rows[1][1],'keep me unsaved');
  assert.equal(h.element('#csv-title').textContent,'Data/second.csv');
  assert.equal(h.element('#csv-state').textContent,'Unsaved changes');
  assert.equal(h.calls.find(c=>c.method==='PUT').url,'/csv?path=Data%2Fsales.csv');
});

test('closing a background CSV does not select it, and closing the last CSV returns to a notebook',async()=>{
  const h=harness();await h.open();const first=h.state.active;h.input(1,1,'draft');
  await h.open('second.csv');const second=h.state.active;
  h.allowDiscard(false);await h.close(first);assert.strictEqual(h.state.active,second);assert.equal(h.state.tabs.length,2);
  h.allowDiscard(true);await h.close(first);assert.strictEqual(h.state.active,second);assert.equal(h.state.tabs.length,1);
  const notebook={uid:'java',name:'Analysis.jnb',path:'Analysis.jnb'};
  h.state.tabs.unshift(notebook);h.state.notebooks.push(notebook);
  await h.close(second);
  assert.strictEqual(h.state.active,notebook);assert.equal(h.state.launcher,false);
  assert.equal(h.element('#csv-editor').hidden,true);
});

test('a reload finishing after a tab switch updates only the reloaded document',async()=>{
  let finish;
  const response=new Promise(resolve=>{finish=resolve;});
  const h=harness(undefined,{onRead:url=>url.includes('&delimiter=')?response:undefined});
  await h.open();const first=h.state.active;
  h.setDisk({path:first.path,revision:'c'.repeat(64),table:{...first.table,rows:[['id','notes'],['001','fresh disk value']]}});
  h.action('reload');await new Promise(resolve=>setImmediate(resolve));
  await h.open('second.csv');const second=h.state.active;h.input(1,1,'keep second draft');
  finish();await new Promise(resolve=>setImmediate(resolve));
  assert.strictEqual(h.state.active,second);
  assert.equal(first.table.rows[1][1],'fresh disk value');assert.equal(first.dirty,false);
  assert.equal(second.table.rows[1][1],'keep second draft');assert.equal(second.dirty,true);
  assert.equal(h.element('#csv-title').textContent,'Data/second.csv');
});
