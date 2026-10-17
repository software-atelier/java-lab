const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const resources=path.join(__dirname,'../../main/resources/static');
const script=fs.readFileSync(path.join(resources,'help.js'),'utf8');
const index=fs.readFileSync(path.join(resources,'index.html'),'utf8');
const escapeHtml=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

function harness(shortcuts=[['Both','Shift Enter','Run selected cells'],['Application','Ctrl / Cmd S','Save active file']]){
  const nodes=new Map(),document={activeElement:null},actions={};
  function element(selector){
    if(!nodes.has(selector))nodes.set(selector,{value:'',textContent:'',innerHTML:'',scrollTop:100,open:false,isConnected:true,showCount:0,handlers:{},
      addEventListener(name,fn){this.handlers[name]=fn;},showModal(){this.open=true;this.showCount++;},close(){this.open=false;},
      focus(){document.activeElement=this;},closest(){return null;}});
    return nodes.get(selector);
  }
  const opener=element('opener');document.activeElement=opener;
  const context={window:{NotebookControls:{shortcuts}},document,actions,$:element,escapeHtml};
  vm.runInNewContext(script,context);
  return {element,actions,context,document,opener,help:context.window.LabHelp,
    search(query){element('#help-search').value=query;element('#help-search').handlers.input();},
    topic(id){element('#help-topics').handlers.click({target:{closest:()=>({dataset:{helpTopic:id}})}});}};
}

test('every Help menu item opens a real topic in the same offline dialog',()=>{
  const h=harness();
  const items=[...new Set([...index.matchAll(/data-action="(help(?:-[a-z-]+)?)"/g)].map(m=>m[1]))];
  assert.equal(items.length,15);assert.deepEqual([...items].sort(),Object.keys(h.actions).sort());
  for(const action of items){
    h.actions[action]();
    assert.equal(h.element('#help-dialog').open,true);
    assert.ok(h.element('#help-topic-title').textContent.length>0);
    assert.ok(h.element('#help-topic-body').innerHTML.length>200);
    const id=action==='help'?'overview':action.slice(5);
    assert.match(h.element('#help-topics').innerHTML,new RegExp(`data-help-topic="${id}" aria-current="page"`));
  }
  assert.equal(h.element('#help-dialog').showCount,1,'selecting another help topic must not open a second modal');
  assert.equal(h.element('#help-content').scrollTop,0);
});

test('help search finds content and live shortcuts, handles no matches, and does not render query HTML',()=>{
  const h=harness();h.help.open();
  h.search('Csv.save');assert.match(h.element('#help-topics').innerHTML,/data-help-topic="csv"/);
  h.search('AI_TIMEOUT');assert.match(h.element('#help-topics').innerHTML,/data-help-topic="ai-setup"/);
  h.context.window.NotebookControls.shortcuts.push(['Edit','UniqueKey','Unique live binding']);
  h.search('Unique live binding');assert.match(h.element('#help-topics').innerHTML,/data-help-topic="shortcuts"/);
  h.search('<img src=x onerror=alert(1)>');
  assert.match(h.element('#help-topics').innerHTML,/No matching topics/);
  assert.doesNotMatch(h.element('#help-topics').innerHTML,/<img/);
  assert.equal(h.element('#help-result-count').textContent,'0 of 15 topics');
  h.help.open('csv');assert.equal(h.element('#help-search').value,'');
  assert.equal(h.element('#help-result-count').textContent,'15 of 15 topics');
});

test('topic navigation and cross-links reset the reading position and focus the selected heading',()=>{
  const h=harness();h.help.open('files');h.search('CSV');h.topic('csv');
  assert.equal(h.element('#help-topic-title').textContent,'CSV files & helper');
  assert.match(h.element('#help-topic-body').innerHTML,/Csv\.load/);
  h.element('#help-content').scrollTop=300;
  let prevented=false;
  h.element('#help-topic-body').handlers.click({preventDefault(){prevented=true;},target:{closest:()=>({getAttribute:()=>'#help-settings'})}});
  assert.equal(prevented,true);assert.equal(h.element('#help-search').value,'');
  assert.equal(h.element('#help-topic-title').textContent,'Variables & libraries');
  assert.equal(h.element('#help-content').scrollTop,0);
  assert.strictEqual(h.document.activeElement,h.element('#help-topic-title'));
});

test('shortcut tables use all current bindings and escape shortcut labels and keys',()=>{
  const controls=fs.readFileSync(path.join(resources,'notebook-controls.js'),'utf8');
  const start=controls.indexOf('const shortcuts='),end=controls.indexOf('function nstate',start);
  const shortcuts=vm.runInNewContext(`(()=>{${controls.slice(start,end)}return shortcuts;})()`);
  const h=harness(shortcuts);h.help.open('shortcuts');
  assert.equal((h.element('#help-shortcut-list').innerHTML.match(/class="shortcut-row"/g)||[]).length,shortcuts.length);
  assert.match(h.element('#help-shortcut-list').innerHTML,/Save active file/);
  shortcuts.push(['Edit','<Key>','<img src=x onerror=alert(1)>']);h.help.open('shortcuts');
  assert.match(h.element('#help-shortcut-list').innerHTML,/&lt;Key&gt;/);
  assert.match(h.element('#help-shortcut-list').innerHTML,/&lt;img/);
  assert.doesNotMatch(h.element('#help-shortcut-list').innerHTML,/<img/);
});

test('Escape and Done close only help and return focus to its opener',()=>{
  const h=harness();h.help.open('execution');
  let prevented=false;h.element('#help-dialog').handlers.cancel({preventDefault(){prevented=true;}});
  assert.equal(prevented,true);assert.equal(h.element('#help-dialog').open,false);
  assert.strictEqual(h.document.activeElement,h.opener);
  h.help.open('ai');h.element('#help-done').onclick();
  assert.equal(h.element('#help-dialog').open,false);assert.strictEqual(h.document.activeElement,h.opener);
});

test('menu help restores focus to the visible menu trigger instead of a hidden menu item',()=>{
  const h=harness(),trigger=h.element('help menu trigger');
  h.document.activeElement={closest:()=>({querySelector:()=>trigger})};
  h.help.open('csv');h.element('#help-close').onclick();
  assert.strictEqual(h.document.activeElement,trigger);
});

test('configuration help documents every application environment placeholder and runtime variable read directly',()=>{
  const h=harness();h.help.open('configuration');const html=h.element('#help-topic-body').innerHTML;
  const properties=fs.readFileSync(path.join(resources,'../application.properties'),'utf8');
  const names=[...properties.matchAll(/\$\{([A-Z][A-Z0-9_]*):/g)].map(m=>m[1]);
  for(const name of [...names,'SERVER_PORT','SERVER_ADDRESS','SERVER_SERVLET_CONTEXT_PATH','MAVEN_HOME','M2_HOME','DISPLAY','WAYLAND_DISPLAY','JAVA_HOME','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','MAVEN_OPTS','PATH'])assert.ok(html.includes(name),name);
  assert.match(html,/LAB_DATA_DIR=\./);assert.match(html,/workingDir/);
});
