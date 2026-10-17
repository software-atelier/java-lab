/* Provider-neutral edit/context request; credentials stay in the Spring Boot process. */
window.Vibecode = (() => {
  const dialog = $('#vibe-dialog');
  const history = new WeakMap();
  const diagnosticsByCell = new WeakMap();
  const MAX_OUTPUT_CHARS = 12000;
  const MAX_NOTEBOOK_OUTPUT_CHARS = 64000;
  const FIX_ERROR_PROMPT = 'Fix the execution error in this Java cell. Use the current cell diagnostics and preceding notebook context to identify the cause. Return the complete corrected cell. Preserve the intended behavior and change only what is needed.';
  let edit = null, controller = null, generation = 0;
  let progressSession = null;
  let diagnosticsController = null;

  function formatDiagnostics(items) {
    const count=value=>Number.isSafeInteger(value) && value>=0?String(value):'not reported';
    return items.map(item=>`Request ${item.request}\nConfigured model: ${item.configuredModel || 'not reported'}\nReported model: ${item.reportedModel || 'not reported'}\nOutput limit: ${count(item.maxTokens)} tokens\nFinish reason: ${item.finishReason || 'not reported'}\nInput tokens: ${count(item.inputTokens)}\nOutput tokens: ${count(item.outputTokens)}\nReasoning tokens: ${count(item.reasoningTokens)}`).join('\n\n');
  }

  function renderDiagnostics(items,expanded=false) {
    const diagnostics=Array.isArray(items)?items:[];
    $('#vibe-diagnostics').hidden=!diagnostics.length;
    $('#vibe-diagnostics').open=expanded;
    $('#vibe-diagnostics-text').textContent=formatDiagnostics(diagnostics);
  }

  function rememberDiagnostics(items,cell) {
    if(!Array.isArray(items) || !items.length)return;
    diagnosticsByCell.set(cell,items);
    $('#vibe-progress-diagnostics').textContent=formatDiagnostics(items);
    $('#vibe-progress-diagnostics').hidden=false;
  }

  function fetchFinalDiagnostics(requestId,version,cell) {
    if(!requestId)return;
    diagnosticsController?.abort();
    const pending=new AbortController();diagnosticsController=pending;
    api(`/ai/progress/${encodeURIComponent(requestId)}`,'GET',undefined,{signal:pending.signal,cache:'no-store'}).then(snapshot=>{
      if(version!==generation || diagnosticsController!==pending)return;
      rememberDiagnostics(snapshot?.diagnostics,cell);
      renderDiagnostics(diagnosticsByCell.get(cell),true);
    }).catch(()=>{/* The original generation error remains visible even if diagnostics cannot be retrieved. */});
  }

  function generationRequestId() {
    if(window.crypto?.randomUUID)return window.crypto.randomUUID();
    // getRandomValues is also available on HTTP origins where randomUUID may be unavailable.
    if(!window.crypto?.getRandomValues)return null;
    const bytes=window.crypto.getRandomValues(new Uint8Array(16));
    bytes[6]=(bytes[6]&15)|64;bytes[8]=(bytes[8]&63)|128;
    const hex=Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
    return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
  }

  function stopProgress() {
    if(!progressSession)return;
    clearTimeout(progressSession.timer);progressSession.controller.abort();progressSession=null;
  }

  function startProgress(requestId,version,cell) {
    stopProgress();
    diagnosticsController?.abort();diagnosticsController=null;
    diagnosticsByCell.delete(cell);
    renderDiagnostics([]);
    $('#vibe-progress-popup').hidden=false;
    $('#vibe-progress-activity').textContent='Submitting generation request…';
    $('#vibe-progress-log').textContent='Waiting for live activity…';
    $('#vibe-progress-diagnostics').textContent='';$('#vibe-progress-diagnostics').hidden=true;
    if(!requestId)return;
    const session={controller:new AbortController(),timer:null};
    progressSession=session;
    const current=()=>progressSession===session && version===generation;
    async function poll() {
      if(!current())return;
      let done=false;
      try {
        const snapshot=await api(`/ai/progress/${encodeURIComponent(requestId)}`,'GET',undefined,{signal:session.controller.signal,cache:'no-store'});
        if(!current())return;
        const events=Array.isArray(snapshot?.events)?snapshot.events:[];
        const seconds=ms=>`${Math.floor(Math.max(0,Number(ms)||0)/1000)}s`;
        if(events.length) {
          $('#vibe-progress-activity').textContent=events[events.length-1].activity;
          $('#vibe-progress-log').textContent=`Elapsed: ${seconds(snapshot.elapsedMillis)}\n\n`+events.map(event=>`${seconds(event.elapsedMillis)} · ${String(event.activity)}`).join('\n');
        }
        rememberDiagnostics(snapshot?.diagnostics,cell);
        done=!!snapshot?.done;
      }catch(e){/* A late registration or failed progress poll must not interrupt code generation. */}
      if(current() && !done)session.timer=setTimeout(poll,750);
    }
    // Let the POST register the request before polling. Only one poll is in flight at a time.
    session.timer=setTimeout(poll,300);
  }

  function close() {
    stopProgress();diagnosticsController?.abort();diagnosticsController=null;generation++;controller?.abort();controller=null;edit=null;dialog.close();
  }
  function error(message) {$('#vibe-error').textContent=message;$('#vibe-error').hidden=false;}
  function loading(value) {
    if(!value)stopProgress();
    $('#vibe-progress').hidden=!value;
    $('#vibe-generate').disabled=value;
    $('#vibe-prompt').disabled=value;
    $('#vibe-context-notes').disabled=value;
    $('#vibe-notebook-context').disabled=value;
    $('#vibe-generate').textContent=value?'Generating…':'Generate & apply';
  }
  async function open(fixError = false) {
    const n=active(), c=n?.document.cells[n.selected];
    if(!c || c.cell_type!=='code' || n.running || dialog.open)return;
    if(fixError && !(c.outputs||[]).some(o=>o.output_type==='error'))return;
    const editor=$(`#cells [data-cell="${n.selected}"] textarea`);
    const selected=!fixError && editor && editor.selectionEnd>editor.selectionStart;
    edit={notebook:n,cell:c,original:source(c),start:selected?editor.selectionStart:null,end:selected?editor.selectionEnd:null,errorOutputs:fixError?c.outputs:null};
    const version=++generation;
    const text=selected?edit.original.slice(edit.start,edit.end):edit.original;
    $('#vibe-target-label').textContent=fixError?'FIX FAILED CELL':selected?'REPLACE SELECTED CODE':'REPLACE CELL';
    $('#vibe-target-code').innerHTML=text?LabPresentation.highlightJava(text):'<span class="syntax-comment">// A fresh canvas</span>';
    $('#vibe-context-count').textContent=`(${n.selected})`;
    $('#vibe-prompt').value=fixError?FIX_ERROR_PROMPT:'';$('#vibe-context-notes').value='';$('#vibe-error').hidden=true;
    $('#vibe-model').textContent='AI · loading configuration…';loading(false);
    renderDiagnostics(diagnosticsByCell.get(c));
    $('#vibe-generate').disabled=true;
    window.JavaEditor?.dismiss();dialog.showModal();$('#vibe-prompt').focus();
    try {
      const configuration=await api('/ai/config');
      if(version!==generation)return;
      const provider=configuration.provider||'OpenAI-compatible API';
      $('#vibe-model').textContent=configuration.model?`${provider} · ${configuration.model}`:`${provider} · not configured`;
      $('#vibe-generate').disabled=!configuration.configured;
      if(!configuration.configured)error('Set AI_MODEL and AI_API_KEY if your API requires authentication, then restart Java Lab. OpenRouter requires an API key; AI_API_URL selects another compatible API.');
      else if(fixError)await generate();
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
      if(Object.keys(data).some(type=>type.startsWith('image/')) || data['application/vnd.apache.echarts+json'])return null;
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
    event?.preventDefault();if(!edit || controller)return;
    const current=edit;
    const prompt=$('#vibe-prompt').value.trim();if(!prompt)return;
    if(!state.notebooks.includes(current.notebook)||!current.notebook.document.cells.includes(current.cell)||current.cell.cell_type!=='code'||source(current.cell)!==current.original||current.notebook.running||(current.errorOutputs && current.cell.outputs!==current.errorOutputs)){error('The target cell changed before generating. Your current code was kept; request the edit again.');return;}
    let context;
    try{context=contextFor(current);}catch(e){error(e.message);return;}
    const kernel=current.notebook.kernel;
    const requestId=generationRequestId();
    const request={prompt,target:{source:current.original,start:current.start,end:current.end},context,...(kernel?{kernelId:kernel}:{}),...(requestId?{requestId}:{})};
    controller=new AbortController();const version=generation;loading(true);$('#vibe-error').hidden=true;
    try {
      const pending=api('/ai/generate','POST',request,{signal:controller.signal});
      startProgress(requestId,version,current.cell);
      const result=await pending;
      if(version!==generation)return;
      rememberDiagnostics(result.diagnostics,current.cell);
      const {notebook:n,cell:c,original,start,end}=current;
      if(!state.notebooks.includes(n)||!n.document.cells.includes(c)||c.cell_type!=='code'||source(c)!==original||n.running||n.kernel!==kernel||(current.errorOutputs && c.outputs!==current.errorOutputs))
        throw new Error('The target cell changed while generating. Your current code was kept; request the edit again.');
      if(typeof result.code!=='string'||!result.code.trim())throw new Error('The model returned no code.');
      const replacement=start===null?result.code:original.slice(0,start)+result.code+original.slice(end);
      if(replacement.length>100000)throw new Error('The resulting cell exceeds 100,000 characters. Request a smaller edit.');
      history.set(c,{source:c.source,outputs:c.outputs,executionCount:c.execution_count,applied:replacement});
      c.source=[replacement];c.outputs=[];c.execution_count=null;
      n.selected=n.document.cells.indexOf(c);markDirty(n);close();
      if(active()===n){renderCells();selectCell(n.selected,true);}
      notify('AI edit applied · use “Undo AI edit” on the cell to restore');
    }catch(e){if(version===generation && e.name!=='AbortError'){
      error(e.message);renderDiagnostics(diagnosticsByCell.get(current.cell),true);
      fetchFinalDiagnostics(requestId,version,current.cell);
    }}
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
  $('#vibe-progress').addEventListener('mouseenter',()=>{$('#vibe-progress-popup').hidden=false;});
  $('#vibe-progress').addEventListener('focusin',()=>{$('#vibe-progress-popup').hidden=false;});
  $('#vibe-progress').addEventListener('keydown',event=>{
    if(event.key==='Escape' && !$('#vibe-progress-popup').hidden) {
      event.preventDefault();event.stopPropagation();$('#vibe-progress-popup').hidden=true;
    }
  });
  $('#vibe-close').onclick=$('#vibe-cancel').onclick=close;
  dialog.addEventListener('cancel',event=>{event.preventDefault();close();});
  return {open,fixError:()=>open(true),canUndo,undo};
})();
