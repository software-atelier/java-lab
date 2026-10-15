const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');

const code=fs.readFileSync(path.join(__dirname,'../../main/resources/static/editor.js'),'utf8');

function makeElement(tag){
  const listeners={};
  const element={
    tag,listeners,style:{},children:[],removed:0,attributes:{},parent:null,
    className:'',textContent:'',innerHTML:'',isConnected:true,
    value:'',selectionStart:0,selectionEnd:0,scrollLeft:0,scrollTop:0,
    append(child){element.children.push(child);child.parent=element;},
    remove(){element.removed++;if(element.parent){const index=element.parent.children.indexOf(element);if(index>=0)element.parent.children.splice(index,1);element.parent=null;}},
    addEventListener(type,fn){(listeners[type] ||= []).push(fn);},
    removeEventListener(){},
    dispatchEvent(){return true;},
    setAttribute(name,value){element.attributes[name]=String(value);},
    removeAttribute(name){delete element.attributes[name];},
    querySelector(){return null;},
    querySelectorAll(){return [];},
    getBoundingClientRect(){return {left:0,top:0,bottom:0,right:0,width:0,height:0};},
    scrollIntoView(){},
    closest(){return {};},
    matches(){return true;},
    focus(){},setRangeText(){},setSelectionRange(){},
  };
  return element;
}

function load() {
  const documentListeners={};
  const doc={
    addEventListener(type,fn){(documentListeners[type] ||= []).push(fn);},
    createElement:makeElement,
    body:makeElement('body'),
    activeElement:null,
  };
  const cellsElement=makeElement('cells'), scrollElement=makeElement('scroll');
  const calls=[],responses=[],notified=[];
  const api=async(url,method,body)=>{calls.push({url,method,body});const next=responses.shift();if(next instanceof Error)throw next;return next;};
  const notebook={kernel:'kernel-1',running:false};
  const sandbox={
    window:{addEventListener(){}},
    document:doc,
    active:()=>notebook,
    api,
    notify:message=>notified.push(message),
    $:(selector,root)=>root!==undefined?root.querySelector(selector):selector==='#cells'?cellsElement:selector==='#notebook-scroll'?scrollElement:null,
    innerWidth:1000,innerHeight:600,
    getComputedStyle:()=>({fontFamily:'',fontSize:'',fontWeight:'',lineHeight:'',letterSpacing:'',padding:'',tabSize:''}),
    escapeHtml:value=>String(value??''),
    setTimeout,clearTimeout,
  };
  vm.createContext(sandbox);
  vm.runInNewContext(code,sandbox);
  const editor=makeElement('textarea');
  const fire=()=>{for(const handler of cellsElement.listeners.input)handler({target:editor,isComposing:false});};
  return {editor,fire,calls,responses,notified,body:doc.body,doc};
}

const flush=()=>new Promise(setImmediate);

async function open(h,t,suggestions=[{text:'out'}]) {
  h.doc.activeElement=h.editor;
  h.editor.value='System.';h.editor.selectionStart=7;h.editor.selectionEnd=7;
  h.fire();
  h.responses.push({anchor:7,suggestions});
  t.mock.timers.tick(300);
  await flush();
  assert.equal(h.calls.length,1);
  assert.equal(JSON.stringify(h.calls[0].body),JSON.stringify({code:'System.',cursor:7}));
  assert.equal(h.editor.attributes['aria-expanded'],'true');
  return h.body.children.find(element=>element.className==='completion-popup');
}

test('a single letter after a dot re-requests completion instead of dropping it',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});
  const h=load();
  const menu=await open(h,t);
  assert.ok(menu);

  h.editor.value='System.o';h.editor.selectionStart=8;h.editor.selectionEnd=8;
  h.fire();
  assert.equal(h.calls.length,1); // no immediate network call
  h.responses.push({anchor:8,suggestions:[{text:'out'}]});
  t.mock.timers.tick(80);
  await flush();
  assert.equal(h.calls.length,2);
  assert.equal(JSON.stringify(h.calls[1].body),JSON.stringify({code:'System.o',cursor:8}));
  assert.equal(h.editor.attributes['aria-expanded'],'true');
  assert.equal(menu.removed,0);
});

test('the popup stays open and updates while typing more letters',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});
  const h=load();
  const menu=await open(h,t);

  h.editor.value='System.ou';h.editor.selectionStart=9;h.editor.selectionEnd=9;
  h.fire();
  assert.equal(menu.removed,0); // kept visible during the refresh wait
  h.responses.push({anchor:8,suggestions:[{text:'out'},{text:'output'}]});
  t.mock.timers.tick(80);
  await flush();
  assert.equal(h.calls.length,2);
  assert.equal(JSON.stringify(h.calls[1].body),JSON.stringify({code:'System.ou',cursor:9}));
  assert.equal(menu.removed,0);
  assert.equal(h.editor.attributes['aria-expanded'],'true');
});

test('a refresh without suggestions closes the popup',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});
  const h=load();
  const menu=await open(h,t);

  h.editor.value='System.z';h.editor.selectionStart=8;h.editor.selectionEnd=8;
  h.fire();
  h.responses.push({anchor:8,suggestions:[]});
  t.mock.timers.tick(80);
  await flush();
  assert.equal(h.calls.length,2);
  assert.equal(menu.removed,1);
  assert.equal(h.editor.attributes['aria-expanded'],'false');
});

test('typing a non-identifier character dismisses immediately without a request',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});
  const h=load();
  const menu=await open(h,t);

  h.editor.value='System.o ';h.editor.selectionStart=9;h.editor.selectionEnd=9;
  h.fire();
  assert.equal(menu.removed,1);
  assert.equal(h.editor.attributes['aria-expanded'],'false');
  t.mock.timers.tick(500);
  await flush();
  assert.equal(h.calls.length,1); // no follow-up request for the space
});

test('a single identifier letter from scratch requests completion',async t=>{
  t.mock.timers.enable({apis:['setTimeout']});
  const h=load();
  h.doc.activeElement=h.editor;
  h.editor.value='o';h.editor.selectionStart=1;h.editor.selectionEnd=1;
  h.fire();
  h.responses.push({anchor:0,suggestions:[]});
  t.mock.timers.tick(300);
  await flush();
  assert.equal(h.calls.length,1);
  assert.equal(JSON.stringify(h.calls[0].body),JSON.stringify({code:'o',cursor:1}));
});
