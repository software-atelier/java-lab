/* Native text input with a synchronized, escaped highlight layer; no external editor runtime. */
window.TextEditor=(()=>{
  const pane=$('#text-editor'),input=$('#text-input'),opening=new Map();let current=null;
  const endpoint=path=>'/files/text?path='+encodeURIComponent(path);
  const chrome=()=>{renderTabs();updateStatus();};
  const dirty=doc=>doc.content!==doc.savedContent||JSON.stringify(doc.sqlConfig)!==JSON.stringify(doc.savedSqlConfig);
  function status(){
    if(!current)return;
    $('#text-state').textContent=current.busy?'Working…':current.dirty?'Unsaved changes':'Saved';
    $('#text-save').disabled=current.busy||!current.dirty;
    $('#text-error').textContent=current.error||'';$('#text-error').hidden=!current.error;
  }
  function error(message,doc=current){doc.error=message;if(doc===current)status();chrome();}
  function remember(){if(current){current.cursor={start:input.selectionStart,end:input.selectionEnd};current.scroll={top:input.scrollTop,left:input.scrollLeft};current.findQuery=$('#text-find').value;current.findStatus=$('#text-find-status').textContent;}}
  function syncScroll(){const transform=`translate(${-input.scrollLeft}px,${-input.scrollTop}px)`;$('#text-highlight').style.transform=transform;$('#text-line-numbers').style.transform=`translateY(${-input.scrollTop}px)`;}
  function paint(){
    const value=current.content;
    $('#text-highlight').innerHTML=TextEditing.highlight(value,current.format)+'\n';
    $('#text-line-numbers').textContent=value.split('\n').map((_,i)=>i+1).join('\n');
    $('#text-info').textContent=`${current.format.toUpperCase()} · UTF-8${current.bom?' BOM':''} · ${current.recordSeparator==='\r\n'?'CRLF':current.recordSeparator==='\r'?'CR':'LF'} · ${value.length} characters${value.length>300000?' · large file: plain highlighting':''}`;
    syncScroll();status();
  }
  function render(){
    $('#text-title').textContent=current.path;input.value=current.content;
    $('#text-find').value=current.findQuery||'';$('#text-find-status').textContent=current.findStatus||'';
    searchVisibility();$('#text-language').textContent=current.format.toUpperCase();
    input.setSelectionRange(current.cursor?.start||0,current.cursor?.end||0);input.scrollTop=current.scroll?.top||0;input.scrollLeft=current.scroll?.left||0;
    pane.querySelectorAll('button,input,textarea').forEach(control=>control.disabled=current.busy);
    $('#text-format').disabled=current.busy||!['json','xml'].includes(current.format);
    $('#text-validate').disabled=current.busy||!['json','xml','yaml'].includes(current.format);
    $('#text-format').hidden=!['json','xml'].includes(current.format);
    $('#text-validate').hidden=!['json','xml','yaml'].includes(current.format);
    const ai=['json','xml','yaml','text','markdown'].includes(current.format);
    $('#text-vibecode').hidden=!ai;$('#text-undo-ai').hidden=!ai||!window.Vibecode?.canUndoText(current);
    paint();window.SqlEditor?.render(current);
  }
  function activate(doc){
    if(current===doc){if(doc){$('#text-title').textContent=doc.path;status();}return;}
    remember();current=doc;if(!doc){window.SqlEditor?.render(null);return;}render();
    input.setSelectionRange(doc.cursor?.start||0,doc.cursor?.end||0);input.scrollTop=doc.scroll?.top||0;input.scrollLeft=doc.scroll?.left||0;
    input.focus({preventScroll:true});syncScroll();
  }
  async function open(name,folder='',select=true){
    const path=notebookPath(folder,name);let doc=openDocuments().find(item=>item.type==='text'&&item.path===path);
    if(!doc){
      if(!opening.has(path))opening.set(path,(async()=>{
        const loaded=await api(endpoint(path));
        const created={...loaded,type:'text',name,folder,uid:crypto.randomUUID(),dirty:false,busy:false,error:null,savedContent:loaded.content,savedSqlConfig:loaded.sqlConfig,undo:[],redo:[]};
        openDocuments().push(created);return created;
      })());
      try{doc=await opening.get(path);}finally{opening.delete(path);}
    }
    if(select){state.active=doc;state.launcher=false;renderWorkspace();}else renderTabs();return doc;
  }
  async function discardAllowed(doc){return !doc.dirty||await modal('Discard text edits?',`Unsaved changes to <strong>${escapeHtml(doc.path)}</strong> will be discarded.`,'Discard');}
  async function close(doc=current){
    if(!doc)return;if(doc.savePromise&&!await doc.savePromise)return;
    if(doc.busy){notify('Wait for the file operation to finish before closing.',true);return;}
    if(!await discardAllowed(doc))return;
    window.NotebookControls?.closed(doc);state.tabs=openDocuments().filter(item=>item!==doc);
    if(state.active===doc){state.active=openDocuments().at(-1)||null;state.launcher=!state.active;}
    renderWorkspace();
  }
  function setBusy(doc,value){doc.busy=value;if(current===doc)render();chrome();}
  async function save(doc=current){
    if(!doc)return false;if(doc.savePromise)return doc.savePromise;if(doc.busy)return false;if(!doc.dirty)return true;
    if(current===doc)remember();doc.error=null;setBusy(doc,true);
    doc.savePromise=(async()=>{
      try{
        const loaded=await api(endpoint(doc.path),'PUT',{content:doc.content,bom:doc.bom,recordSeparator:doc.recordSeparator,revision:doc.revision,sqlConfig:doc.sqlConfig});
        Object.assign(doc,loaded,{dirty:false,savedContent:loaded.content,savedSqlConfig:loaded.sqlConfig,lastInput:0});await refreshFiles();notify('File saved');return true;
      }catch(e){error(e.message,doc);if(current!==doc)notify('File save failed: '+e.message,true);return false;}
      finally{doc.savePromise=null;setBusy(doc,false);}
    })();return doc.savePromise;
  }
  async function reload(){
    const doc=current;if(!doc||doc.busy||!await discardAllowed(doc))return;
    doc.error=null;setBusy(doc,true);
    try{const loaded=await api(endpoint(doc.path));Object.assign(doc,loaded,{dirty:false,savedContent:loaded.content,savedSqlConfig:loaded.sqlConfig,sqlResult:null,sqlSchema:null,sqlColumns:null,sqlDraft:null,undo:[],redo:[],cursor:null,scroll:null,lastInput:0});}
    catch(e){error(e.message,doc);}finally{setBusy(doc,false);}
  }
  function snapshot(){return {content:current.content,start:current.beforeSelection?.start??input.selectionStart,end:current.beforeSelection?.end??input.selectionEnd};}
  function trimHistory(doc){let size=doc.undo.reduce((n,item)=>n+item.content.length,0)+doc.redo.reduce((n,item)=>n+item.content.length,0);while(doc.undo.length&&(doc.undo.length>50||size>4000000))size-=doc.undo.shift().content.length;}
  function commit(content,start,end,typing=false){
    if(content.length>2000000){error('Text editor limit: 2 MB');input.value=current.content;return;}
    if(content!==current.content){
      if(!typing||!current.lastInput||Date.now()-current.lastInput>600||current.beforeSelection?.start!==current.cursor?.end||current.beforeSelection?.start!==current.beforeSelection?.end)current.undo.push(snapshot());
      current.redo=[];current.content=content;current.lastInput=typing?Date.now():0;trimHistory(current);
    }
    input.value=content;if(Number.isInteger(start))input.setSelectionRange(start,end);
    current.beforeSelection=null;current.cursor={start:input.selectionStart,end:input.selectionEnd};
    current.dirty=dirty(current);current.error=null;current.findStatus='';$('#text-find-status').textContent='';paint();chrome();
  }
  function applySql(doc,draft){
    if(doc.busy||doc.content!==draft.source){error('The source changed. Generate a fresh draft before applying.',doc);return false;}
    const start=draft.start??0,end=draft.end??doc.content.length;
    const content=doc.content.slice(0,start)+draft.code+doc.content.slice(end);
    if(content.length>2000000){error('Text editor limit: 2 MB',doc);return false;}
    doc.undo.push({content:doc.content,start,end});doc.redo=[];doc.content=content;doc.cursor={start,end:start+draft.code.length};doc.lastInput=0;
    trimHistory(doc);doc.dirty=dirty(doc);doc.error=null;if(current===doc)render();chrome();return true;
  }
  function undo(redo=false){
    const from=redo?current.redo:current.undo,to=redo?current.undo:current.redo,item=from.pop();if(!item)return;
    to.push({content:current.content,start:input.selectionStart,end:input.selectionEnd});current.content=item.content;current.lastInput=0;
    input.value=item.content;input.setSelectionRange(item.start,item.end);current.beforeSelection=null;current.cursor={start:item.start,end:item.end};current.dirty=dirty(current);current.error=null;paint();chrome();
  }
  function xmlDocument(text){
    if(/<!DOCTYPE|<!ENTITY/i.test(text.replace(/<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?\]\]>/g,'')))throw new Error('DTD/entity declarations are not supported by validation/formatting. You can still save the file as text.');
    const parsed=new DOMParser().parseFromString(text,'application/xml');
    const failure=parsed.getElementsByTagNameNS('http://www.mozilla.org/newlayout/xml/parsererror.xml','parsererror')[0]
      ||[...parsed.getElementsByTagName('parsererror')].find(node=>/error on line|XML Parsing Error|parse error/i.test(node.textContent));
    if(failure)throw new Error('Invalid XML: '+failure.textContent.slice(0,400));
    return parsed;
  }
  function formatXml(text){
    const parsed=xmlDocument(text);
    let nodes=0;
    function indent(node,level,preserve=false){
      if(level>100||++nodes>20000)throw new Error('XML is too large or deeply nested to format; save or edit it as text');
      preserve=preserve||node.getAttribute?.('xml:space')==='preserve';
      const mixed=[...node.childNodes].some(child=>child.nodeType===4||child.nodeType===3&&child.textContent.trim());
      for(const child of [...node.childNodes])if(child.nodeType===1)indent(child,level+1,preserve||mixed);
      if(preserve||mixed)return;
      for(const child of [...node.childNodes])if(child.nodeType===3&&!child.textContent.trim())node.removeChild(child);
      const children=[...node.childNodes];if(!children.length)return;
      for(const child of children)node.insertBefore(parsed.createTextNode('\n'+'  '.repeat(level+1)),child);
      node.appendChild(parsed.createTextNode('\n'+'  '.repeat(level)));
    }
    indent(parsed.documentElement,0);
    let output=new XMLSerializer().serializeToString(parsed);
    const declaration=text.match(/^(<\?xml[^>]*\?>)/)?.[1];if(declaration&&!output.startsWith('<?xml'))output=declaration+'\n'+output;
    return output+(/\n$/.test(text)?'\n':'');
  }
  async function validate(format=false){
    if(!current||current.busy)return;
    const doc=current,source=doc.content;
    if(format&&!['json','xml'].includes(doc.format))return;
    if(['xml','yaml'].includes(doc.format)){
      const language=doc.format.toUpperCase();
      remember();doc.error=null;setBusy(doc,true);
      try{
        const result=await api('/files/validate-'+doc.format,'POST',{content:source});
        if(!result.valid)throw new Error(`Invalid ${language}${result.line?' at line '+result.line+', column '+result.column:''}: ${result.message}`);
        if(format){
          const value=formatXml(source);
          if(current===doc)commit(value,0,0);
          else{doc.undo.push({content:source,start:doc.cursor?.start||0,end:doc.cursor?.end||0});doc.redo=[];doc.content=value;doc.dirty=value!==doc.savedContent;doc.cursor={start:0,end:0};trimHistory(doc);}
        }else notify('Valid '+language+': '+doc.name);
      }catch(e){error(e.message,doc);}
      finally{setBusy(doc,false);}
      return;
    }
    try{
      let value=current.content;
      if(current.format==='json'){if(format)value=TextEditing.formatJson(value);else JSON.parse(value);}
      else return;
      if(format)commit(value,0,0);else{current.error=null;status();notify('Valid '+current.format.toUpperCase());}
    }catch(e){error(e.message);}
  }
  function find(previous=false){
    if(!current)return;const query=$('#text-find').value;current.findQuery=query;if(!query)return;
    const at=previous?(input.selectionStart===0?-1:current.content.lastIndexOf(query,input.selectionStart-1)):current.content.indexOf(query,input.selectionEnd);
    const found=at<0?(previous?current.content.lastIndexOf(query):current.content.indexOf(query)):at;
    if(found<0){current.findStatus='No match';$('#text-find-status').textContent=current.findStatus;return;}
    input.focus();input.setSelectionRange(found,found+query.length);
    input.scrollTop=Math.max(0,(current.content.slice(0,found).split('\n').length-1)*20-(input.clientHeight||200)/2);syncScroll();
    current.findStatus='Match at character '+found;$('#text-find-status').textContent=current.findStatus;
  }
  input.addEventListener('beforeinput',()=>{if(current)current.beforeSelection={start:input.selectionStart,end:input.selectionEnd};});
  input.addEventListener('input',event=>{if(current&&!current.busy)commit(input.value,input.selectionStart,input.selectionEnd,event.inputType==='insertText');});
  input.addEventListener('scroll',syncScroll);input.addEventListener('blur',remember);
  pane.addEventListener('keydown',event=>{
    if(!current||current.busy||event.isComposing)return;
    const accel=event.ctrlKey||event.metaKey,key=event.key.toLowerCase();
    if(accel&&key==='enter'&&current.format==='sql'){event.preventDefault();event.stopPropagation();window.SqlEditor?.run();return;}
    if(event.altKey&&event.shiftKey&&key==='v'){event.preventDefault();event.stopPropagation();if(current.format==='sql')window.SqlEditor?.vibe();else vibe();return;}
    if(accel&&key==='s'){event.preventDefault();event.stopPropagation();save();return;}
    if(accel&&key==='f'){event.preventDefault();event.stopPropagation();setSearch(true);return;}
    if(event.altKey&&event.shiftKey&&key==='f'){event.preventDefault();validate(true);return;}
    if(event.target!==input)return;
    if(accel&&(key==='z'||key==='y')){event.preventDefault();undo(event.shiftKey||key==='y');return;}
    if(accel||event.altKey)return;
    const change=TextEditing.assist(current.content,input.selectionStart,input.selectionEnd,event.key,event.shiftKey);
    if(change){event.preventDefault();commit(change.text,change.start,change.end);}
  });
  $('#text-save').onclick=()=>save();$('#text-reload').onclick=reload;$('#text-format').onclick=()=>validate(true);$('#text-validate').onclick=()=>validate();
  function vibe(){if(current&&!current.busy&&['json','xml','yaml','text','markdown'].includes(current.format))window.Vibecode?.openText(current,input);}
  $('#text-vibecode').onclick=vibe;$('#text-undo-ai').onclick=()=>window.Vibecode?.undoText(current);
  function searchVisibility(){
    const expanded=!!current?.searchOpen;
    $('#text-search').hidden=!expanded;
    $('#text-find-toggle').ariaExpanded=$('#sql-find').ariaExpanded=String(expanded);
  }
  function setSearch(open){
    if(!current||current.busy)return;
    current.searchOpen=open;searchVisibility();
    if(open){$('#text-find').focus();$('#text-find').select();}else input.focus();
  }
  function toggleSearch(){setSearch(!current?.searchOpen);}
  $('#text-find-toggle').onclick=toggleSearch;$('#text-find-close').onclick=()=>setSearch(false);
  $('#text-find-next').onclick=()=>find();$('#text-find-previous').onclick=()=>find(true);
  $('#text-find').addEventListener('input',()=>{if(current){current.findQuery=$('#text-find').value;current.findStatus='';$('#text-find-status').textContent='';}});
  $('#text-search').addEventListener('keydown',event=>{if(event.key==='Escape'){event.preventDefault();event.stopPropagation();setSearch(false);}});
  $('#text-find').addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();find(event.shiftKey);}});
  return {open,activate,save,close,setBusy,applySql,applyAi:applySql,toggleSearch,reportError:error,configureSql:(doc,config)=>{doc.sqlConfig=config;doc.sqlSchema=null;doc.sqlDraft=null;doc.dirty=dirty(doc);if(current===doc)status();chrome();},refresh:doc=>{if(current===doc)render();}};
})();
