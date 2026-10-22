const {test}=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
function harness(options={}){
  class Element{
    constructor(tag='div'){this.tag=tag;this.children=[];this.value='';this.hidden=false;this.disabled=false;this.selectionStart=0;this.selectionEnd=0;this.checked=false;this.open=false;const classes=new Set();this.classList={toggle(name,value){if(value??!classes.has(name))classes.add(name);else classes.delete(name);},contains:name=>classes.has(name)};}
    showModal(){this.open=true;}close(){this.open=false;}
    append(...nodes){this.children.push(...nodes);}replaceChildren(...nodes){this.children=nodes;}setAttribute(){}focus(){}
    querySelectorAll(selector){return this.children.flatMap(child=>[...(selector.includes(child.tag)?[child]:[]),...child.querySelectorAll(selector)]);}
    createTHead(){const head=new Element('thead');this.append(head);return head;}createTBody(){const body=new Element('tbody');this.append(body);return body;}
    insertRow(){const row=new Element('tr');this.append(row);return row;}insertCell(){const cell=new Element('td');this.append(cell);return cell;}
  }
  const nodes=new Map(),calls=[],notices=[];let current=null,refreshed=0;
  const $=id=>{if(!nodes.has(id))nodes.set(id,new Element());return nodes.get(id);};
  const config={maven:'org.example:jdbc:1.0',url:'jdbc:example:db',credentialId:'11111111-1111-1111-1111-111111111111'};
  const result={id:'result-1',tables:[{columns:['<script>'],rows:[['<img src=x>'],[null]],truncated:false}],updateCounts:[]};
   const context={window:{Vibecode:{canUndoSql:()=>false,openSql:(doc,input)=>calls.push({vibe:doc,start:input.selectionStart,end:input.selectionEnd})}},$,document:{createElement:tag=>new Element(tag)},crypto:{randomUUID:()=>config.credentialId},
    refreshFiles:async()=>{refreshed++;},notify:message=>notices.push(message),
    TextEditor:{setBusy(doc,value){doc.busy=value;if(doc===current)context.window.SqlEditor.render(doc);},reportError(message,doc){doc.error=message;},configureSql(doc,config){doc.sqlConfig=config;doc.dirty=true;},save:async doc=>{calls.push({save:doc});return true;}},
    api:async(url,method,body)=>{calls.push({url,method,body});if(options.api)return options.api(url,method,body);if(url==='/sql/execute')return result;if(url==='/sql/export')return {path:'queries/query-result.csv'};return {user:'alice',passwordStored:true};}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/sql-editor.js'),'utf8'),context);
  return {$,calls,notices,config,result,refreshed:()=>refreshed,
    doc:()=>({format:'sql',path:'queries/query.sql',content:'select 1; select 2;',sqlConfig:config,busy:false}),
    activate(doc){current=doc;context.window.SqlEditor.render(doc);},run:()=>context.window.SqlEditor.run(),
    submit:()=>$('#sql-configuration').onsubmit({preventDefault(){}})};
}
test('SQL runs the selected draft and safely renders tables and NULL values below the editor',async()=>{
  const h=harness(),doc=h.doc();h.activate(doc);await Promise.resolve();h.$('#text-input').selectionStart=10;h.$('#text-input').selectionEnd=19;
  await h.run();assert.equal(h.calls.find(call=>call.url==='/sql/execute').body.sql,'select 2;');assert.equal(doc.busy,false);
  assert.equal(h.$('#sql-results').hidden,false);const host=h.$('#sql-results');
  const headers=host.querySelectorAll('th');assert.equal(headers[0].textContent,'<script>');assert.equal(headers[0].innerHTML,undefined);
  const cells=host.querySelectorAll('td');assert.equal(cells[0].textContent,'<img src=x>');assert.equal(cells[1].textContent,'NULL');
  await host.querySelectorAll('button')[0].onclick();assert.equal(h.calls.filter(call=>call.url==='/sql/execute').length,1);
  const exported=h.calls.find(call=>call.url==='/sql/export').body;assert.equal(exported.path,doc.path);assert.equal(exported.resultId,'result-1');assert.equal(h.refreshed(),1);
});
test('a completed background execution stays with its original SQL tab',async()=>{
  let finish;const waiting=new Promise(resolve=>{finish=resolve;});const h=harness({api:async url=>url==='/sql/execute'?waiting:{user:'alice',passwordStored:true}});
  const first=h.doc();h.activate(first);const running=h.run();const second={...h.doc(),path:'other.sql'};h.activate(second);
  finish(h.result);await running;assert.equal(first.sqlResult,h.result);assert.equal(second.sqlResult,undefined);assert.equal(h.$('#sql-results').hidden,true);
  h.activate(first);assert.equal(h.$('#sql-results').hidden,false);
});
test('connection setup stores credentials separately and saves only portable metadata in the SQL document',async()=>{
  const h=harness(),doc={...h.doc(),sqlConfig:null};h.activate(doc);await h.run();assert.equal(h.$('#sql-settings-dialog').open,true);
  h.$('#sql-maven').value=h.config.maven;h.$('#sql-url').value=h.config.url;h.$('#sql-user').value='alice';h.$('#sql-password').value='secret';
  await h.submit();const credential=h.calls.find(call=>call.method==='PUT');assert.equal(credential.body.password,'secret');
  assert.equal(doc.sqlConfig.maven,h.config.maven);assert.doesNotMatch(JSON.stringify(doc.sqlConfig),/alice|secret|password/);
  assert.equal(h.calls.at(-1).save,doc);assert.equal(h.$('#sql-password').value,'');assert.equal(doc.busy,false);
});
test('failed SQL retains the prior snapshot and restores the editor controls',async()=>{
  const h=harness({api:async url=>{if(url==='/sql/execute')throw Error('SQL failed');return {user:'alice',passwordStored:true};}}),doc=h.doc();doc.sqlResult=h.result;h.activate(doc);
  await h.run();assert.equal(doc.error,'SQL failed');assert.equal(doc.sqlResult,h.result);assert.equal(doc.busy,false);
});
test('table browser loads columns lazily and renders database identifiers as text',async()=>{
  const table={catalog:null,schema:'public',name:'<people>',type:'TABLE'};
  const h=harness({api:async url=>url==='/sql/schema'?{product:'ExampleDB',tables:[table],truncated:false}:url==='/sql/columns'?[{name:'<name>',type:'VARCHAR',size:50,nullable:false}]:{user:'alice',passwordStored:true}});
  h.activate(h.doc());await h.$('#sql-browse').onclick();assert.equal(h.$('#sql-browser').open,true);
  const detail=h.$('#sql-tables').children[0];assert.equal(detail.children[0].textContent,'public.<people> · TABLE');
  assert.equal(h.calls.filter(c=>c.url==='/sql/columns').length,0);detail.open=true;await detail.ontoggle();
  assert.equal(detail.children[1].children[0].textContent,'<name> · VARCHAR (50) · NOT NULL');
  await detail.ontoggle();assert.equal(h.calls.filter(c=>c.url==='/sql/columns').length,1);
  h.$('#sql-table-filter').value='missing';h.$('#sql-table-filter').oninput();assert.equal(h.$('#sql-tables').children.length,0);
});
test('compact actions open the shared Vibecode dialog with selection and settings close on tab switch',async()=>{
  const h=harness(),first=h.doc();h.activate(first);
  h.$('#text-input').selectionStart=10;h.$('#text-input').selectionEnd=19;h.$('#sql-vibecode').onclick();
  const call=h.calls.find(c=>c.vibe);assert.equal(call.vibe,first);assert.equal(call.start,10);assert.equal(call.end,19);
  h.$('#sql-configure').onclick();assert.equal(h.$('#sql-settings-dialog').open,true);
  h.activate({...h.doc(),path:'other.sql'});assert.equal(h.$('#sql-settings-dialog').open,false);
  h.activate(null);assert.equal(h.$('#text-editor').classList.contains('sql-mode'),false);
});
