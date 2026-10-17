/* CSV documents share workspace tabs, while their table state stays independent of notebook kernels. */
window.CsvEditor=(()=>{
  const pane=$('#csv-editor'),PAGE_SIZE=50,opening=new Map();
  let current=null;
  const chrome=()=>{renderTabs();updateStatus();};
  const error=(message,doc=current)=>{if(doc)doc.error=message;if(doc===current)renderError();chrome();};
  function renderError(){$('#csv-error').textContent=current?.error||'';$('#csv-error').hidden=!current?.error;}
  const width=()=>Math.max(1,...current.table.rows.map(row=>row.length));
  const cells=()=>current.table.rows.reduce((n,row)=>n+row.length,0);
  const endpoint=(path,delimiter)=>'/csv?path='+encodeURIComponent(path)+(delimiter===undefined?'':'&delimiter='+encodeURIComponent(delimiter));
  function setBusy(doc,value){doc.busy=value;if(doc===current){pane.querySelectorAll('button,select,textarea').forEach(e=>e.disabled=value);status();}chrome();}
  function status(){
    if(!current)return;
    $('#csv-state').textContent=current.busy?'Working…':current.dirty?'Unsaved changes':'Saved';
    $('#csv-save').disabled=current.busy||!current.dirty;
  }
  function changed(){current.dirty=true;current.error=null;renderError();status();chrome();}
  function rememberCursor(input){
    if(current&&input?.dataset?.csvRow!==undefined)current.cursor={row:input.dataset.csvRow,col:input.dataset.csvCol,start:input.selectionStart,end:input.selectionEnd};
  }
  function rememberPosition(){
    if(!current)return;
    rememberCursor(globalThis.document?.activeElement);
    current.scroll={top:$('#csv-grid').scrollTop,left:$('#csv-grid').scrollLeft};
  }
  function restorePosition(){
    if(current.cursor){
      const c=current.cursor,input=$(`#csv-grid [data-csv-row="${c.row}"][data-csv-col="${c.col}"]`);
      if(input){input.focus({preventScroll:true});if(Number.isInteger(c.start))input.setSelectionRange(Math.min(c.start,input.value.length),Math.min(c.end,input.value.length));}
    }else $('#csv-grid').focus({preventScroll:true});
    $('#csv-grid').scrollTop=current.scroll?.top||0;$('#csv-grid').scrollLeft=current.scroll?.left||0;
  }
  function activate(doc){
    if(current===doc){if(doc){$('#csv-title').textContent=doc.path;renderError();status();}return;}
    rememberPosition();current=doc;
    if(doc){render();restorePosition();}
  }
  function columnName(index){let name='';for(index++;index;index=Math.floor((index-1)/26))name=String.fromCharCode(65+(index-1)%26)+name;return name;}
  function render(){
    const rows=current.table.rows,columns=width(),pages=Math.max(1,Math.ceil(rows.length/PAGE_SIZE));
    current.page=Math.min(current.page,pages-1);
    const start=current.page*PAGE_SIZE;
    $('#csv-title').textContent=current.path;
    $('#csv-delimiter').value=current.table.delimiter;
    $('#csv-info').textContent=`${rows.length} records · ${rows.length?columns:0} columns · UTF-8${current.table.bom?' with BOM':''} · ${current.table.recordSeparator==='\r\n'?'CRLF':current.table.recordSeparator==='\r'?'CR':'LF'} line endings`;
    $('#csv-grid').innerHTML=rows.length?`<table><thead><tr><th>Record</th>${Array.from({length:columns},(_,col)=>`<th>${columnName(col)} <button type="button" data-csv-remove-column="${col}" aria-label="Delete column ${columnName(col)}" title="Delete column">×</button></th>`).join('')}</tr></thead><tbody>${rows.slice(start,start+PAGE_SIZE).map((row,i)=>`<tr><th>${start+i+1}<button type="button" data-csv-remove-row="${start+i}" aria-label="Delete record ${start+i+1}" title="Delete record">×</button></th>${Array.from({length:columns},(_,col)=>`<td><textarea rows="1" maxlength="100000" data-csv-row="${start+i}" data-csv-col="${col}" aria-label="Record ${start+i+1}, column ${columnName(col)}" spellcheck="false">${escapeHtml(row[col]??'')}</textarea></td>`).join('')}</tr>`).join('')}</tbody></table>`:'<div class="csv-empty">Empty CSV. Add a record to start editing.</div>';
    $('#csv-page').textContent=`Page ${current.page+1} of ${pages} · ${rows.length?'records '+(start+1)+'–'+Math.min(start+PAGE_SIZE,rows.length):'no records'}`;
    pane.querySelectorAll('button,select,textarea').forEach(e=>e.disabled=current.busy);
    pane.querySelector('[data-csv-action="previous"]').disabled=current.busy||current.page===0;
    pane.querySelector('[data-csv-action="next"]').disabled=current.busy||current.page===pages-1;
    renderError();status();
  }
  async function open(name,folder='',select=true){
    const path=notebookPath(folder,name);
    let doc=openDocuments().find(item=>item.type==='csv'&&item.path===path);
    if(!doc){
      if(!opening.has(path))opening.set(path,(async()=>{
        const loaded=await api(endpoint(path));
        const created={...loaded,type:'csv',uid:crypto.randomUUID(),name,folder,dirty:false,page:0,busy:false,error:null};
        openDocuments().push(created);return created;
      })());
      try{doc=await opening.get(path);}finally{opening.delete(path);}
    }
    if(select){window.JavaEditor?.dismiss();state.active=doc;state.launcher=false;renderWorkspace();}else renderTabs();return doc;
  }
  async function discardAllowed(doc){return !doc.dirty||await modal('Discard CSV edits?',`Unsaved changes to <strong>${escapeHtml(doc.path)}</strong> will be discarded.`,'Discard');}
  async function close(doc=current){
    if(!doc)return;
    if(doc.savePromise&&!await doc.savePromise)return;
    if(doc.busy){notify('Wait for the CSV operation to finish before closing.',true);return;}
    if(!await discardAllowed(doc))return;
    window.NotebookControls?.closed(doc);
    state.tabs=openDocuments().filter(item=>item!==doc);
    if(state.active===doc){state.active=openDocuments().at(-1)||null;state.launcher=!state.active;}
    renderWorkspace();
  }
  async function reload(delimiter=$('#csv-delimiter').value){
    const doc=current;if(!doc||doc.busy)return;
    if(!await discardAllowed(doc)){if(current===doc)$('#csv-delimiter').value=doc.table.delimiter;return;}
    doc.error=null;setBusy(doc,true);if(current===doc)renderError();
    try{const loaded=await api(endpoint(doc.path,delimiter));Object.assign(doc,loaded,{dirty:false,page:0,cursor:null,scroll:null});}
    catch(e){error(e.message,doc);}
    finally{setBusy(doc,false);if(current===doc)render();}
  }
  async function save(doc=current){
    if(!doc)return false;
    if(doc.savePromise)return doc.savePromise;
    if(doc.busy)return false;
    if(!doc.dirty)return true;
    if(current===doc)rememberPosition();doc.error=null;setBusy(doc,true);if(current===doc)renderError();
    doc.savePromise=(async()=>{
      try{
        const result=await api(endpoint(doc.path),'PUT',{table:structuredClone(doc.table),revision:doc.revision});
        Object.assign(doc,result,{dirty:false});
        await refreshFiles();notify('CSV saved');return true;
      }catch(e){error(e.message,doc);if(current!==doc)notify('CSV save failed: '+e.message,true);return false;}
      finally{doc.savePromise=null;setBusy(doc,false);if(current===doc)render();}
    })();
    return doc.savePromise;
  }
  function action(name){
    if(!current||current.busy)return;
    const rows=current.table.rows,columns=width();
    if(name==='add-row'){
      if(rows.length>=10000||cells()+columns>100000){error('CSV limit: 10,000 records and 100,000 cells');return;}
      rows.push(Array(columns).fill(''));current.page=Math.floor((rows.length-1)/PAGE_SIZE);changed();
    }else if(name==='add-column'){
      if(columns>=100 || Math.max(1,rows.length)*(columns+1)>100000){error('CSV limit: 100 columns and 100,000 cells');return;}
      if(!rows.length)rows.push(['']);
      else for(const row of rows){while(row.length<columns)row.push('');row.push('');}
      changed();
    }else if(name==='previous')current.page=Math.max(0,current.page-1);
    else if(name==='next')current.page=Math.min(Math.max(0,Math.ceil(rows.length/PAGE_SIZE)-1),current.page+1);
    render();
  }
  $('#csv-grid').addEventListener('input',event=>{
    const input=event.target;if(!current||current.busy||input.dataset.csvRow===undefined)return;
    const row=current.table.rows[Number(input.dataset.csvRow)],col=Number(input.dataset.csvCol);
    if(!row||!Number.isInteger(col)||col<0||col>=100)return;
    if(cells()+Math.max(0,col+1-row.length)>100000){error('CSV limit: 100,000 cells');input.value=row[col]??'';return;}
    while(row.length<=col)row.push('');row[col]=input.value;changed();
  });
  pane.addEventListener('click',event=>{
    if(!current||current.busy)return;
    const row=event.target.closest('[data-csv-remove-row]'),col=event.target.closest('[data-csv-remove-column]'),button=event.target.closest('[data-csv-action]');
    if(row){current.table.rows.splice(Number(row.dataset.csvRemoveRow),1);changed();render();}
    else if(col){
      if(width()===1){error('Keep at least one column. Delete all records to empty the file.');return;}
      for(const record of current.table.rows){record.splice(Number(col.dataset.csvRemoveColumn),1);if(!record.length)record.push('');}changed();render();
    }else if(button){if(button.dataset.csvAction==='reload')reload();else action(button.dataset.csvAction);}
  });
  $('#csv-delimiter').addEventListener('change',()=>reload());
  $('#csv-save').onclick=()=>save();
  pane.addEventListener('focusout',event=>rememberCursor(event.target));
  pane.addEventListener('keydown',event=>{if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='s'){event.preventDefault();event.stopPropagation();save();}});
  return {open,activate,save,close,refresh:doc=>{if(current===doc)render();}};
})();
