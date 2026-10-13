/* Provider-neutral edit/context request; credentials stay in the Spring Boot process. */
window.Vibecode = (() => {
  const dialog = $('#vibe-dialog');
  const history = new WeakMap();
  const MAX_OUTPUT_CHARS = 12000;
  const MAX_NOTEBOOK_OUTPUT_CHARS = 64000;
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

  function outputText(output) {
    const data=output?.data || {};
    const type=output?.output_type;
    const plain=value=>Array.isArray(value)?value.join(''):String(value ?? '');
    if(type==='stream')return {type:output.name==='stderr'?'stderr':'stdout',text:plain(output.text)};
    if(type==='error')return {type:'error',text:plain(Array.isArray(output.traceback) && output.traceback.length?output.traceback.join('\n'):`${output.ename || 'Error'}: ${output.evalue || ''}`)};
    if(type==='execute_result' || type==='display_data') {
      const grid=data['application/vnd.gridjs+json'];
      if(grid && Array.isArray(grid.columns) && Array.isArray(grid.data)) {
        const columns=grid.columns.map(column=>String(typeof column==='object'?(column.name ?? column.id ?? ''):column));
        const lines=[columns.join('\t')];
        let included=0,used=lines[0].length;
        for(const row of grid.data) {
          if(included>=100 || used>=MAX_OUTPUT_CHARS)break;
          const values=Array.isArray(row)?row:[];
          const line=values.map(value=>String(value ?? '').replaceAll('\t',' ').replaceAll('\n',' ').slice(0,1000)).join('\t');
          lines.push(line);used+=line.length+1;
          included++;
        }
        return {type:'grid',text:`${grid.title || 'Grid table'}\n${lines.join('\n')}`,rowsTotal:grid.data.length,rowsIncluded:included};
      }
      // Chart options and base64 images are large and do not help with textual follow-up edits.
      if(data['image/png'] || data['application/vnd.apache.echarts+json'])return null;
      if(data['text/plain'] != null)return {type:'text',text:plain(data['text/plain'])};
    }
    return null;
  }

  function precedingCells(document,index) {
    const cells=document.cells.slice(0,index).map((c,i)=>({index:i+1,type:c.cell_type,source:source(c),executionCount:c.execution_count ?? null}));
    let remaining=MAX_NOTEBOOK_OUTPUT_CHARS,omitted=0,truncated=0,included=0;
    // Allocate the bounded output budget newest-first, but keep notebook order in the request.
    for(let i=cells.length-1;i>=0;i--) {
      const originals=document.cells[i].cell_type==='code'?(document.cells[i].outputs || []):[];
      const selected=[];
      for(let j=originals.length-1;j>=0;j--) {
        const item=outputText(originals[j]);
        if(!item)continue;
        if(remaining<=0){omitted++;continue;}
        const originalLength=item.text.length;
        const limit=Math.min(MAX_OUTPUT_CHARS,remaining);
        if(originalLength>limit){item.text=item.text.slice(0,limit);item.truncated=true;item.originalChars=originalLength;}
        if(item.rowsTotal!==undefined && item.rowsIncluded<item.rowsTotal)item.truncated=true;
        if(item.truncated)truncated++;
        remaining-=item.text.length;
        included++;
        selected.unshift(item);
      }
      if(selected.length)cells[i].outputs=selected;
    }
    return {cells,metadata:{cellCount:cells.length,outputCount:included,outputsOmitted:omitted,outputsTruncated:truncated,outputBudgetChars:MAX_NOTEBOOK_OUTPUT_CHARS}};
  }

  function contextFor(current) {
    const context=[];
    const settings=window.NotebookSettings?.aiContext?.(current.notebook.document);
    if(settings)context.push(settings);
    if($('#vibe-notebook-context').checked) {
      const index=current.notebook.document.cells.indexOf(current.cell);
      const prior=precedingCells(current.notebook.document,index);
      if(prior.cells.length)context.push({kind:'notebook',title:current.notebook.name+' · preceding cells',content:JSON.stringify(prior.cells),metadata:prior.metadata});
    }
    const notes=$('#vibe-context-notes').value.trim();
    if(notes)context.push({kind:'notes',title:'Additional user context',content:notes,metadata:{}});
    const failures=(current.cell.outputs||[]).filter(o=>o.output_type==='error');
    if(failures.length) {
      let remaining=MAX_OUTPUT_CHARS,omitted=0,truncated=0;
      const diagnostics=[];
      for(let i=failures.length-1;i>=0;i--) {
        if(remaining<=0){omitted++;continue;}
        const item=outputText(failures[i]);
        const originalLength=item.text.length;
        if(originalLength>remaining){item.text=item.text.slice(0,remaining);item.truncated=true;item.originalChars=originalLength;truncated++;}
        remaining-=item.text.length;
        diagnostics.unshift(item);
      }
      context.push({kind:'diagnostics',title:'Current cell errors',content:JSON.stringify(diagnostics),metadata:{errorCount:diagnostics.length,errorsOmitted:omitted,errorsTruncated:truncated}});
    }
    return context;
  }

  async function generate(event) {
    event.preventDefault();if(!edit || controller)return;
    const current=edit;
    const prompt=$('#vibe-prompt').value.trim();if(!prompt)return;
    let context;
    try{context=contextFor(current);}catch(e){error(e.message);return;}
    const request={prompt,target:{source:current.original,start:current.start,end:current.end},context};
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
