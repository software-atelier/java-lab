/* Java Lab — entirely local, dependency-free notebook client. */
const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const icon = name => `<svg aria-hidden="true"><use href="#i-${name}"/></svg>`;
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const source = cell => Array.isArray(cell.source) ? cell.source.join('') : (cell.source || '');
const state = { files: [], folders: [], folder: '', notebooks: [], tabs: [], active: null, launcher: true, panel: 'files', opening: new Map() };
const folderQuery = folder => '?folder=' + encodeURIComponent(folder || '');
const notebookPath = (folder, name) => folder ? folder + '/' + name : name;
let toastTimer;
let loadingCount=0;
async function withLoading(label,operation){
  loadingCount++;$('#loading-label').textContent=label;$('#loading-overlay').hidden=false;
  try{return await operation();}
  finally{loadingCount=Math.max(0,loadingCount-1);if(!loadingCount)$('#loading-overlay').hidden=true;}
}

async function api(path, method = 'GET', body, options = {}) {
  const response = await fetch('api' + path, { ...options, method, headers: body === undefined ? {} : {'Content-Type':'application/json'}, body: body === undefined ? undefined : JSON.stringify(body) });
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try { const error = await response.json(); message = error.message || error.detail || message; } catch (_) { /* non-JSON error */ }
    throw new Error(message);
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

function notebookSnapshot(document) {
  const snapshot={...document, cells:document.cells.map(({editing, busy, ...persisted}) => persisted)};
  // A secret definition may have come from an older document. Never write its default back.
  if(snapshot.metadata?.java_lab?.variables) snapshot.metadata={...snapshot.metadata,java_lab:{...snapshot.metadata.java_lab,variables:snapshot.metadata.java_lab.variables.map(variable=>{
    const copy={...variable};if(copy.secret)delete copy.defaultValue;return copy;
  })}};
  return snapshot;
}
function formValues(n) {
  return n.document.metadata?.java_lab?.form_values || {};
}
function markFollowingStale(n, index) {
  n.staleCells ||= new Set();
  for(let i=index+1;i<n.document.cells.length;i++) if(n.document.cells[i].outputs?.length)n.staleCells.add(n.document.cells[i].id);
  if(active()===n) $$('#cells .cell').forEach((element,i)=>element.classList.toggle('stale-output',n.staleCells.has(n.document.cells[i]?.id)));
  updateStatus();
}
function clearStaleThrough(n, index) {
  n.staleCells?.delete(n.document.cells[index]?.id);
  if(!n.staleCells?.size)delete n.staleCells;
}
function validVisibleForms() {
  const invalid=$$('#cells .lab-form [data-form-field]').find(control=>!control.checkValidity());
  if(!invalid)return true;
  invalid.reportValidity();invalid.focus();notify('Correct the highlighted form field before running.',true);
  return false;
}
function hydrateFormValues(n) {
  const values={...formValues(n)};
  let changed=false;
  for(const c of n.document.cells) for(const output of c.outputs || []) {
    const form=output.data?.[LabPresentation.formMime];
    if(!Array.isArray(form?.fields))continue;
    for(const field of form.fields) if(field && typeof field.id==='string' && !Object.prototype.hasOwnProperty.call(values,field.id)) {
      values[field.id]=field.value ?? null;changed=true;
    }
  }
  if(changed) {
    n.document.metadata ||= {};n.document.metadata.java_lab ||= {};
    n.document.metadata.java_lab.form_values=values;
    markDirty(n);
  }
}

function notify(message, error = false) {
  clearTimeout(toastTimer);
  $('#toast').textContent = message; $('#toast').classList.toggle('error', error); $('#toast').hidden = false;
  toastTimer = setTimeout(() => $('#toast').hidden = true, error ? 6500 : 3200);
}
function openDocuments() { return state.tabs ?? state.notebooks; }
function activeDocument() { return state.launcher ? null : state.active; }
function active() { const doc=activeDocument();return ['csv','text','image'].includes(doc?.type)?null:doc; }
async function openFile(name,folder=state.folder,type,select=true) {
  return withLoading('Opening '+name+'…',()=>openFileContent(name,folder,type,select));
}
async function openFileContent(name,folder=state.folder,type,select=true) {
  if(type==='image'||/\.(png|jpe?g|gif|bmp|webp|svg)$/i.test(name))return window.ImageViewer?.open(name,folder,select);
  if(type==='csv'||/\.csv$/i.test(name))return window.CsvEditor?.open(name,folder,select);
  if(type==='text'||/\.(json|xml|txt|md|yaml|yml|java|sql|properties|log|css|js|html|htm)$/i.test(name))return window.TextEditor?.open(name,folder,select);
  if(type==='notebook'||/\.(jnb|ipynb)$/.test(name))return openNotebook(name,folder,select);
  return downloadWorkspaceFile(name,folder);
}
async function closeDocument(doc=activeDocument()) {
  if(doc?.fileOperation)throw new Error('Wait for the file operation to finish before closing.');
  if(doc?.type==='csv')return window.CsvEditor?.close(doc);
  if(doc?.type==='text')return window.TextEditor?.close(doc);
  if(doc?.type==='image')return window.ImageViewer?.close(doc);
  return closeNotebook(doc);
}
async function saveDocument(doc=activeDocument(),announce=true) {
  if(doc?.fileOperation)throw new Error('Wait for the file operation to finish before saving.');
  if(doc?.type==='image')return true;
  if(doc?.type==='text')return window.TextEditor?.save(doc);
  return doc?.type==='csv'?window.CsvEditor?.save(doc):saveNotebook(doc,announce);
}
function downloadWorkspaceFile(name,folder=state.folder) {
  const link=document.createElement('a');link.href='api/files/download?path='+encodeURIComponent(notebookPath(folder,name));link.download=name;link.click();
}
function dateLabel(time) {
  const days = Math.floor((Date.now() - time) / 86400000);
  return days < 1 ? 'Today' : days === 1 ? 'Yesterday' : new Date(time).toLocaleDateString(undefined, {month:'short', day:'numeric'});
}
function cell(type = 'code', text = '') {
  const value = { id: crypto.randomUUID(), cell_type: type, metadata: {}, source: [text] };
  if (type === 'code') Object.assign(value, {execution_count:null, outputs:[]});
  return value;
}

async function refreshFiles() {
  const result = await api('/notebooks' + folderQuery(state.folder)); state.files = result.files; state.folders = result.folders || []; state.folder = result.folder || '';
  $('#folder-path').textContent = state.folder || 'Data directory';
  $('#directory').textContent = result.directory; $('#directory').title = result.directory;
  $('#connection-state').textContent = 'Connected'; renderFiles();
}
function renderFiles() {
  const filter = $('#file-search').value.toLowerCase();
  const files = state.files.filter(f => !f.name.startsWith('.') && f.name.toLowerCase().includes(filter));
  const fileIcon=file=>{
    const extension=file.name.toLowerCase().split('.').at(-1);
    if(file.type==='notebook'||['jnb','ipynb'].includes(extension))return 'book';
    if(file.type==='image'||['png','jpg','jpeg','gif','bmp','webp','svg','tif','tiff','ico','heic','avif'].includes(extension))return 'image';
    if(['zip','gz','tgz','tar','jar','7z','rar'].includes(extension))return 'archive';
    const icons={csv:'table',json:'braces',xml:'xml',txt:'text',log:'text',md:'book',yaml:'list',yml:'list',sql:'database',db:'database',sqlite:'database',properties:'config',ini:'config',conf:'config',java:'code',js:'code',ts:'code',css:'code',html:'code',htm:'code',sh:'terminal',bat:'terminal'};
    return Object.prototype.hasOwnProperty.call(icons,extension)?icons[extension]:'file';
  };
  $('#file-list').innerHTML = (state.folder ? `<button class="file-row folder-row" data-parent-folder="true" title="Move files to parent folder or open it">${icon('folder')}<span class="file-name">..</span></button>` : '') + state.folders.filter(f=>f.name.toLowerCase().includes(filter)).map(f => `<button class="file-row folder-row" data-folder="${escapeHtml(f.path)}" title="Open ${escapeHtml(f.name)} or drop a file here">${icon('folder')}<span class="file-name">${escapeHtml(f.name)}</span></button>`).join('') + (files.length ? files.map(f => `<button class="file-row ${activeDocument()?.path === notebookPath(state.folder, f.name) ? 'active' : ''}" data-file="${escapeHtml(f.name)}" data-file-type="${escapeHtml(f.type)}" data-file-folder="${escapeHtml(state.folder)}" draggable="true" title="${escapeHtml(f.name)} · open or drag into a folder">${icon(fileIcon(f))}<span class="file-name">${escapeHtml(f.name)}</span><time>${dateLabel(f.modified)}</time></button>`).join('') : (!state.folder && !state.folders.length ? `<div class="empty-files">${filter ? 'No matching files.' : 'A fresh workspace.<br>Create your first notebook to begin.'}</div>` : ''));
  const recent=state.files.filter(f=>!f.name.startsWith('.') && f.type==='notebook');
  $('#recent-files').innerHTML = recent.length ? [...recent].sort((a,b) => b.modified-a.modified).slice(0,4).map(f => `<button class="recent-row" data-file="${escapeHtml(f.name)}" data-file-type="notebook" data-file-folder="${escapeHtml(state.folder)}">${icon(fileIcon(f))}<span>${escapeHtml(f.name)}</span><time>${dateLabel(f.modified)}</time>${icon('chevron')}</button>`).join('') : '<div class="recent-empty">Your notebooks will appear here. Let’s make the first one.</div>';
}
function renderTabs() {
  $('#tabs').innerHTML = (state.launcher ? '<div class="tab active" data-launcher="true">'+icon('grid')+'<span class="tab-name">Launcher</span></div>' : '') + openDocuments().map(n => `<div class="tab ${activeDocument() === n ? 'active' : ''}" data-tab="${n.uid}" role="button" tabindex="0" title="${escapeHtml(n.path)}">${icon(n.type==='csv'?'grid':'file')}<span class="tab-name">${escapeHtml(n.name)}</span>${n.dirty ? '<span class="dirty-dot" title="Unsaved changes"></span>' : ''}<button class="icon-button" data-close="${n.uid}" title="Close ${n.type==='csv'?'CSV':n.type==='text'?'file':'notebook'}" aria-label="Close ${escapeHtml(n.name)}">${icon('close')}</button></div>`).join('');
  renderKernels();
}
function renderKernels() {
  $('#kernel-list').innerHTML = state.notebooks.length ? state.notebooks.map(n => {
    const disabled=n.kernelStarting||n.fileOperation||n.configPending;
    const control=(action,label,symbol,blocked=false)=>`<button class="icon-button" data-kernel-action="${action}" data-kernel-notebook="${n.uid}" title="${label}" aria-label="${label} · ${escapeHtml(n.name)}" ${blocked?'disabled':''}>${icon(symbol)}</button>`;
    return `<div class="kernel-row ${active()===n?'active':''}"><button class="kernel-open" data-tab="${n.uid}" title="Open ${escapeHtml(n.path)}">${icon('terminal')}<span class="kernel-summary"><span class="kernel-name">${escapeHtml(n.name)}</span><small>${n.kernelStopping ? 'Stopping' : n.kernelStarting ? 'Starting' : !n.kernel ? 'Stopped' : n.running ? 'Busy' : 'Idle'} · Java</small><small data-kernel-timing="${n.uid}" ${n.executingCell?'':'hidden'}>${kernelTiming(n)}</small><small class="kernel-resources" data-kernel-resources="${n.uid}" ${n.kernel&&!n.kernelStarting?'':'hidden'}>Reading resources…</small></span></button><div class="kernel-actions">${n.running?control('interrupt','Interrupt kernel','stop',disabled||n.cancelRun):control(n.kernel?'restart':'start',n.kernel?'Restart kernel':'Start kernel',n.kernel?'refresh':'play',disabled)}${control('shutdown','Shut down kernel (keep tab open)','close',disabled||n.running||!n.kernel)}</div></div>`;
  }).join('') : '<div class="empty-files">No open notebooks.</div>';
  window.KernelResources?.refresh();
}
function kernelTiming(n) {
  if(!n.executingCell)return '';
  const seconds=Math.floor((Date.now()-n.cellStartedAt)/1000);
  const elapsed=seconds<60?`${seconds}s`:`${Math.floor(seconds/60)}m ${seconds%60}s`;
  return `Cell ${n.document.cells.indexOf(n.executingCell)+1} · ${elapsed}`;
}

async function openNotebook(name, folder = state.folder, select = true) {
  const path = notebookPath(folder, name);
  let notebook = state.notebooks.find(n => n.path === path);
  if (!notebook) {
    if (!state.opening.has(path)) {
      state.opening.set(path, (async () => {
        const document = await api('/notebooks/' + encodeURIComponent(name) + folderQuery(folder));
         // Preserve notebook metadata and standard output objects on round trip.
         const opened = {uid:crypto.randomUUID(), name, folder, path, document, kernel:null, kernelStarting:true, configPending:false, selected:0, dirty:false, revision:0, running:false, timer:null};
        state.notebooks.push(opened);
        if(state.tabs)state.tabs.push(opened);
        hydrateFormValues(opened);
         // Display saved cells immediately; starting JShell (and resolving libraries) can take seconds.
         opened.kernelPromise = api('/kernels', 'POST', {notebook:path}).then(kernel=>{opened.kernel=kernel.id;})
           .catch(error=>{opened.configPending=true;notify('Kernel could not start: '+error.message+'. Open Settings to fix libraries or values.',true);})
           .finally(()=>{opened.kernelStarting=false;renderKernels();updateStatus();});
        return opened;
      })());
    }
    try { notebook = await state.opening.get(path); }
    finally { state.opening.delete(path); }
  }
  if(select){state.active = notebook; state.launcher = false; renderWorkspace();}else renderTabs();return notebook;
}
function renderWorkspace() {
  if(editingTitle&&titleNotebook!==active())finishNotebookTitle(false);
  const doc=activeDocument(),n=active();
  if(!n)window.JavaEditor?.dismiss();
  $('#launcher').hidden = !state.launcher; $('#notebook').hidden = !n;
  if(doc?.type!=='csv')window.CsvEditor?.activate(null);
  if(doc?.type!=='text')window.TextEditor?.activate(null);
  if(doc?.type!=='image')window.ImageViewer?.activate(null);
  $('#csv-editor').hidden=doc?.type!=='csv';
  $('#text-editor').hidden=doc?.type!=='text';
  $('#image-viewer').hidden=doc?.type!=='image';
  if(doc?.type==='csv')window.CsvEditor?.activate(doc);
  if(doc?.type==='text')window.TextEditor?.activate(doc);
  if(doc?.type==='image')window.ImageViewer?.activate(doc);
  renderTabs(); renderFiles();
  if (active()) { $('#notebook-name').textContent = active().name.replace(/\.(jnb|ipynb)$/, ''); renderCells(); }
  else $('#notebook-mode').hidden=true;
  updateStatus();
  window.NotebookControls?.restoreFocus();
}
function updateStatus() {
  const n = active(),doc=activeDocument(),csv=doc?.type==='csv'?doc:null,text=doc?.type==='text'?doc:null,image=doc?.type==='image'?doc:null;
  const marked=n?window.NotebookControls?.indexes(n).length||1:0;
  $('#cell-position').textContent = n ? `Cell ${n.document.cells.length ? n.selected+1 : 0} of ${n.document.cells.length}${marked>1?' · '+marked+' selected':''}` : '';
   $('#kernel-status').textContent = text ? `${text.format.toUpperCase()} · No kernel` : csv ? 'CSV · No kernel' : n ? `Java · ${n.kernelStopping ? 'Stopping' : n.kernelStarting ? 'Starting' : !n.kernel ? 'Stopped' : n.running ? 'Busy' : 'Idle'}` : 'Java · JShell';
   $('#status-message').textContent = n ? (n.kernelStopping ? 'Shutting down Java kernel…' : n.kernelStarting ? 'Starting Java kernel… · Cells are ready to edit' : n.configPending ? 'Settings need Apply before running' : !n.kernel ? 'Kernel stopped · run a cell to start a fresh session' : n.running ? 'Running cells…' : n.staleCells?.size ? 'Some outputs are stale · run following cells' : n.dirty ? 'Unsaved changes' : 'All changes saved') : 'Data directory';
  $('#save-state').innerHTML = n?.saving ? 'Saving…' : n?.dirty ? 'Unsaved' : icon('check')+' Saved';
   $('#kernel-dot').classList.toggle('busy', !!n?.running || !!n?.kernelStarting);
  $('#cell-type').value = n?.document.cells[n.selected]?.cell_type || 'code';
  $('#cell-type').disabled=!n;
  if(csv){$('#cell-position').textContent=`${csv.table.rows.length} records`;$('#status-message').textContent=csv.busy?'Working with CSV…':csv.error?'CSV needs attention':csv.dirty?'Unsaved CSV changes':'All changes saved';}
  if(text){$('#cell-position').textContent=`${text.content.split('\n').length} lines`;$('#status-message').textContent=text.busy?'Working with file…':text.error?'File needs attention':text.dirty?'Unsaved text changes':'All changes saved';}
  if(image){$('#cell-position').textContent=`${image.width} × ${image.height}`;$('#kernel-status').textContent='Image · No kernel';$('#status-message').textContent=image.error?'Image needs attention':'Image preview';}
  $$('[data-action="rename"]').forEach(b=>b.disabled=!doc||!!doc.busy||!!doc.running);
  $$('[data-action="save"]').forEach(b=>b.disabled=!doc||doc.type==='image'||!!doc.fileOperation);
  for(const action of ['settings','export-html','download']) $$(`[data-action="${action}"]`).forEach(b=>b.disabled=!n||!!n.fileOperation);
   for (const action of ['run','run-next','run-all']) $$(`[data-action="${action}"]`).forEach(b => b.disabled = !n || n.running || n.kernelStarting || n.configPending || !!n.fileOperation);
   for (const action of ['restart','restart-run-all']) $$(`[data-action="${action}"]`).forEach(b=>b.disabled=!n || n.kernelStarting || n.running || n.configPending || !!n.fileOperation);
  $$('[data-action="interrupt"]').forEach(b => b.disabled = !n?.running);
  $$('[data-action="vibecode"]').forEach(b => b.disabled = !n || n.running || !n.document.cells[n.selected]);
}
function markDirty(n) {
  n.dirty = true; n.revision++; clearTimeout(n.timer);
  n.timer = setTimeout(() => saveNotebook(n).catch(e => notify('Save failed: '+e.message, true)), 1200);
  renderTabs(); updateStatus();
}
async function saveNotebook(n = active(), announce = false) {
  if (!n) return;
  if(n.fileOperation)throw new Error('Wait for the file operation to finish before saving.');
  clearTimeout(n.timer);
  if (n.savePromise) { await n.savePromise; if (!n.dirty) return; }
  const revision = n.revision; n.saving = true; updateStatus();
  n.savePromise = api('/notebooks/'+encodeURIComponent(n.name)+folderQuery(n.folder), 'PUT', notebookSnapshot(n.document));
  try {
    await n.savePromise;
    if (n.revision === revision) n.dirty = false;
    if (announce) notify('Notebook saved');
    await refreshFiles();
  } finally { n.saving = false; n.savePromise = null; renderTabs(); updateStatus(); }
}

function markdown(text,options) {
  return LabPresentation.markdown(text,options);
}
function outputHtml(output, values = {}) {
  const form=LabPresentation.formHtml(output,values);
  if(form)return `<div class="cell-output form-output"><div class="cell-prompt"></div><div class="output-content">${form}<button type="button" class="primary form-apply" data-form-action="apply">Apply values & run following cells</button></div></div>`;
  const formatted=LabPresentation.codeHtml(output);
  if(formatted)return `<div class="cell-output formatted-cell-output"><div class="cell-prompt"></div><div class="output-content">${formatted}</div></div>`;
  const table=LabPresentation.gridHtml(output);
  if(table)return `<div class="cell-output table-output"><div class="cell-prompt"></div><div class="output-content">${table}</div></div>`;
  const interactive = LabPresentation.echartsHtml(output);
  if (interactive) return `<div class="cell-output chart-output"><div class="cell-prompt"></div><div class="output-content">${interactive}</div></div>`;
  const image = LabPresentation.imageHtml(output);
  if (image) return `<div class="cell-output chart-output"><div class="cell-prompt"></div><div class="output-content">${image}</div></div>`;
  if (!['stream','error','execute_result'].includes(output.output_type)) return '';
  const text = value => Array.isArray(value) ? value.join('') : value ?? '';
  const error = output.output_type === 'error';
  const value = error ? (output.traceback || [`${output.ename}: ${output.evalue}`]).join('\n') : output.output_type === 'stream' ? text(output.text) : text(output.data?.['text/plain']);
  return `<div class="cell-output ${error ? 'error' : output.name === 'stderr' ? 'stderr' : ''}"><div class="cell-prompt">${output.output_type === 'execute_result' ? 'Out ['+(output.execution_count ?? ' ')+']:' : ''}</div><div class="output-content"><pre>${escapeHtml(value)}</pre>${error ? '<button type="button" class="fix-error-button" data-cell-action="fix-error" aria-label="Fix this cell error with AI" title="Fix this cell error with AI">✦ Fix with AI</button>' : ''}</div></div>`;
}
function renderCells() {
  const focused=document.activeElement,hadFocus=$('#cells').contains(focused),wasEditor=focused?.matches?.('textarea');
  const cursor=wasEditor?[focused.selectionStart,focused.selectionEnd]:null;
  window.JavaEditor?.dismiss();
  window.NotebookCharts?.dispose($('#cells'));
  window.NotebookTables?.dispose($('#cells'));
  const n = active(); if (!n) return;
  const headingCounts=new Map();n.markdownPrefixCounts=null;
  $('#cells').innerHTML = n.document.cells.map((c,index) => {
    const rendered = c.cell_type === 'markdown' && !c.editing;
    const textarea = `<textarea aria-label="${c.cell_type} cell ${index+1}" spellcheck="false" autocapitalize="off" autocorrect="off">${escapeHtml(source(c))}</textarea>`;
    if(c.cell_type==='markdown'&&index===n.selected)n.markdownPrefixCounts={id:c.id,counts:new Map(headingCounts)};
    const mdOptions={idPrefix:'md-'+c.id+'-',headingCounts};
    const input = rendered ? `<div class="markdown-render" tabindex="0" title="Double-click to edit with live preview">${source(c).trim() ? markdown(source(c),mdOptions) : '<span class="markdown-placeholder">Double-click to write Markdown…</span>'}</div>`
      : c.cell_type === 'markdown' ? `<div class="markdown-editor"><div class="markdown-editor-heading"><span>MARKDOWN</span><span>LIVE PREVIEW</span></div><div class="markdown-editor-columns"><div class="cell-editor">${textarea}</div><div class="markdown-render markdown-live" aria-label="Live Markdown preview">${markdown(source(c),mdOptions)}</div></div></div>`
      : c.cell_type === 'code' ? `<div class="cell-editor code-editor"><div class="highlight-layer" aria-hidden="true"><pre>${LabPresentation.highlightJava(source(c))}\n</pre></div>${textarea}</div>`
      : `<div class="cell-editor">${textarea}</div>`;
    const outputs=(c.outputs||[]).map(o=>outputHtml(o,formValues(n))).join('');
    const hidden=!!c.metadata?.jupyter?.outputs_hidden;
    const heading=window.NotebookModel?.heading(c);
    return `<article class="cell ${index === n.selected ? 'selected' : ''} ${n.staleCells?.has(c.id) && outputs ? 'stale-output' : ''}" data-cell="${index}" tabindex="${index===n.selected?0:-1}"><div class="cell-actions">${heading?'<button class="icon-button" data-cell-action="heading" title="Collapse or expand heading">'+(c.metadata.java_lab?.heading_collapsed?'›':'⌄')+'</button>':''}<button class="icon-button vibe-cell-button" data-cell-action="vibecode" title="AI edit this cell or selected text">✦ AI</button>${window.Vibecode?.canUndo(c) ? '<button class="icon-button vibe-cell-button" data-cell-action="undo-ai" title="Undo the last AI edit">Undo AI edit</button>' : ''}${c.cell_type === 'markdown' ? `<button class="icon-button preview-toggle" data-cell-action="preview" title="Toggle Markdown editing and preview">${rendered ? 'Edit & preview' : 'Done'}</button>` : ''}<button class="icon-button" data-cell-action="run" title="Run cell">${icon('play')}</button><button class="icon-button" data-cell-action="up" title="Move up">${icon('up')}</button><button class="icon-button" data-cell-action="down" title="Move down">${icon('down')}</button><button class="icon-button" data-cell-action="delete" title="Delete cell">${icon('trash')}</button></div><div class="cell-input-row"><div class="cell-prompt">${c.cell_type === 'code' ? 'In ['+(c.busy ? '*' : c.execution_count ?? ' ')+']:' : ''}</div>${input}</div>${hidden&&outputs?'<button class="show-outputs" data-action="toggle-outputs">Show hidden outputs</button>':''}<div class="cell-outputs ${c.metadata?.scrolled?'output-scrolled':''}" ${hidden?'hidden':''}>${outputs}</div></article>`;
  }).join('');
   // Batch layout writes and reads instead of forcing one layout per cell.
   const editors=$$('#cells textarea');
   editors.forEach(editor=>{editor.style.height='0px';});
   const heights=editors.map(editor=>Math.max(46,editor.scrollHeight));
   editors.forEach((editor,index)=>{editor.style.height=heights[index]+'px';});
  window.NotebookControls?.decorate(n);
  window.NotebookCharts?.mount($('#cells'));
  window.NotebookTables?.mount($('#cells'));
  if(hadFocus&&window.NotebookControls) {
    const current=$(`#cells [data-cell="${n.selected}"] textarea`);
    if(wasEditor&&current){current.focus({preventScroll:true});current.setSelectionRange(Math.min(cursor[0],current.value.length),Math.min(cursor[1],current.value.length));}
    else NotebookControls.command(n);
  }
}
function sizeEditor(editor) { editor.style.height = '0px'; editor.style.height = Math.max(46,editor.scrollHeight)+'px'; }
function updateEditor(editor) {
  sizeEditor(editor);
  const element = editor.closest('[data-cell]');
  const highlighted = $('.highlight-layer pre',element);
  if (highlighted) highlighted.innerHTML = LabPresentation.highlightJava(editor.value)+'\n';
  if($('.markdown-live',element))renderMarkdownPreviews(active());
}
function renderMarkdownPreviews(n=active()) {
  if(!n)return;
  const headings=new Map(),slugs=[];
  n.document.cells.forEach((cell,index)=>{
    if(cell.cell_type!=='markdown')return;
    const root=$(`#cells [data-cell="${index}"]`),editor=$('textarea',root),preview=$('.markdown-live,.markdown-render',root);
    if(preview)preview.innerHTML=markdown(editor?.value??source(cell),{headingCounts:headings,collectHeading:slug=>slugs.push(slug)});
  });
  n.markdownHeadingSlugs=slugs;
}

function downloadFile(name, content, type) {
  const url = URL.createObjectURL(new Blob([content], {type}));
  const link = document.createElement('a'); link.href=url; link.download=name; link.click();
  setTimeout(()=>URL.revokeObjectURL(url),1000);
}
async function exportHtml() {
  const n = active(); if (!n) return;
  await saveNotebook(n);
   const snapshot = notebookSnapshot(n.document);
  if((snapshot.metadata?.java_lab?.variables?.length || Object.keys(snapshot.metadata?.java_lab?.form_values || {}).length) && !await modal('Export HTML with outputs?',
    'HTML export includes saved cell outputs and form values, including anything sensitive you entered or printed. Review them before sharing. For a notebook without outputs and form values, use “Download share-safe notebook”.','Export HTML'))return;
  const hasECharts = snapshot.cells.some(c => (c.outputs || []).some(o => !!LabPresentation.echartsHtml(o)));
  const hasGrid=snapshot.cells.some(c=>(c.outputs||[]).some(o=>!!LabPresentation.gridHtml(o)));
  const [chartAssets,tableAssets]=await Promise.all([hasECharts?NotebookCharts.exportAssets():{},hasGrid?NotebookTables.exportAssets():{}]);
  const assets={...chartAssets,...tableAssets};
  const imagePaths=[...new Set(snapshot.cells.filter(c=>c.cell_type==='markdown').flatMap(c=>LabPresentation.markdownImagePaths(source(c))))];
  const markdownSlugs=new Set();for(const c of snapshot.cells)if(c.cell_type==='markdown')LabPresentation.markdown(source(c),{headingCounts:new Map(),collectHeading:slug=>markdownSlugs.add(slug)});
  for(const match of snapshot.cells.filter(c=>c.cell_type==='markdown').flatMap(c=>[...source(c).matchAll(/\[[^\]]+\]\(#([^\s)]+)\)/g)])){
    let fragment;try{fragment=decodeURIComponent(match[1]);}catch(_){continue;}
    const slug=fragment.normalize('NFKD').replace(/[\u0300-\u036f]/g,'').replace(/[`*_]/g,'').toLowerCase().replace(/[^\p{L}\p{N}]+/gu,'-').replace(/^-|-$/g,'')||'section';
    if(!markdownSlugs.has(slug))throw new Error(`Markdown section link not found: #${fragment}`);
  }
  if(imagePaths.length>100)throw new Error('HTML export supports up to 100 referenced Markdown image files.');
  assets.markdownImages={};let imageBytes=0;
  for(const path of imagePaths){const image=await api('/files/image-data?path='+encodeURIComponent(path),'GET',undefined,{cache:'no-store'});imageBytes+=image.dataUri.length;if(imageBytes>32000000)throw new Error('Markdown image assets exceed the 32 MB encoded export limit.');assets.markdownImages[path]=image.dataUri;}
  downloadFile(n.name.replace(/\.(jnb|ipynb)$/,'')+'.html',LabPresentation.exportHtml(n.name,snapshot,assets),'text/html;charset=utf-8');
  notify('HTML exported · review outputs before sharing');
}
function selectCell(index, focus = false) {
  const n = active(); if (!n) return;
  if(window.NotebookControls)NotebookControls.selected(n,index);
  else {n.selected = Math.max(0,Math.min(index,n.document.cells.length-1));$$('#cells .cell').forEach((c,i) => c.classList.toggle('selected',i===n.selected));}
  updateStatus();
  if (focus) {if(window.NotebookControls)NotebookControls.edit(n);else {const target = $(`#cells [data-cell="${n.selected}"]`); $('textarea',target)?.focus();target?.scrollIntoView({block:'nearest'});}}
}
function insertCell(index, type = 'code', text = '') {
  if(window.NotebookControls){NotebookControls.insert(index,type,text,true);return;}
  const n = active(); if (!n || n.running) return;
  n.document.cells.splice(index,0,cell(type,text)); n.selected = index;
  markDirty(n); renderCells(); selectCell(index,true);
}
async function evaluateCell(n, c) {
  if (c.cell_type === 'markdown') { delete c.editing; if(active()===n) renderCells(); return true; }
  if (c.cell_type !== 'code') return true;
  c.busy = true;n.executingCell=c;n.cellStartedAt=Date.now();renderKernels();
  const timing=setInterval(()=>{const label=$(`[data-kernel-timing="${n.uid}"]`);if(label)label.textContent=kernelTiming(n);},1000);
  if (active()===n) renderCells();
  try {
    const code=source(c);n.executionHistory ||= [];if(n.executionHistory.at(-1)!==code)n.executionHistory.push(code);if(n.executionHistory.length>100)n.executionHistory.shift();n.historyBrowse=null;
    const values=formValues(n);
    if(Object.keys(values).length>200 || JSON.stringify(values).length>100000)throw new Error('Too many form values (maximum 200 fields / 100 KB).');
    const result = await api(`/kernels/${n.kernel}/execute`, 'POST', {code,formValues:values});
    c.execution_count = result.executionCount; c.outputs = result.outputs; hydrateFormValues(n); clearStaleThrough(n,n.document.cells.indexOf(c)); markDirty(n);
    return result.success;
  } finally { clearInterval(timing);delete n.executingCell;delete n.cellStartedAt;delete c.busy;renderKernels();if(active()===n) renderCells(); }
}
async function runSelected(next = false, insert = false) {
   const n = active(); if (!n || n.running || !n.document.cells.length) return;
   if(n.kernelStarting){notify('Java kernel is still starting. Please try again shortly.');return;}
   if (n.configPending) { notify('Apply notebook settings before running cells.', true); return; }
   if(n.fileOperation)return;
   if(!n.kernel)await startKernel(n);
  if(!validVisibleForms())return;
  const indices=window.NotebookControls?.indexes(n)||[n.selected],index=indices.at(-1),mode=n.controls?.mode||'command';
  const cursor=$(`#cells [data-cell="${n.selected}"] textarea`)?.selectionStart??0;
  n.running = true;n.cancelRun=false;updateStatus(); renderKernels();let success=true;
  try {for(const i of indices){if(n.cancelRun){success=false;break;}if(!await evaluateCell(n,n.document.cells[i])){success=false;selectCell(i);notify(`Execution stopped at cell ${i+1} because of an error.`,true);break;}}}
  finally { n.running = false; updateStatus(); renderKernels(); }
  if (active() === n && next&&success) {
    if (insert || index === n.document.cells.length-1) insertCell(index+1);
    else {selectCell(index+1);window.NotebookControls?.command(n);}
  }else if(active()===n&&window.NotebookControls){if(mode==='edit'&&n.controls?.mode==='edit')NotebookControls.edit(n,cursor);else NotebookControls.command(n);}
}
async function runAll(start = 0, target = active()) {
   const n = target; if (!n || n.running) return;
   if(n.kernelStarting){notify('Java kernel is still starting. Please try again shortly.');return;}
   if (n.configPending) { notify('Apply notebook settings before running cells.', true); return; }
   if(n.fileOperation)return;
   if(!n.kernel)await startKernel(n);
  if(!validVisibleForms())return;
  n.running = true; n.cancelRun = false; updateStatus(); renderKernels();
  try { for (const c of n.document.cells.slice(start)) {
    if (n.cancelRun) break;
    if (!await evaluateCell(n,c)) {
      n.selected=n.document.cells.indexOf(c);
      if(active()===n)selectCell(n.selected,true);
      notify(`Run all stopped at cell ${n.selected+1} because of an error. Later cells were not run.`,true);break;
    }
  } }
  finally { n.running = false; updateStatus(); renderKernels();if(active()===n)window.NotebookControls?.restoreFocus(); }
}
async function applyFormAndRun(button) {
  const n=active(); if(!n || n.running)return;
  const form=button.closest('.lab-form') || button.closest('.form-output')?.querySelector('.lab-form');
  if(!form)return;
  const controls=$$('[data-form-field]',form);
  const invalid=controls.find(control=>!control.checkValidity());
  if(invalid){invalid.reportValidity();invalid.focus();notify('Correct the highlighted form field before running.',true);return;}
  const index=Number(button.closest('[data-cell]').dataset.cell);
  if(index+1 >= n.document.cells.length){notify('Values applied. Add a following cell to use them.');return;}
  await runAll(index+1,n);
}
async function startKernel(n) {
  if(n.kernelStarting)return n.kernelPromise;
  if(n.running||n.fileOperation)throw new Error('Wait for execution or file operations to finish.');
  if(n.configPending)throw new Error('Apply notebook settings before starting the kernel.');
  n.kernelStarting=true;renderKernels();updateStatus();
  n.kernelPromise=(async()=>{
    if(n.dirty||n.savePromise)await saveNotebook(n);
    const replacement=await api('/kernels','POST',{notebook:n.path});
    const old=n.kernel;n.kernel=replacement.id;
    n.staleCells=new Set(n.document.cells.filter(c=>c.outputs?.length).map(c=>c.id));
    if(old)api(`/kernels/${old}`,'DELETE').catch(e=>notify('Old kernel cleanup failed: '+e.message,true));
  })().finally(()=>{n.kernelStarting=false;renderKernels();updateStatus();if(active()===n)renderCells();});
  return n.kernelPromise;
}
async function interruptKernel(n=active()) {
  if(!n?.running||!n.kernel)return;
  n.cancelRun=true;renderKernels();
  try {await api(`/kernels/${n.kernel}/interrupt`,'POST');notify('Interrupt requested');}
  catch(e){n.cancelRun=false;renderKernels();throw e;}
}
async function shutdownKernel(n) {
  if(!n?.kernel||n.kernelStarting)return;
  if(n.running)throw new Error('Interrupt execution before shutting down the kernel.');
  if(n.fileOperation)throw new Error('Wait for the file operation to finish.');
  if(!await modal('Shut down kernel?',`The Java session for <strong>${escapeHtml(n.name)}</strong> will be reset. The notebook tab, cells, and outputs will stay open.`,'Shut down'))return;
  if(n.running||n.kernelStarting||n.fileOperation)throw new Error('Kernel state changed. Try again when it is idle.');
  n.kernelStarting=true;n.kernelStopping=true;renderKernels();updateStatus();
  n.kernelPromise=(async()=>{
    await api(`/kernels/${n.kernel}`,'DELETE');n.kernel=null;
    n.staleCells=new Set(n.document.cells.filter(c=>c.outputs?.length).map(c=>c.id));
    notify('Kernel shut down · run a cell to start a fresh session');
  })().finally(()=>{n.kernelStarting=false;delete n.kernelStopping;renderKernels();updateStatus();if(active()===n)renderCells();});
  return n.kernelPromise;
}
async function restartKernel(runAfter = false, n = active()) {
   if (!n) return;
   if(n.kernelStarting){notify('Java kernel is still starting. Please try again shortly.');return;}
  if(n.configPending)throw new Error('Apply notebook settings before restarting the kernel.');
  if (!await modal(runAfter ? 'Restart kernel & run all?' : 'Restart kernel?', 'All Java variables, imports, and definitions in this session will be reset. Your cells and outputs will remain.', runAfter ? 'Restart & run all' : 'Restart')) return;
  if(n.running)throw new Error('Interrupt execution before restarting the kernel.');
  if(n.kernelStarting)return;
  await startKernel(n);
  notify('Kernel restarted · fresh Java session');
  if(runAfter)await runAll(0,n);
}
async function closeNotebook(n) {
   if (!n) return;
   if(n.kernelStarting){try{await n.kernelPromise;}catch(_){/* A failed lifecycle request still allows closing the tab. */}}
  if(n.fileOperation)throw new Error('Wait for the file operation to finish before closing.');
  if (n.running) { notify('Interrupt execution before closing the notebook.',true); return; }
  if (n.dirty || n.savePromise) await saveNotebook(n);
  clearTimeout(n.timer); if(n.kernel)await api(`/kernels/${n.kernel}`,'DELETE');
  window.NotebookControls?.closed(n);
  state.notebooks = state.notebooks.filter(item => item !== n);
  state.tabs=openDocuments().filter(item=>item!==n);
  if (state.active === n) { state.active = openDocuments().at(-1) || null; state.launcher = !state.active; }
  renderWorkspace();
}
function modal(title, body, confirm = 'Continue', initial = null) {
  return new Promise(resolve => {
    const dialog = $('#modal'); $('#modal-title').textContent = title;
    $('#modal-body').innerHTML = `<div>${body}</div>`+(initial !== null ? '<input id="modal-input" aria-label="Name" required maxlength="255">' : '');
    $('#modal-submit').textContent = confirm; $('#modal-cancel').hidden = confirm === 'Got it';
    if (initial !== null) $('#modal-input').value = initial;
    const finish = value => { dialog.close(); resolve(value); };
    $('#modal-form').onsubmit = event => {event.preventDefault(); finish(initial !== null ? $('#modal-input').value.trim() : true);};
    $('#modal-cancel').onclick = $('#modal-close').onclick = () => finish(null);
    dialog.oncancel = event => {event.preventDefault();finish(null);};
    dialog.showModal(); if (initial !== null) {$('#modal-input').focus();$('#modal-input').select();}
  });
}
async function newNotebook(example = false) {
  return withLoading(example?'Creating introduction…':'Creating notebook…',async()=>{
  const result = await api('/notebooks'+folderQuery(state.folder),'POST', {name:example ? 'Getting started.jnb' : undefined});
  await openNotebook(result.name);
  if (example) {
    const n = active(); n.document.cells = window.gettingStartedCells(cell); markDirty(n); renderCells(); await runAll(); await saveNotebook(n);
  }
  await refreshFiles();
  });
}
async function newFile(extension){
  const folder=state.folder;
  const name=await modal('New '+extension.toUpperCase()+' file','Choose a filename. The file is created in the current data folder.','Create','Untitled.'+extension);
  if(!name)return;return withLoading('Creating '+name+'…',async()=>{const created=await api('/files','POST',{folder,name,extension});
  await refreshFiles();await openFile(created.name,folder,created.type);
  });
}
async function newFileByName(){
  const name=await modal('New data/text file','Enter a filename with a supported extension, such as report.json, notes.txt or query.sql. Use Upload file for images.','Create file','untitled.txt');
  if(!name)return;
  const extension=name.slice(name.lastIndexOf('.')+1).toLowerCase();
  const extensions=new Set(['csv','json','xml','txt','md','yaml','yml','java','sql','properties','log','css','js','html','htm']);
  if(!extensions.has(extension))throw new Error('Choose a supported text/source extension, or use Upload file.');
  const folder=state.folder;return withLoading('Creating '+name+'…',async()=>{const created=await api('/files','POST',{folder,name,extension});
  await refreshFiles();await openFile(created.name,folder,created.type);
  });
}
async function newFolder() {
  const name=await modal('New folder','Choose a folder name. Folders can contain notebooks and data files.','Create folder','New folder');
  if(!name)return;
  const path=notebookPath(state.folder,name);
  await api('/notebooks/folders','POST',{path});
  await refreshFiles();notify('Folder created');
}
async function deleteFolder(path) {
  if(!await modal('Delete empty folder?',`<strong>${escapeHtml(path)}</strong> must be empty before it can be deleted.`,'Delete'))return;
  await api('/notebooks/folders?path='+encodeURIComponent(path),'DELETE');
  await refreshFiles();notify('Folder deleted');
}
async function uploadDataFile(file) {
  if(!file)return;
  if(/\.(jnb|ipynb)$/i.test(file.name))throw new Error('Notebook import is not supported by file upload. Upload a data file instead.');
  const folder=state.folder,body=new FormData();body.append('file',file);
  const response=await fetch('api/notebooks/uploads'+folderQuery(folder),{method:'POST',body});
  if(!response.ok){let message=`Upload failed (${response.status})`;try{const error=await response.json();message=error.message||error.detail||message;}catch(_){}throw new Error(message);}
  const result=await response.json();
  await refreshFiles();
  if(/\.(png|jpe?g|gif|bmp|webp|svg)$/i.test(result.name)&&window.ImageViewer)await window.ImageViewer.open(result.name,folder);
  else if(/\.csv$/i.test(result.name) && window.CsvEditor)await window.CsvEditor.open(result.name,folder);
  else if(/\.(json|xml|txt|md|yaml|yml|java|sql|properties|log|css|js|html|htm)$/i.test(result.name) && window.TextEditor)await window.TextEditor.open(result.name,folder);
  else if(Array.isArray(result.preview) && result.preview.length){
    const rows=result.preview.slice(0,21);
    const table=`<div class="csv-preview"><table><thead><tr>${(rows[0]||[]).map(value=>`<th>${escapeHtml(value)}</th>`).join('')}</tr></thead><tbody>${rows.slice(1).map(row=>`<tr>${row.map(value=>`<td>${escapeHtml(value)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
    await modal(`CSV preview · ${result.name}`,`<p>Uploaded to <code>${escapeHtml(result.path)}</code>. First ${rows.length-1} data rows:</p>${table}`,'Got it');
  } else notify(`${result.name || file.name} uploaded`);
}
async function renameNotebook(name = active()?.name, folder = active()?.folder ?? state.folder) {
  if (!name) return;
  const newName = await modal('Rename notebook', 'Choose a name for your notebook.', 'Rename', name);
  if (!newName) return;
  await renameNotebookTo(name,folder,newName);
}
async function renameNotebookTo(name,folder,newName) {
  if(!newName || newName===name || newName===name.replace(/\.(jnb|ipynb)$/,''))return;
  return renameFileTo(name,folder,newName);
}
async function renameFile(name=activeDocument()?.name,folder=activeDocument()?.folder??state.folder){
  if(!name)return;const doc=openDocuments().find(item=>item.path===notebookPath(folder,name));if(doc?.busy||doc?.running)throw new Error('Wait for execution or file operations to finish before renaming.');
  const value=await modal('Rename file','Choose a filename. Renaming changes the name, not the file contents.','Rename',name);
  if(value&&value!==name)await renameFileTo(name,folder,value);
}
async function renameFileTo(name,folder,newName){
   const path=notebookPath(folder,name),doc=openDocuments().find(item=>item.path===path);
   if(doc){
     if(doc.kernelStarting)await doc.kernelPromise;
    if(doc.running||doc.busy)throw new Error('Wait for execution or file operations to finish before renaming.');
    if(doc.type==='csv'||doc.type==='text'){if(!await saveDocument(doc,false))throw new Error('Save the file before renaming it.');}
    else if(doc.type!=='image')await saveNotebook(doc);
  }
  if(doc)setFileOperation(doc,true);
  try{
  const result=await api('/files/rename','PATCH',{path,name:newName});
  if(doc){
    const previousType=doc.type||'notebook';
    if(previousType===result.type){
      Object.assign(doc,{name:result.name,path:result.path});if(result.format){if(doc.format!==result.format)doc.error=null;doc.format=result.format;}
      if(doc.type==='text')window.TextEditor?.refresh(doc);if(doc.type==='image')window.ImageViewer?.refresh(doc);
    }else{
      const index=openDocuments().indexOf(doc),selected=activeDocument()===doc;
      state.tabs=openDocuments().filter(item=>item!==doc);
      if(state.active===doc){state.active=openDocuments().at(-1)||null;if(selected)state.launcher=!state.active;}
      renderWorkspace();
      const replacement=await openFile(result.name,folder,result.type,selected);
      if(replacement){state.tabs=openDocuments().filter(item=>item!==replacement);state.tabs.splice(Math.min(index,state.tabs.length),0,replacement);}
    }
  }
  await refreshFiles();renderWorkspace();notify('File renamed');
  }finally{if(doc){setFileOperation(doc,false);if(doc.document&&doc.dirty)markDirty(doc);}}
}
async function duplicateFile(name,folder=state.folder){
  const path=notebookPath(folder,name),doc=openDocuments().find(item=>item.path===path);
  if(doc){
    if(doc.running||doc.busy)throw new Error('Wait for execution or file operations to finish before duplicating.');
    if(doc.type==='csv'||doc.type==='text'){if(!await saveDocument(doc,false))throw new Error('Save the file before duplicating it.');}
    else if(doc.type!=='image')await saveNotebook(doc);
  }
  if(doc)setFileOperation(doc,true);
  try{
    const result=await api('/files/duplicate','POST',{path});
    await refreshFiles();notify(`Created ${result.name}`);
    return result;
  }finally{if(doc){setFileOperation(doc,false);if(doc.document&&doc.dirty)markDirty(doc);}}
}
function setFileOperation(doc,value){
  doc.fileOperation=value;doc.busy=value;
  if(doc.type==='text')window.TextEditor?.refresh(doc);if(doc.type==='csv')window.CsvEditor?.refresh(doc);
  renderTabs();updateStatus();
}
let editingTitle=false,titleNotebook=null;
function editNotebookTitle() {
  const n=active();if(!n||editingTitle)return;
  editingTitle=true;titleNotebook=n;
  const title=$('#notebook-name'),input=$('#notebook-name-editor');
  input.value=n.name.replace(/\.(jnb|ipynb)$/,'');title.hidden=true;input.hidden=false;
  input.focus();input.select();
}
function finishNotebookTitle(commit=true) {
  if(!editingTitle)return;
  editingTitle=false;
  const title=$('#notebook-name'),input=$('#notebook-name-editor'),n=titleNotebook;
  titleNotebook=null;
  const value=input.value.trim();input.hidden=true;title.hidden=false;
  if(commit&&n&&value)renameNotebookTo(n.name,n.folder,value).catch(e=>notify(e.message,true));
}
async function moveFile(name,sourceFolder,targetFolder) {
  if(targetFolder===sourceFolder)return;
   const sourcePath=notebookPath(sourceFolder,name),n=openDocuments().find(open=>open.path===sourcePath);
   if(n){
     if(n.kernelStarting)await n.kernelPromise;
    if(n.busy)throw new Error('Wait for the file operation to finish before moving it.');
    if(['csv','text'].includes(n.type)){const editor=n.type==='csv'?window.CsvEditor:window.TextEditor;if(n.busy||!await editor.save(n))throw new Error(`Save the ${n.type==='csv'?'CSV':'file'} before moving it.`);}
    else if(n.type!=='image'){if(n.running)throw new Error('Interrupt execution before moving this notebook.');await saveNotebook(n);}
  }
  if(n)setFileOperation(n,true);
  try{
  const result=await api('/notebooks/move','POST',{path:sourcePath,folder:targetFolder});
  if(n){n.folder=targetFolder;n.path=result.path;n.name=result.name;}
  await refreshFiles();renderWorkspace();notify(`${name} moved to ${targetFolder||'Data directory'}`);
  }finally{if(n){setFileOperation(n,false);if(n.document&&n.dirty)markDirty(n);}}
}
async function deleteNotebook(name, folder = state.folder) {
  if (!await modal('Delete notebook?', `<strong>${escapeHtml(name)}</strong> will be permanently deleted from the data directory.`, 'Delete')) return;
  const n = state.notebooks.find(n => n.path === notebookPath(folder,name)); if(n) {if(n.running) throw new Error('Interrupt execution before deleting this notebook.'); await closeNotebook(n);}
  await api('/notebooks/'+encodeURIComponent(name)+folderQuery(folder),'DELETE'); await refreshFiles(); notify('Notebook deleted');
}
async function deleteFile(name, folder = state.folder) {
  const path=notebookPath(folder,name);
  if(!await modal('Delete file?',`<strong>${escapeHtml(path)}</strong> will be permanently deleted from the data directory. Unsaved changes will be discarded.`,'Delete'))return;
  const doc=openDocuments().find(item=>item.path===path);
  if(doc?.busy||doc?.savePromise)throw new Error('Wait for file operations to finish before deleting this file.');
  if(doc)setFileOperation(doc,true);
  try{
    await api('/files?path='+encodeURIComponent(path),'DELETE');
    if(doc){
      window.NotebookControls?.closed(doc);
      state.tabs=openDocuments().filter(item=>item!==doc);
      if(state.active===doc){state.active=openDocuments().at(-1)||null;state.launcher=!state.active;}
      renderWorkspace();
    }
    await refreshFiles();notify('File deleted');
  }finally{if(doc)setFileOperation(doc,false);}
}
function help(topic='overview') { return window.LabHelp?.open(topic); }
const actions = {
  new: () => newNotebook(), example: () => newNotebook(true), refresh:refreshFiles,
  'new-folder':newFolder, upload:()=>{$('#upload-input').accept='';$('#upload-input').click();},
  'new-file':newFileByName,'new-csv':()=>newFile('csv'),'new-json':()=>newFile('json'),'new-xml':()=>newFile('xml'),'new-txt':()=>newFile('txt'),
  'new-md':()=>newFile('md'),'new-yaml':()=>newFile('yaml'),'new-java':()=>newFile('java'),'new-sql':()=>newFile('sql'),'new-properties':()=>newFile('properties'),'new-log':()=>newFile('log'),'new-css':()=>newFile('css'),'new-js':()=>newFile('js'),'new-html':()=>newFile('html'),
  'new-image':()=>{$('#upload-input').accept='image/png,image/jpeg,image/gif,image/bmp,image/webp,image/svg+xml';$('#upload-input').click();},
  launcher: () => {state.launcher=true;renderWorkspace();},
  sidebar: () => {
    const sidebar = $('#sidebar');
    if (innerWidth <= 760) {sidebar.hidden=false;sidebar.classList.toggle('revealed');}
    else sidebar.hidden = !sidebar.hidden;
    window.KernelResources?.refresh();
  },
  save: () => saveDocument(), rename: () => renameFile(), close: () => closeDocument(),
  settings: () => window.NotebookSettings?.open(active()),
  run: () => runSelected(), 'run-next': () => runSelected(true), 'run-all':()=>runAll(), restart:()=>restartKernel(), 'restart-run-all':()=>restartKernel(true),
  interrupt: () => interruptKernel(),
  add: () => insertCell((active()?.selected ?? -1)+1), 'add-end': () => insertCell(active()?.document.cells.length || 0),
  duplicate: () => {const n=active();if(n && !n.running && n.document.cells[n.selected]){const copy=structuredClone(n.document.cells[n.selected]);copy.id=crypto.randomUUID();delete copy.busy;n.document.cells.splice(n.selected+1,0,copy);n.selected++;markDirty(n);renderCells();}},
  'delete-cell': () => {const n=active();if(n && !n.running){n.document.cells.splice(n.selected,1);n.selected=Math.max(0,Math.min(n.selected,n.document.cells.length-1));markDirty(n);renderCells();}},
  clear: () => {const n=active();if(n && !n.running){n.document.cells.forEach(c=>{if(c.cell_type==='code'){c.outputs=[];c.execution_count=null;}});markDirty(n);renderCells();}},
  download: async () => {const n=active();if(!n)return;await saveNotebook(n);const snapshot=notebookSnapshot(n.document);snapshot.cells=snapshot.cells.map(c=>c.cell_type==='code'?{...c,outputs:[],execution_count:null}:c);if(snapshot.metadata?.java_lab){snapshot.metadata={...snapshot.metadata,java_lab:{...snapshot.metadata.java_lab}};delete snapshot.metadata.java_lab.form_values;}downloadFile(n.name.replace(/\.ipynb$/,'.jnb'),JSON.stringify(snapshot,null,2),'application/json');notify('Downloaded without cell outputs or form values. Check code and non-secret defaults before sharing.');},
  'export-html': exportHtml,
  vibecode: () => window.Vibecode?.open(),
  help
};
async function dispatch(action) {try {await actions[action]?.();} catch(e){notify(e.message,true);}}

document.addEventListener('click', async event => {
  const target = event.target;
  const workspaceLink=target.closest('a[data-workspace-link]');
  if(workspaceLink){event.preventDefault();try{const file=await api('/files/info?path='+encodeURIComponent(workspaceLink.dataset.workspaceLink));const slash=file.path.lastIndexOf('/');await openFile(file.name,slash<0?'':file.path.slice(0,slash),file.type);}catch(e){notify(e.message,true);}return;}
  const sectionLink=target.closest('a[data-md-anchor-link]');
  if(sectionLink&&target.closest('#notebook')){event.preventDefault();const slug=sectionLink.dataset.mdAnchorLink;const headings=[...document.querySelectorAll('#notebook [data-md-anchor]')];const local=sectionLink.closest('[data-cell]');const heading=headings.find(h=>h.dataset.mdAnchor===slug&&local?.contains(h))||headings.find(h=>h.dataset.mdAnchor===slug);if(heading)heading.scrollIntoView({block:'start'});else notify('Markdown section not found.',true);return;}
  if(target.closest('#notebook-name')){editNotebookTitle();return;}
  if(target.closest('#csv-title,#text-title,#image-title')){dispatch('rename');return;}
  const menuTrigger = target.closest('.menu-trigger');
  $$('.menu').forEach(menu=>{if(menu!==menuTrigger?.parentElement)menu.classList.remove('open');});
  if(menuTrigger){menuTrigger.parentElement.classList.toggle('open');return;}
  $('.file-context')?.remove();
  const formAction=target.closest('[data-form-action]');if(formAction){try{await applyFormAndRun(formAction);}catch(e){notify(e.message,true);}return;}
  const action = target.closest('[data-action]'); if(action){dispatch(action.dataset.action);return;}
  const kernelAction=target.closest('[data-kernel-action]');if(kernelAction){
    if(kernelAction.disabled)return;
    const n=state.notebooks.find(n=>n.uid===kernelAction.dataset.kernelNotebook);
    if(n)try{const action=kernelAction.dataset.kernelAction;if(action==='interrupt')await interruptKernel(n);else if(action==='restart')await restartKernel(false,n);else if(action==='start')await startKernel(n);else if(action==='shutdown')await shutdownKernel(n);}catch(e){notify(e.message,true);}return;
  }
  const close = target.closest('[data-close]'); if(close){try{const doc=openDocuments().find(n=>n.uid===close.dataset.close);if(doc)await closeDocument(doc);}catch(e){notify(e.message,true);}return;}
  const folder = target.closest('[data-folder]'); if(folder){state.folder=folder.dataset.folder;await refreshFiles();return;}
  if(target.closest('[data-parent-folder]')){state.folder=state.folder.split('/').slice(0,-1).join('/');await refreshFiles();return;}
  const file = target.closest('[data-file]'); if(file){try{
    await openFile(file.dataset.file,file.dataset.fileFolder || '',file.dataset.fileType);
  }catch(e){notify(e.message,true);}return;}
  const tab = target.closest('[data-tab]'); if(tab){const doc=openDocuments().find(n=>n.uid===tab.dataset.tab);if(doc){state.active=doc;state.launcher=false;renderWorkspace();}return;}
  const panel = target.closest('[data-panel]'); if(panel){const type=panel.dataset.panel;const sidebar=$('#sidebar');sidebar.hidden=false;if(innerWidth<=760)sidebar.classList.toggle('revealed',state.panel!==type||!sidebar.classList.contains('revealed'));state.panel=type;$('#files-panel').hidden=type!=='files';$('#running-panel').hidden=type!=='running';$('#panel-title').textContent=type==='files'?'FILE BROWSER':'RUNNING KERNELS';$$('[data-panel]').forEach(b=>b.classList.toggle('active',b.dataset.panel===type));renderKernels();return;}
  const element = target.closest('[data-cell]');
  if(element) {
    if(target.closest('.lab-form'))return;
    const index=Number(element.dataset.cell);
    if(window.NotebookControls)NotebookControls.clicked(event,index);else selectCell(index);
    const cellAction=target.closest('[data-cell-action]')?.dataset.cellAction;
    if(cellAction) {
      const n=active();
      if(cellAction==='vibecode')dispatch('vibecode');
      else if(cellAction==='fix-error')window.Vibecode?.fixError();
      else if(cellAction==='undo-ai')window.Vibecode?.undo();
      else if(cellAction==='run')dispatch('run');
      else if(cellAction==='delete')dispatch('delete-cell');
      else if(cellAction==='preview') {
        const c=n.document.cells[n.selected];c.editing=!c.editing;renderCells();
        if(c.editing)selectCell(n.selected,true);else window.NotebookControls?.command(n);
      }else if(cellAction==='heading') {
        const c=n.document.cells[n.selected];window.NotebookControls?.perform(c.metadata.java_lab?.heading_collapsed?'expand-heading':'collapse-heading');
      }else window.NotebookControls?.cellAction(cellAction);
    }
  }
});
$('#file-search').addEventListener('input',renderFiles);
$('#tabs').addEventListener('dblclick',event=>{
  const tab=event.target.closest('[data-tab]');if(!tab||event.target.closest('[data-close]'))return;
  const doc=openDocuments().find(item=>item.uid===tab.dataset.tab);if(doc)renameFile(doc.name,doc.folder).catch(e=>notify(e.message,true));
});
$('#tabs').addEventListener('contextmenu',event=>{
  const tab=event.target.closest('[data-tab]'),doc=tab&&openDocuments().find(item=>item.uid===tab.dataset.tab);if(!doc)return;
  event.preventDefault();$('.file-context')?.remove();const menu=document.createElement('div');menu.className='file-context';menu.style.left=Math.min(event.clientX,innerWidth-170)+'px';menu.style.top=Math.min(event.clientY,innerHeight-100)+'px';menu.innerHTML='<button type="button">Rename file</button>';document.body.append(menu);
  menu.addEventListener('click',()=>{menu.remove();renameFile(doc.name,doc.folder).catch(e=>notify(e.message,true));});
});
for(const id of ['#csv-title','#text-title','#image-title'])$(id).addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();dispatch('rename');}});
$('#notebook-name').addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();editNotebookTitle();}});
$('#notebook-name-editor').addEventListener('keydown',event=>{
  if(event.key==='Enter'){event.preventDefault();finishNotebookTitle();}
  else if(event.key==='Escape'){event.preventDefault();finishNotebookTitle(false);}
});
$('#notebook-name-editor').addEventListener('blur',()=>finishNotebookTitle());
let draggedFile=null;
const fileList=$('#file-list');
const dropFolder=element=>element?.hasAttribute('data-parent-folder')?state.folder.split('/').slice(0,-1).join('/'):element?.dataset.folder;
fileList.addEventListener('dragstart',event=>{
  const file=event.target.closest('[data-file]');if(!file)return;
  draggedFile={name:file.dataset.file,folder:file.dataset.fileFolder||''};
  event.dataTransfer.effectAllowed='move';event.dataTransfer.setData('text/plain',notebookPath(draggedFile.folder,draggedFile.name));
  file.classList.add('dragging');
});
fileList.addEventListener('dragover',event=>{
  const target=event.target.closest('[data-folder],[data-parent-folder]');
  const folder=dropFolder(target);
  $$('.folder-drop-target',fileList).forEach(row=>row.classList.remove('folder-drop-target'));
  if(!draggedFile||folder===undefined||folder===draggedFile.folder)return;
  event.preventDefault();event.dataTransfer.dropEffect='move';target.classList.add('folder-drop-target');
});
fileList.addEventListener('drop',event=>{
  const target=event.target.closest('[data-folder],[data-parent-folder]'),folder=dropFolder(target),file=draggedFile;
  if(!file||folder===undefined||folder===file.folder)return;
  event.preventDefault();draggedFile=null;
  $$('.folder-drop-target,.dragging',fileList).forEach(row=>row.classList.remove('folder-drop-target','dragging'));
  moveFile(file.name,file.folder,folder).catch(e=>notify(e.message,true));
});
fileList.addEventListener('dragend',()=>{draggedFile=null;$$('.folder-drop-target,.dragging',fileList).forEach(row=>row.classList.remove('folder-drop-target','dragging'));});
$('#upload-input').addEventListener('change',event=>{const file=event.target.files?.[0];event.target.value='';uploadDataFile(file).catch(e=>notify(e.message,true));});
function updateFormControl(control) {
  const n=active();if(!n)return;
  const values={...formValues(n)};
  const value=control.dataset.formType==='checkbox'?control.checked:control.dataset.formType==='multiselect'?[...control.selectedOptions].map(option=>option.value):control.dataset.formType==='number'?(control.value===''?null:Number(control.value)):control.value;
  if(typeof value==='number'&&!Number.isFinite(value))return;
  values[control.dataset.formField]=value;
  if(Object.keys(values).length>200 || JSON.stringify(values).length>100000){notify('Too many form values (maximum 200 fields / 100 KB).',true);renderCells();return;}
  n.document.metadata ||= {};n.document.metadata.java_lab ||= {};n.document.metadata.java_lab.form_values=values;markDirty(n);markFollowingStale(n,Number(control.closest('[data-cell]').dataset.cell));
}
$('#cells').addEventListener('input',event=>{if(event.target.matches('.cell-editor textarea')){const n=active();const index=Number(event.target.closest('[data-cell]').dataset.cell);n.document.cells[index].source=[event.target.value];updateEditor(event.target);markDirty(n);}
  const control=event.target.closest('.lab-form [data-form-field]');if(control && !['checkbox','select','multiselect','date'].includes(control.dataset.formType))updateFormControl(control);
});
$('#cells').addEventListener('change',event=>{const control=event.target.closest('.lab-form [data-form-field]');if(control && ['checkbox','select','multiselect','date'].includes(control.dataset.formType))updateFormControl(control);});
$('#cells').addEventListener('error',event=>{if(event.target.matches?.('img.workspace-image,img.chart-image')){const message=document.createElement('div');message.className='chart-render-error';message.textContent=event.target.matches('.workspace-image')?'Image unavailable. Check the workspace-relative Markdown path.':'Image could not be displayed. Rerun this cell to regenerate it.';event.target.replaceWith(message);}},true);
$('#cells').addEventListener('scroll',event=>{if(event.target.matches('textarea')){const pre=$('.highlight-layer pre',event.target.parentElement);if(pre)pre.style.transform=`translate(${-event.target.scrollLeft}px,${-event.target.scrollTop}px)`;}},true);
$('#cells').addEventListener('focusin',event=>{const el=event.target.closest('[data-cell]');if(el&&event.target.matches('.cell-editor textarea')){selectCell(Number(el.dataset.cell));window.NotebookControls?.setEditing();}});
$('#cells').addEventListener('dblclick',event=>{const el=event.target.closest('.markdown-render');if(el){const n=active();n.document.cells[n.selected].editing=true;renderCells();selectCell(n.selected,true);}});
$('#cell-type').addEventListener('change',event=>{if(window.NotebookControls)NotebookControls.changeType(event.target.value);});
$('#file-list').addEventListener('contextmenu',event=>{
  const folder=event.target.closest('[data-folder]');
  if(folder){event.preventDefault();$('.file-context')?.remove();const menu=document.createElement('div');menu.className='file-context';menu.style.left=Math.min(event.clientX,innerWidth-170)+'px';menu.style.top=Math.min(event.clientY,innerHeight-100)+'px';menu.innerHTML='<button type="button">Delete empty folder</button>';document.body.append(menu);menu.addEventListener('click',()=>{menu.remove();deleteFolder(folder.dataset.folder).catch(e=>notify(e.message,true));});return;}
  const file=event.target.closest('[data-file]');if(!file)return;event.preventDefault();$('.file-context')?.remove();
  if(file.dataset.fileType!=='notebook'){
    const menu=document.createElement('div');menu.className='file-context';menu.style.left=Math.min(event.clientX,innerWidth-170)+'px';menu.style.top=Math.min(event.clientY,innerHeight-170)+'px';menu.innerHTML='<button type="button" data-context="rename">Rename file</button><button type="button" data-context="duplicate">Duplicate file</button><button type="button" data-context="download">Download file</button><button type="button" data-context="delete">Delete file</button>';document.body.append(menu);
    menu.addEventListener('click',event=>{const action=event.target.closest('button')?.dataset.context;menu.remove();if(action==='rename')renameFile(file.dataset.file,file.dataset.fileFolder||'').catch(e=>notify(e.message,true));else if(action==='duplicate')duplicateFile(file.dataset.file,file.dataset.fileFolder||'').catch(e=>notify(e.message,true));else if(action==='download')downloadWorkspaceFile(file.dataset.file,file.dataset.fileFolder||'');else if(action==='delete')deleteFile(file.dataset.file,file.dataset.fileFolder||'').catch(e=>notify(e.message,true));});return;
  }
  const menu=document.createElement('div');menu.className='file-context';menu.style.left=Math.min(event.clientX,innerWidth-170)+'px';menu.style.top=Math.min(event.clientY,innerHeight-130)+'px';menu.innerHTML='<button data-context="rename">Rename notebook</button><button data-context="duplicate">Duplicate notebook</button><button data-context="delete">Delete notebook</button>';document.body.append(menu);
  menu.addEventListener('click',event=>{const action=event.target.closest('button')?.dataset.context;menu.remove();const handler=action==='rename'?renameNotebook:action==='duplicate'?duplicateFile:action==='delete'?deleteNotebook:null;if(handler)handler(file.dataset.file,file.dataset.fileFolder).catch(e=>notify(e.message,true));});
});
window.addEventListener('beforeunload',event=>{if(openDocuments().some(n=>n.dirty||n.running)){event.preventDefault();event.returnValue='';}});
window.addEventListener('pagehide',()=>{for(const n of state.notebooks){if(n.kernel)fetch(`api/kernels/${n.kernel}`,{method:'DELETE',keepalive:true}).catch(()=>{});}});
renderWorkspace(); refreshFiles().catch(e=>{$('#connection-state').textContent='Disconnected';notify(e.message,true);});
