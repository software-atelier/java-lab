/* Provider-neutral edit/context request; credentials stay in the Spring Boot process. */
window.Vibecode = (() => {
  const dialog = $('#vibe-dialog');
  const history = new WeakMap();
  let edit = null, controller = null, generation = 0;

  function close() {
    generation++;controller?.abort();controller=null;edit=null;dialog.close();
  }
  function error(message) {$('#vibe-error').textContent=message;$('#vibe-error').hidden=false;}
  function loading(value) {
    $('#vibe-progress').hidden=!value;
    $('#vibe-generate').disabled=value;
    $('#vibe-prompt').disabled=value;
    $('#vibe-context-notes').disabled=value;
    $('#vibe-notebook-context').disabled=value;
    $('#vibe-generate').textContent=value?'Generating…':'Generate & apply';
  }
  async function open() {
    const n=active(), c=n?.document.cells[n.selected];
    if(!c || c.cell_type!=='code' || n.running)return;
    const editor=$(`#cells [data-cell="${n.selected}"] textarea`);
    const selected=editor && editor.selectionEnd>editor.selectionStart;
    edit={notebook:n,cell:c,original:source(c),start:selected?editor.selectionStart:null,end:selected?editor.selectionEnd:null};
    const version=++generation;
    const text=selected?edit.original.slice(edit.start,edit.end):edit.original;
    $('#vibe-target-label').textContent=selected?'REPLACE SELECTED CODE':'REPLACE CELL';
    $('#vibe-target-code').innerHTML=text?LabPresentation.highlightJava(text):'<span class="syntax-comment">// A fresh canvas</span>';
    $('#vibe-context-count').textContent=`(${n.selected})`;
    $('#vibe-prompt').value='';$('#vibe-context-notes').value='';$('#vibe-error').hidden=true;
    $('#vibe-model').textContent='AI · loading configuration…';loading(false);
    window.JavaEditor?.dismiss();dialog.showModal();$('#vibe-prompt').focus();
    try {
      const configuration=await api('/ai/config');
      if(version!==generation)return;
      const provider=configuration.provider||'OpenAI-compatible API';
      $('#vibe-model').textContent=configuration.model?`${provider} · ${configuration.model}`:`${provider} · not configured`;
      $('#vibe-generate').disabled=!configuration.configured;
      if(!configuration.configured)error('Set AI_MODEL and AI_API_KEY if your API requires authentication, then restart Java Lab. OpenRouter requires an API key; AI_API_URL selects another compatible API.');
    }catch(e){if(version===generation){error(e.message);$('#vibe-generate').disabled=true;}}
  }

  function contextFor(current) {
    const context=[];
    const settings=window.NotebookSettings?.aiContext?.(current.notebook.document);
    if(settings)context.push(settings);
    if($('#vibe-notebook-context').checked) {
      const index=current.notebook.document.cells.indexOf(current.cell);
      const cells=current.notebook.document.cells.slice(0,index).map((c,i)=>({index:i+1,type:c.cell_type,source:source(c),executionCount:c.execution_count ?? null}));
      if(cells.length)context.push({kind:'notebook',title:current.notebook.name+' · preceding cells',content:JSON.stringify(cells),metadata:{cellCount:cells.length}});
    }
    const notes=$('#vibe-context-notes').value.trim();
    if(notes)context.push({kind:'notes',title:'Additional user context',content:notes,metadata:{}});
    const failures=(current.cell.outputs||[]).filter(o=>o.output_type==='error');
    if(failures.length)context.push({kind:'diagnostics',title:'Current cell errors',content:JSON.stringify(failures),metadata:{}});
    return context;
  }

  async function generate(event) {
    event.preventDefault();if(!edit || controller)return;
    const current=edit;
    const request={prompt:$('#vibe-prompt').value.trim(),target:{source:current.original,start:current.start,end:current.end},context:contextFor(current)};
    if(!request.prompt)return;
    controller=new AbortController();const version=generation;loading(true);$('#vibe-error').hidden=true;
    try {
      const result=await api('/ai/generate','POST',request,{signal:controller.signal});
      if(version!==generation)return;
      const {notebook:n,cell:c,original,start,end}=current;
      if(!state.notebooks.includes(n)||!n.document.cells.includes(c)||c.cell_type!=='code'||source(c)!==original||n.running)
        throw new Error('The target cell changed while generating. Your current code was kept; request the edit again.');
      if(typeof result.code!=='string'||!result.code.trim())throw new Error('The model returned no code.');
      const replacement=start===null?result.code:original.slice(0,start)+result.code+original.slice(end);
      if(replacement.length>100000)throw new Error('The resulting cell exceeds 100,000 characters. Request a smaller edit.');
      history.set(c,{source:c.source,outputs:c.outputs,executionCount:c.execution_count,applied:replacement});
      c.source=[replacement];c.outputs=[];c.execution_count=null;
      n.selected=n.document.cells.indexOf(c);markDirty(n);close();
      if(active()===n){renderCells();selectCell(n.selected,true);}
      notify('AI edit applied · use “Undo AI edit” on the cell to restore');
    }catch(e){if(version===generation && e.name!=='AbortError')error(e.message);}
    finally{if(version===generation){controller=null;loading(false);}}
  }

  function canUndo(c) {return history.has(c);}
  function undo() {
    const n=active(),c=n?.document.cells[n.selected],before=c&&history.get(c);
    if(!before||n.running)return;
    if(source(c)!==before.applied){notify('The code changed after the AI edit; automatic undo would overwrite your edits.',true);return;}
    c.source=before.source;c.outputs=before.outputs;c.execution_count=before.executionCount;
    history.delete(c);markDirty(n);renderCells();selectCell(n.selected,true);notify('AI edit undone');
  }
  $('#vibe-form').addEventListener('submit',generate);
  $('#vibe-close').onclick=$('#vibe-cancel').onclick=close;
  dialog.addEventListener('cancel',event=>{event.preventDefault();close();});
  return {open,canUndo,undo};
})();
