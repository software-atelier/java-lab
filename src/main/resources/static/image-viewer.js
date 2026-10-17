window.ImageViewer=(()=>{
  const opening=new Map();let current=null,rendered='',generation=0;
  const endpoint=(path,kind='image-info')=>'/files/'+kind+'?path='+encodeURIComponent(path);
  function examples(path){
    const encoded=path.split('/').map(part=>encodeURIComponent(part).replace(/[!'()*]/g,c=>'%'+c.charCodeAt(0).toString(16))).join('/');
    return {java:`Images.show(${JSON.stringify(path)});`,markdown:`![Image](<${encoded}>)`};
  }
  function render(){
    const doc=current;if(!doc)return;
    $('#image-title').textContent=doc.path;
    $('#image-info').textContent=`${doc.mime} · ${doc.width} × ${doc.height} · ${doc.size} bytes`;
    $('#image-error').textContent=doc.error||'';$('#image-error').hidden=!doc.error;
    const snippets=examples(doc.path);$('#image-java-example').textContent=snippets.java;$('#image-md-example').textContent=snippets.markdown;
    const img=document.createElement('img'),path=doc.path,version=++generation;img.alt=doc.name;
    img.onerror=()=>{if(current===doc&&doc.path===path&&version===generation){doc.error='Image could not be displayed. Reload it or download the original file.';$('#image-error').textContent=doc.error;$('#image-error').hidden=false;}};
    img.onload=()=>{if(current===doc&&doc.path===path&&version===generation){doc.error=null;$('#image-error').hidden=true;}};
    img.src='api'+endpoint(doc.path,'image')+'&v='+encodeURIComponent(doc.revision);
    $('#image-preview').replaceChildren(img);rendered=doc.path+'|'+doc.revision;
  }
  function activate(doc){current=doc;if(!doc){rendered='';return;}if(rendered!==doc.path+'|'+doc.revision)render();else $('#image-title').textContent=doc.path;}
  async function open(name,folder='',select=true){
    const path=notebookPath(folder,name);let doc=openDocuments().find(item=>item.type==='image'&&item.path===path);
    if(!doc){
      if(!opening.has(path))opening.set(path,(async()=>{const info=await api(endpoint(path));const created={...info,type:'image',name,folder,uid:crypto.randomUUID(),dirty:false,error:null};openDocuments().push(created);return created;})());
      try{doc=await opening.get(path);}finally{opening.delete(path);}
    }
    if(select){state.active=doc;state.launcher=false;renderWorkspace();}else renderTabs();return doc;
  }
  async function close(doc=current){if(!doc)return;window.NotebookControls?.closed(doc);state.tabs=openDocuments().filter(item=>item!==doc);if(state.active===doc){state.active=openDocuments().at(-1)||null;state.launcher=!state.active;}renderWorkspace();}
  async function reload(){const doc=current;if(!doc||doc.busy)return;doc.busy=true;
    try{Object.assign(doc,await api(endpoint(doc.path)),{error:null});if(current===doc)render();}
    catch(e){doc.error=e.message;if(current===doc){$('#image-error').textContent=doc.error;$('#image-error').hidden=false;}}
    finally{doc.busy=false;}
  }
  async function copy(kind){
    if(!current)return;const text=examples(current.path)[kind];
    try{
      let copied=false;
      if(navigator.clipboard?.writeText){try{await navigator.clipboard.writeText(text);copied=true;}catch(_){/* Fall back to the native selection clipboard. */}}
      if(!copied){const focused=document.activeElement,input=document.createElement('textarea');input.value=text;input.readOnly=true;input.style.position='fixed';input.style.opacity='0';document.body.append(input);input.select();copied=document.execCommand('copy');input.remove();focused?.focus?.();if(!copied)throw Error('Clipboard unavailable');}
      notify((kind==='java'?'Java':'Markdown')+' example copied');
    }catch(e){notify('Could not copy. Select and copy the example text directly.',true);}
  }
  $('#image-reload').onclick=reload;$('#image-download').onclick=()=>{if(current)downloadWorkspaceFile(current.name,current.folder);};
  $('#image-copy-java').onclick=()=>copy('java');$('#image-copy-md').onclick=()=>copy('markdown');
  return {open,activate,close,examples,refresh:doc=>{if(current===doc)render();}};
})();
