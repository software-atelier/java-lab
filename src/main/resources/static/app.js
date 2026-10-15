/* Java Lab — entirely local, dependency-free notebook client. */
const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const icon = name => `<svg aria-hidden="true"><use href="#i-${name}"/></svg>`;
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const source = cell => Array.isArray(cell.source) ? cell.source.join('') : (cell.source || '');
const state = { files: [], folders: [], folder: '', notebooks: [], active: null, launcher: true, panel: 'files', opening: new Map() };
const folderQuery = folder => '?folder=' + encodeURIComponent(folder || '');
const notebookPath = (folder, name) => folder ? folder + '/' + name : name;
let toastTimer;

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
function active() { return state.launcher ? null : state.active; }
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
  $('#folder-path').textContent = state.folder || 'Working directory';
  $('#directory').textContent = result.directory; $('#directory').title = result.directory;
  $('#connection-state').textContent = 'Connected'; renderFiles();
}
function renderFiles() {
  const filter = $('#file-search').value.toLowerCase();
  const files = state.files.filter(f => f.name.toLowerCase().includes(filter));
  $('#file-list').innerHTML = (state.folder ? `<button class="file-row folder-row" data-parent-folder="true" title="Move files to parent folder or open it">${icon('folder')}<span class="file-name">..</span></button>` : '') + state.folders.filter(f=>f.name.toLowerCase().includes(filter)).map(f => `<button class="file-row folder-row" data-folder="${escapeHtml(f.path)}" title="Open ${escapeHtml(f.name)} or drop a file here">${icon('folder')}<span class="file-name">${escapeHtml(f.name)}</span></button>`).join('') + (files.length ? files.map(f => `<button class="file-row ${f.type==='file'?'data-file-row':''} ${active()?.path === notebookPath(state.folder, f.name) ? 'active' : ''}" data-file="${escapeHtml(f.name)}" data-file-type="${escapeHtml(f.type)}" data-file-folder="${escapeHtml(state.folder)}" draggable="true" title="${escapeHtml(f.name)} · ${f.type==='notebook'?'open or drag into a folder':'drag into a folder'}">${icon('file')}<span class="file-name">${escapeHtml(f.name)}</span><time>${dateLabel(f.modified)}</time></button>`).join('') : (!state.folder && !state.folders.length ? `<div class="empty-files">${filter ? 'No matching files.' : 'A fresh workspace.<br>Create your first notebook to begin.'}</div>` : ''));
  const recent=state.files.filter(f=>f.type==='notebook');
  $('#recent-files').innerHTML = recent.length ? [...recent].sort((a,b) => b.modified-a.modified).slice(0,4).map(f => `<button class="recent-row" data-file="${escapeHtml(f.name)}" data-file-type="notebook" data-file-folder="${escapeHtml(state.folder)}">${icon('file')}<span>${escapeHtml(f.name)}</span><time>${dateLabel(f.modified)}</time>${icon('chevron')}</button>`).join('') : '<div class="recent-empty">Your notebooks will appear here. Let’s make the first one.</div>';
}
function renderTabs() {
  $('#tabs').innerHTML = (state.launcher ? '<div class="tab active" data-launcher="true">'+icon('grid')+'<span class="tab-name">Launcher</span></div>' : '') + state.notebooks.map(n => `<div class="tab ${active() === n ? 'active' : ''}" data-tab="${n.uid}" role="button" tabindex="0">${icon('file')}<span class="tab-name">${escapeHtml(n.name)}</span>${n.dirty ? '<span class="dirty-dot" title="Unsaved changes"></span>' : ''}<button class="icon-button" data-close="${n.uid}" title="Close notebook" aria-label="Close ${escapeHtml(n.name)}">${icon('close')}</button></div>`).join('');
  renderKernels();
}
function renderKernels() {
  $('#kernel-list').innerHTML = state.notebooks.length ? state.notebooks.map(n => `<div class="kernel-row">${icon('terminal')}<span title="${escapeHtml(n.name)}">${escapeHtml(n.name)}<br><small>${n.running ? 'Busy' : 'Idle'} · Java</small></span><button class="icon-button" data-close="${n.uid}" title="Close notebook and shut down kernel">${icon('close')}</button></div>`).join('') : '<div class="empty-files">No running kernels.</div>';
}

