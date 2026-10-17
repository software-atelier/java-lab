const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const app=fs.readFileSync(path.join(__dirname,'../../main/resources/static/app.js'),'utf8');
const source=app.slice(app.indexOf('async function renameFile('),app.indexOf('let editingTitle=',app.indexOf('async function renameFile(')));
function harness(type='text',resultType=type){
  const doc={uid:'file',type,name:'old.json',path:'Data/old.json',folder:'Data',dirty:true,format:'json',content:'draft',kernel:'same-kernel'},calls=[],state={tabs:[doc],notebooks:type==='notebook'?[doc]:[],active:doc,launcher:false};let saved=true;
  const context={state,openDocuments:()=>state.tabs,activeDocument:()=>state.launcher?null:state.active,notebookPath:(folder,name)=>folder?folder+'/'+name:name,
    modal:async()=>null,saveDocument:async target=>{calls.push(['save',target.path]);return saved;},saveNotebook:async target=>calls.push(['save-java',target.path]),
    api:async(url,method,body)=>{calls.push([url,method,body]);return {path:'Data/new.xml',name:'new.xml',type:resultType,format:resultType==='text'?'xml':null};},
    window:{TextEditor:{refresh:target=>calls.push(['refresh-editor',target.uid])},ImageViewer:{refresh(){}}},
    refreshFiles:async()=>{},renderWorkspace(){},renderTabs(){},updateStatus(){},markDirty(){},notify(){},
    openFile:async(name,folder,type,select)=>{const replacement={uid:'new',name,folder,type,path:folder+'/'+name};state.tabs.push(replacement);if(select)state.active=replacement;calls.push(['open',select]);return replacement;}};
  vm.runInNewContext(source,context);return {doc,state,calls,rename:()=>context.renameFileTo('old.json','Data','new.xml'),failSave:()=>{saved=false;}};
}
test('renaming an open text file saves first and keeps tab identity, draft history and new syntax format',async()=>{
  const h=harness();const identity=h.doc;await h.rename();
  assert.equal(h.calls[0][0],'save');assert.strictEqual(h.state.tabs[0],identity);assert.strictEqual(h.state.active,identity);
  assert.equal(identity.path,'Data/new.xml');assert.equal(identity.format,'xml');assert.equal(identity.content,'draft');
});
test('save failure prevents rename and preserves the original file/tab path',async()=>{
  const h=harness();h.failSave();await assert.rejects(h.rename(),/Save the file/);assert.equal(h.doc.path,'Data/old.json');assert.ok(!h.calls.some(c=>c[0]==='/files/rename'));
});
test('notebook rename saves without replacing its kernel or document identity',async()=>{
  const h=harness('notebook');await h.rename();assert.strictEqual(h.state.tabs[0],h.doc);assert.equal(h.doc.kernel,'same-kernel');assert.equal(h.calls[0][0],'save-java');
});
test('changing a background document type opens its replacement without selecting it',async()=>{
  const h=harness('text','image'),other={uid:'other',type:'csv'};h.state.tabs.push(other);h.state.active=other;
  await h.rename();assert.strictEqual(h.state.active,other);assert.equal(h.state.tabs[0].type,'image');assert.deepEqual(h.calls.find(c=>c[0]==='open'),['open',false]);
});