async function openNotebook(name, folder = state.folder) {
  const path = notebookPath(folder, name);
  let notebook = state.notebooks.find(n => n.path === path);
  if (!notebook) {
    if (!state.opening.has(path)) {
      state.opening.set(path, (async () => {
        const document = await api('/notebooks/' + encodeURIComponent(name) + folderQuery(folder));
        let kernel=null,kernelError=null;
        try{kernel=await api('/kernels', 'POST', {notebook:path});}
        catch(e){kernelError=e;}
        // Preserve notebook metadata and standard output objects on round trip.
        const opened = {uid:crypto.randomUUID(), name, folder, path, document, kernel:kernel?.id||null, configPending:!!kernelError, selected:0, dirty:false, revision:0, running:false, timer:null};
        state.notebooks.push(opened);
        hydrateFormValues(opened);
        if(kernelError)notify('Kernel could not start: '+kernelError.message+'. Open Settings to fix libraries or values.',true);
        return opened;
      })());
    }
    try { notebook = await state.opening.get(path); }
    finally { state.opening.delete(path); }
  }
  state.active = notebook; state.launcher = false; renderWorkspace();
}
function renderWorkspace() {
  if(editingTitle&&titleNotebook!==active())finishNotebookTitle(false);
  $('#launcher').hidden = !state.launcher; $('#notebook').hidden = state.launcher;
  renderTabs(); renderFiles();
  if (active()) { $('#notebook-name').textContent = active().name.replace(/\.(jnb|ipynb)$/, ''); renderCells(); }
  else $('#notebook-mode').hidden=true;
  updateStatus();
  window.NotebookControls?.restoreFocus();
}
function updateStatus() {
  const n = active();
  const marked=n?window.NotebookControls?.indexes(n).length||1:0;
  $('#cell-position').textContent = n ? `Cell ${n.document.cells.length ? n.selected+1 : 0} of ${n.document.cells.length}${marked>1?' · '+marked+' selected':''}` : '';
  $('#kernel-status').textContent = n ? `Java · ${!n.kernel ? 'Not started' : n.running ? 'Busy' : 'Idle'}` : 'Java · JShell';
  $('#status-message').textContent = n ? (n.configPending ? 'Settings need Apply before running' : n.running ? 'Running cells…' : n.staleCells?.size ? 'Some outputs are stale · run following cells' : n.dirty ? 'Unsaved changes' : 'All changes saved') : 'Working directory';
  $('#save-state').innerHTML = n?.saving ? 'Saving…' : n?.dirty ? 'Unsaved' : icon('check')+' Saved';
  $('#kernel-dot').classList.toggle('busy', !!n?.running);
  $('#cell-type').value = n?.document.cells[n.selected]?.cell_type || 'code';
  for (const action of ['run','run-next','run-all']) $$(`[data-action="${action}"]`).forEach(b => b.disabled = !n || n.running || n.configPending || !n.kernel);
  for (const action of ['restart','restart-run-all']) $$(`[data-action="${action}"]`).forEach(b=>b.disabled=!n || n.running || n.configPending);
  $$('[data-action="interrupt"]').forEach(b => b.disabled = !n?.running);
  $$('[data-action="vibecode"]').forEach(b => b.disabled = !n || n.running || n.document.cells[n.selected]?.cell_type !== 'code');
}
function markDirty(n) {
  n.dirty = true; n.revision++; clearTimeout(n.timer);
  n.timer = setTimeout(() => saveNotebook(n).catch(e => notify('Save failed: '+e.message, true)), 1200);
  renderTabs(); updateStatus();
}
async function saveNotebook(n = active(), announce = false) {
  if (!n) return;
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

function markdown(text) {
  return LabPresentation.markdown(text);
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
  $('#cells').innerHTML = n.document.cells.map((c,index) => {
    const rendered = c.cell_type === 'markdown' && !c.editing;
    const textarea = `<textarea aria-label="${c.cell_type} cell ${index+1}" spellcheck="false" autocapitalize="off" autocorrect="off">${escapeHtml(source(c))}</textarea>`;
    const input = rendered ? `<div class="markdown-render" tabindex="0" title="Double-click to edit with live preview">${source(c).trim() ? markdown(source(c)) : '<span class="markdown-placeholder">Double-click to write Markdown…</span>'}</div>`
      : c.cell_type === 'markdown' ? `<div class="markdown-editor"><div class="markdown-editor-heading"><span>MARKDOWN</span><span>LIVE PREVIEW</span></div><div class="markdown-editor-columns"><div class="cell-editor">${textarea}</div><div class="markdown-render markdown-live" aria-label="Live Markdown preview">${markdown(source(c))}</div></div></div>`
      : c.cell_type === 'code' ? `<div class="cell-editor code-editor"><div class="highlight-layer" aria-hidden="true"><pre>${LabPresentation.highlightJava(source(c))}\n</pre></div>${textarea}</div>`
      : `<div class="cell-editor">${textarea}</div>`;
    const outputs=(c.outputs||[]).map(o=>outputHtml(o,formValues(n))).join('');
    const hidden=!!c.metadata?.jupyter?.outputs_hidden;
    const heading=window.NotebookModel?.heading(c);
    return `<article class="cell ${index === n.selected ? 'selected' : ''} ${n.staleCells?.has(c.id) && outputs ? 'stale-output' : ''}" data-cell="${index}" tabindex="${index===n.selected?0:-1}"><div class="cell-actions">${heading?'<button class="icon-button" data-cell-action="heading" title="Collapse or expand heading">'+(c.metadata.java_lab?.heading_collapsed?'›':'⌄')+'</button>':''}${c.cell_type === 'code' ? `<button class="icon-button vibe-cell-button" data-cell-action="vibecode" title="AI edit this cell or selected text">✦ Vibecode</button>${window.Vibecode?.canUndo(c) ? '<button class="icon-button vibe-cell-button" data-cell-action="undo-ai" title="Undo the last AI edit">Undo AI edit</button>' : ''}` : ''}${c.cell_type === 'markdown' ? `<button class="icon-button preview-toggle" data-cell-action="preview" title="Toggle Markdown editing and preview">${rendered ? 'Edit & preview' : 'Done'}</button>` : ''}<button class="icon-button" data-cell-action="run" title="Run cell">${icon('play')}</button><button class="icon-button" data-cell-action="up" title="Move up">${icon('up')}</button><button class="icon-button" data-cell-action="down" title="Move down">${icon('down')}</button><button class="icon-button" data-cell-action="delete" title="Delete cell">${icon('trash')}</button></div><div class="cell-input-row"><div class="cell-prompt">${c.cell_type === 'code' ? 'In ['+(c.busy ? '*' : c.execution_count ?? ' ')+']:' : ''}</div>${input}</div>${hidden&&outputs?'<button class="show-outputs" data-action="toggle-outputs">Show hidden outputs</button>':''}<div class="cell-outputs ${c.metadata?.scrolled?'output-scrolled':''}" ${hidden?'hidden':''}>${outputs}</div></article>`;
  }).join('');
  $$('#cells textarea').forEach(sizeEditor);
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
  const preview = $('.markdown-live',element);
  if (preview) preview.innerHTML = markdown(editor.value);
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
  c.busy = true; if (active()===n) renderCells();
  try {
    const code=source(c);n.executionHistory ||= [];if(n.executionHistory.at(-1)!==code)n.executionHistory.push(code);if(n.executionHistory.length>100)n.executionHistory.shift();n.historyBrowse=null;
    const values=formValues(n);
    if(Object.keys(values).length>200 || JSON.stringify(values).length>100000)throw new Error('Too many form values (maximum 200 fields / 100 KB).');
    const result = await api(`/kernels/${n.kernel}/execute`, 'POST', {code,formValues:values});
    c.execution_count = result.executionCount; c.outputs = result.outputs; hydrateFormValues(n); clearStaleThrough(n,n.document.cells.indexOf(c)); markDirty(n);
    return result.success;
  } finally { delete c.busy; if(active()===n) renderCells(); }
}
async function runSelected(next = false, insert = false) {
  const n = active(); if (!n || n.running || !n.document.cells.length) return;
  if (n.configPending) { notify('Apply notebook settings before running cells.', true); return; }
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
  if (n.configPending) { notify('Apply notebook settings before running cells.', true); return; }
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
async function restartKernel(runAfter = false) {
  const n = active(); if (!n) return;
  if(n.configPending)throw new Error('Apply notebook settings before restarting the kernel.');
  if (!await modal(runAfter ? 'Restart kernel & run all?' : 'Restart kernel?', 'All Java variables, imports, and definitions in this session will be reset. Your cells and outputs will remain.', runAfter ? 'Restart & run all' : 'Restart')) return;
  if(n.running)throw new Error('Interrupt execution before restarting the kernel.');
  if(n.dirty || n.savePromise)await saveNotebook(n);
  n.cancelRun=true;const replacement=await api('/kernels','POST',{notebook:n.path});
  const old=n.kernel;n.kernel=replacement.id;n.configPending=false;
  api(`/kernels/${old}`, 'DELETE').catch(e=>notify('Old kernel cleanup failed: '+e.message,true));
  n.staleCells=new Set(n.document.cells.filter(c=>c.outputs?.length).map(c=>c.id));notify('Kernel restarted · fresh Java session'); updateStatus();renderCells();
  if(runAfter)await runAll(0,n);
}
async function closeNotebook(n) {
  if (!n) return;
  if (n.running) { notify('Interrupt execution before closing the notebook.',true); return; }
  if (n.dirty || n.savePromise) await saveNotebook(n);
  clearTimeout(n.timer); if(n.kernel)await api(`/kernels/${n.kernel}`,'DELETE');
  window.NotebookControls?.closed(n);
  state.notebooks = state.notebooks.filter(item => item !== n);
  if (state.active === n) { state.active = state.notebooks.at(-1) || null; state.launcher = !state.active; }
  renderWorkspace();
}
function modal(title, body, confirm = 'Continue', initial = null) {
  return new Promise(resolve => {
    const dialog = $('#modal'); $('#modal-title').textContent = title;
    $('#modal-body').innerHTML = `<div>${body}</div>`+(initial !== null ? '<input id="modal-input" aria-label="Notebook name" required maxlength="180">' : '');
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
  const result = await api('/notebooks'+folderQuery(state.folder),'POST', {name:example ? 'Getting started.jnb' : undefined});
  await openNotebook(result.name);
  if (example) {
    const n = active(); n.document.cells = window.gettingStartedCells(cell); markDirty(n); renderCells(); await runAll(); await saveNotebook(n);
  }
  await refreshFiles();
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
  const body=new FormData();body.append('file',file);
  const response=await fetch('api/notebooks/uploads'+folderQuery(state.folder),{method:'POST',body});
  if(!response.ok){let message=`Upload failed (${response.status})`;try{const error=await response.json();message=error.message||error.detail||message;}catch(_){}throw new Error(message);}
  const result=await response.json();
  await refreshFiles();
  if(Array.isArray(result.preview) && result.preview.length){
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
  const n=state.notebooks.find(n=>n.path===notebookPath(folder,name));
  if(n){if(n.running)throw new Error('Interrupt execution before renaming this notebook.');await saveNotebook(n);}
  const result=await api('/notebooks/'+encodeURIComponent(name)+folderQuery(folder),'PATCH',{name:newName});
  if(n){n.name=result.name;n.path=notebookPath(folder,result.name);}
  await refreshFiles();renderWorkspace();notify('Notebook renamed');
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
  const sourcePath=notebookPath(sourceFolder,name),n=state.notebooks.find(open=>open.path===sourcePath);
  if(n){if(n.running)throw new Error('Interrupt execution before moving this notebook.');await saveNotebook(n);}
  const result=await api('/notebooks/move','POST',{path:sourcePath,folder:targetFolder});
  if(n){n.folder=targetFolder;n.path=result.path;n.name=result.name;}
  await refreshFiles();renderWorkspace();notify(`${name} moved to ${targetFolder||'Working directory'}`);
}
async function deleteNotebook(name, folder = state.folder) {
  if (!await modal('Delete notebook?', `<strong>${escapeHtml(name)}</strong> will be permanently deleted from the working directory.`, 'Delete')) return;
  const n = state.notebooks.find(n => n.path === notebookPath(folder,name)); if(n) {if(n.running) throw new Error('Interrupt execution before deleting this notebook.'); await closeNotebook(n);}
  await api('/notebooks/'+encodeURIComponent(name)+folderQuery(folder),'DELETE'); await refreshFiles(); notify('Notebook deleted');
}
function help() {
  const shortcuts=window.NotebookControls?.shortcuts||[];
  $('#modal').classList.add('shortcut-help');
  const groups=['Both','Command','Edit','Application','File browser'];
  return modal('Jupyter-style keyboard controls','<p><strong>Esc</strong> enters command mode; <strong>Enter</strong> enters edit mode. Letter shortcuts operate on cells only in command mode. Repeated keys (D D, I I, 0 0) must be pressed within one second.</p><div class="shortcut-groups">'+groups.map(group=>`<h3>${group} mode</h3>`+shortcuts.filter(s=>s[0]===group).map(([,keys,label])=>`<div class="shortcut-row"><span>${escapeHtml(label)}</span><kbd>${escapeHtml(keys)}</kbd></div>`).join('')).join('')+'</div><div class="help-note">Based on <a href="https://jupyterlab.readthedocs.io/en/stable/user/commands.html" target="_blank" rel="noopener">JupyterLab defaults</a> and classic Notebook aliases. Cell clipboard and undo are local to this browser session. Java Lab uses a native text editor; normal text navigation and selection follow your OS.</div>','Got it').finally(()=>$('#modal').classList.remove('shortcut-help'));
}
const actions = {
  new: () => newNotebook(), example: () => newNotebook(true), refresh:refreshFiles,
  'new-folder':newFolder, upload:()=>$('#upload-input').click(),
  launcher: () => {state.launcher=true;renderWorkspace();},
  sidebar: () => {
    const sidebar = $('#sidebar');
    if (innerWidth <= 760) {sidebar.hidden=false;sidebar.classList.toggle('revealed');}
    else sidebar.hidden = !sidebar.hidden;
  },
  save: () => saveNotebook(active(),true), rename: () => renameNotebook(), close: () => closeNotebook(active()),
  settings: () => window.NotebookSettings?.open(active()),
  run: () => runSelected(), 'run-next': () => runSelected(true), 'run-all':()=>runAll(), restart:()=>restartKernel(), 'restart-run-all':()=>restartKernel(true),
  interrupt: async () => {const n=active();if(n?.running){n.cancelRun=true;await api(`/kernels/${n.kernel}/interrupt`,'POST');notify('Interrupt requested');}},
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
  if(target.closest('#notebook-name')){editNotebookTitle();return;}
  const menuTrigger = target.closest('.menu-trigger');
  $$('.menu').forEach(menu=>{if(menu!==menuTrigger?.parentElement)menu.classList.remove('open');});
  if(menuTrigger){menuTrigger.parentElement.classList.toggle('open');return;}
  $('.file-context')?.remove();
  const formAction=target.closest('[data-form-action]');if(formAction){try{await applyFormAndRun(formAction);}catch(e){notify(e.message,true);}return;}
  const action = target.closest('[data-action]'); if(action){dispatch(action.dataset.action);return;}
  const close = target.closest('[data-close]'); if(close){try{await closeNotebook(state.notebooks.find(n=>n.uid===close.dataset.close));}catch(e){notify(e.message,true);}return;}
  const folder = target.closest('[data-folder]'); if(folder){state.folder=folder.dataset.folder;await refreshFiles();return;}
  if(target.closest('[data-parent-folder]')){state.folder=state.folder.split('/').slice(0,-1).join('/');await refreshFiles();return;}
  const file = target.closest('[data-file]'); if(file){if(file.dataset.fileType!=='notebook')return;try{await openNotebook(file.dataset.file,file.dataset.fileFolder || '');}catch(e){notify(e.message,true);}return;}
  const tab = target.closest('[data-tab]'); if(tab){state.active=state.notebooks.find(n=>n.uid===tab.dataset.tab);state.launcher=false;renderWorkspace();return;}
  const panel = target.closest('[data-panel]'); if(panel){const type=panel.dataset.panel;const sidebar=$('#sidebar');sidebar.hidden=false;if(innerWidth<=760)sidebar.classList.toggle('revealed',state.panel!==type||!sidebar.classList.contains('revealed'));state.panel=type;$('#files-panel').hidden=type!=='files';$('#running-panel').hidden=type!=='running';$('#panel-title').textContent=type==='files'?'FILE BROWSER':'RUNNING KERNELS';$$('[data-panel]').forEach(b=>b.classList.toggle('active',b.dataset.panel===type));return;}
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
$('#cells').addEventListener('error',event=>{if(event.target.matches?.('img.chart-image')){const message=document.createElement('div');message.className='chart-render-error';message.textContent='Chart image could not be displayed. Rerun this cell to regenerate it.';event.target.replaceWith(message);}},true);
$('#cells').addEventListener('scroll',event=>{if(event.target.matches('textarea')){const pre=$('.highlight-layer pre',event.target.parentElement);if(pre)pre.style.transform=`translate(${-event.target.scrollLeft}px,${-event.target.scrollTop}px)`;}},true);
$('#cells').addEventListener('focusin',event=>{const el=event.target.closest('[data-cell]');if(el&&event.target.matches('.cell-editor textarea')){selectCell(Number(el.dataset.cell));window.NotebookControls?.setEditing();}});
$('#cells').addEventListener('dblclick',event=>{const el=event.target.closest('.markdown-render');if(el){const n=active();n.document.cells[n.selected].editing=true;renderCells();selectCell(n.selected,true);}});
$('#cell-type').addEventListener('change',event=>{if(window.NotebookControls)NotebookControls.changeType(event.target.value);});
$('#file-list').addEventListener('contextmenu',event=>{
  const folder=event.target.closest('[data-folder]');
  if(folder){event.preventDefault();$('.file-context')?.remove();const menu=document.createElement('div');menu.className='file-context';menu.style.left=Math.min(event.clientX,innerWidth-170)+'px';menu.style.top=Math.min(event.clientY,innerHeight-100)+'px';menu.innerHTML='<button type="button">Delete empty folder</button>';document.body.append(menu);menu.addEventListener('click',()=>{menu.remove();deleteFolder(folder.dataset.folder).catch(e=>notify(e.message,true));});return;}
  const file=event.target.closest('[data-file]');if(!file||file.dataset.fileType!=='notebook')return;event.preventDefault();$('.file-context')?.remove();
  const menu=document.createElement('div');menu.className='file-context';menu.style.left=Math.min(event.clientX,innerWidth-160)+'px';menu.style.top=Math.min(event.clientY,innerHeight-100)+'px';menu.innerHTML='<button data-context="rename">Rename notebook</button><button data-context="delete">Delete notebook</button>';document.body.append(menu);
  menu.addEventListener('click',event=>{const action=event.target.dataset.context;menu.remove();(action==='rename'?renameNotebook(file.dataset.file,file.dataset.fileFolder):deleteNotebook(file.dataset.file,file.dataset.fileFolder)).catch(e=>notify(e.message,true));});
});
window.addEventListener('beforeunload',event=>{if(state.notebooks.some(n=>n.dirty||n.running)){event.preventDefault();event.returnValue='';}});
window.addEventListener('pagehide',()=>{for(const n of state.notebooks){if(n.kernel)fetch(`api/kernels/${n.kernel}`,{method:'DELETE',keepalive:true}).catch(()=>{});}});
renderWorkspace(); refreshFiles().catch(e=>{$('#connection-state').textContent='Disconnected';notify(e.message,true);});
