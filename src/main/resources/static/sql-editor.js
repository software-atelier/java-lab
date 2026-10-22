window.SqlEditor=(()=>{
  let current=null,renderedResult=null,renderedSchema=null,loadedConfig=null,loadingCredentials=false;
  const input=$('#text-input'),form=$('#sql-configuration'),settings=$('#sql-settings-dialog'),browser=$('#sql-browser');
  function openSettings(){$('#sql-settings-error').hidden=true;if(!settings.open)settings.showModal();$('#sql-maven').focus();}
  function report(message,doc){
    TextEditor.reportError(message,doc);
    if(current===doc)for(const [dialog,id] of [[settings,'#sql-settings-error'],[browser,'#sql-browser-error']])if(dialog.open){$(id).textContent=message;$(id).hidden=false;}
  }
  async function credentials(doc,id){
    loadingCredentials=true;form.querySelectorAll('input,button').forEach(control=>control.disabled=true);
    try{
      const value=await api('/sql/credentials/'+id);
      if(current!==doc||doc.sqlConfig?.credentialId!==id)return;
      $('#sql-user').value=value.user;$('#sql-password').placeholder=value.passwordStored?'Password stored · leave empty to keep':'Password';
    }catch(e){if(current===doc)report(e.message,doc);}
    finally{if(current===doc&&doc.sqlConfig?.credentialId===id){loadingCredentials=false;form.querySelectorAll('input,button').forEach(control=>control.disabled=doc.busy);}}
  }
  function render(doc){
    const changed=current!==doc;current=doc;
    const sql=doc?.format==='sql';$('#sql-tools').hidden=!sql;$('#sql-results').hidden=!sql||!doc.sqlResult;
    $('#text-editor').classList.toggle('sql-mode',sql);$('#text-info').hidden=sql;
    if(changed){settings.close();browser.close();}
    if(!sql){loadedConfig=null;renderedResult=null;return;}
    $('#sql-browse').disabled=doc.busy;$('#sql-vibecode').disabled=doc.busy;
    $('#sql-undo-ai').hidden=!window.Vibecode?.canUndoSql(doc);$('#sql-undo-ai').disabled=doc.busy;
    $('#sql-save').disabled=doc.busy;$('#sql-reload').disabled=doc.busy;
    $('#sql-browser').querySelectorAll('button,input').forEach(control=>control.disabled=doc.busy);
    $('#sql-browser-close').disabled=false;
    if(changed)$('#sql-table-filter').value=doc.sqlFilter||'';
    if(changed||renderedSchema!==doc.sqlSchema)renderSchema(doc);
    $('#sql-run').disabled=doc.busy;$('#sql-configure').disabled=doc.busy;
    $('#sql-connection-info').textContent=doc.sqlConfig?'Connection configured':'No connection';
    $('#sql-connection-info').title=doc.sqlConfig?.maven||'Open settings to configure JDBC';
    const config=JSON.stringify(doc.sqlConfig);
    if(changed||loadedConfig!==config){
      renderSchema(doc);
      loadedConfig=config;loadingCredentials=false;
      $('#sql-maven').value=doc.sqlConfig?.maven||'';$('#sql-url').value=doc.sqlConfig?.url||'';
      $('#sql-user').value='';$('#sql-password').value='';$('#sql-clear-password').checked=false;
      if(doc.sqlConfig?.credentialId)credentials(doc,doc.sqlConfig.credentialId);
    }
    form.querySelectorAll('input,button').forEach(control=>control.disabled=doc.busy||loadingCredentials);
    if(changed||renderedResult!==doc.sqlResult){renderedResult=doc.sqlResult;renderResults(doc);}
    $('#sql-results').querySelectorAll('button').forEach(button=>button.disabled=doc.busy||!!button.sqlPageDisabled);
  }
  function configured(doc){
    if(doc.sqlConfig)return true;
    openSettings();return false;
  }
  function renderSchema(doc){
    const host=$('#sql-tables');host.replaceChildren();const schema=doc.sqlSchema;renderedSchema=schema;
    $('#sql-schema-info').textContent=schema?`${schema.product} · ${schema.tables.length} tables/views${schema.truncated?' · limited to 500':''}`:'Load the database schema to browse tables';
    if(!schema)return;
    schema.tables.forEach((table,index)=>{
      const name=[table.catalog,table.schema,table.name].filter(Boolean).join('.');if(doc.sqlFilter&&!name.toLowerCase().includes(doc.sqlFilter.toLowerCase()))return;
      const detail=document.createElement('details'),summary=document.createElement('summary'),list=document.createElement('ul');summary.textContent=name+' · '+table.type;detail.append(summary,list);host.append(detail);
      const paint=columns=>{list.replaceChildren();columns.forEach(column=>{const item=document.createElement('li');item.textContent=`${column.name} · ${column.type}${column.size?' ('+column.size+')':''}${column.nullable?' · nullable':' · NOT NULL'}`;list.append(item);});};
      if(doc.sqlColumns?.[index])paint(doc.sqlColumns[index]);
      detail.ontoggle=async()=>{
        if(!detail.open||doc.sqlColumns?.[index]||detail.loading)return;detail.loading=true;list.textContent='Loading columns…';
        try{const columns=await api('/sql/columns','POST',{path:doc.path,config:doc.sqlConfig,table});if(doc.sqlSchema!==schema)return;(doc.sqlColumns??={})[index]=columns;list.textContent='';paint(columns);}
        catch(e){list.textContent=e.message;}finally{detail.loading=false;}
      };
    });
  }
  async function loadSchema(){
    const doc=current;if(!doc||doc.busy||!configured(doc))return;doc.error=null;TextEditor.setBusy(doc,true);
    $('#sql-browser-error').hidden=true;
    try{doc.sqlSchema=await api('/sql/schema','POST',{path:doc.path,config:doc.sqlConfig});doc.sqlColumns={};if(current===doc)renderSchema(doc);}
    catch(e){report(e.message,doc);}finally{TextEditor.setBusy(doc,false);}
  }
  function renderResults(doc){
    const host=$('#sql-results');host.replaceChildren();if(!doc.sqlResult)return;
    const result=doc.sqlResult;
    const summary=document.createElement('p');summary.className='sql-result-summary';summary.textContent=result.updateCounts.map(count=>`${count} affected rows`).join(' · ')||(!result.tables.length?'SQL executed successfully':'');if(summary.textContent)host.append(summary);
    result.tables.forEach((table,index)=>{
      const section=document.createElement('section');section.className='sql-result';host.append(section);
      const toolbar=document.createElement('div');toolbar.className='sql-result-toolbar';
      const info=document.createElement('span');info.textContent=`Result ${index+1}: ${table.rows.length} rows${table.truncated?' · limited to 10,000 rows (export uses these rows)':''}`;
      const button=document.createElement('button');button.type='button';button.className='editor-icon-button';button.title='Export CSV to file browser';button.setAttribute('aria-label',`Export result ${index+1} as CSV to file browser`);button.innerHTML='<svg aria-hidden="true"><use href="#i-download"/></svg>';button.onclick=()=>exportCsv(doc,index);
      toolbar.append(info,button);section.append(toolbar);
      const grid=document.createElement('table');
      const head=grid.createTHead().insertRow();table.columns.forEach(value=>{const cell=document.createElement('th');cell.textContent=value;head.append(cell);});
      const body=grid.createTBody();const pageSize=200;let page=0;
      const pager=document.createElement('div');pager.className='sql-result-pager';const previous=document.createElement('button'),next=document.createElement('button'),label=document.createElement('span');
      previous.type=next.type='button';previous.className=next.className='secondary';previous.textContent='Previous';next.textContent='Next';
      function paint(){
        body.replaceChildren();table.rows.slice(page*pageSize,(page+1)*pageSize).forEach(row=>{const tr=body.insertRow();row.forEach(value=>{const td=tr.insertCell();td.textContent=value===null?'NULL':value;if(value===null)td.className='sql-null';});});
        label.textContent=`Page ${page+1} / ${Math.max(1,Math.ceil(table.rows.length/pageSize))}`;previous.disabled=previous.sqlPageDisabled=page===0;next.disabled=next.sqlPageDisabled=(page+1)*pageSize>=table.rows.length;
      }
      previous.onclick=()=>{if(page>0){page--;paint();}};next.onclick=()=>{if((page+1)*pageSize<table.rows.length){page++;paint();}};
      pager.append(previous,label,next);paint();section.append(grid);if(table.rows.length>pageSize)section.append(pager);
    });
  }
  async function run(){
    const doc=current;if(!doc||doc.busy)return;
    if(!configured(doc))return;
    const sql=input.selectionStart===input.selectionEnd?doc.content:doc.content.slice(input.selectionStart,input.selectionEnd);
    doc.error=null;TextEditor.setBusy(doc,true);
    try{doc.sqlResult=await api('/sql/execute','POST',{path:doc.path,sql,config:doc.sqlConfig});}
    catch(e){TextEditor.reportError(e.message,doc);}
    finally{TextEditor.setBusy(doc,false);}
  }
  async function exportCsv(doc,index){
    if(doc.busy)return;TextEditor.setBusy(doc,true);
    try{const file=await api('/sql/export','POST',{resultId:doc.sqlResult.id,tableIndex:index,path:doc.path});await refreshFiles();notify('CSV exported: '+file.path);}
    catch(e){TextEditor.reportError(e.message,doc);}
    finally{TextEditor.setBusy(doc,false);}
  }
  $('#sql-run').onclick=run;
  $('#sql-browse').onclick=async()=>{const doc=current;if(!doc||doc.busy||!configured(doc))return;browser.showModal();if(!doc.sqlSchema)await loadSchema();};
  function vibe(){const doc=current;if(doc&&!doc.busy&&configured(doc))window.Vibecode?.openSql(doc,input);}
  $('#sql-vibecode').onclick=vibe;$('#sql-undo-ai').onclick=()=>window.Vibecode?.undoSql(current);
  $('#sql-table-filter').oninput=()=>{if(current){current.sqlFilter=$('#sql-table-filter').value;renderSchema(current);}};
  $('#sql-schema-refresh').onclick=loadSchema;
  $('#sql-configure').onclick=openSettings;$('#sql-settings-close').onclick=$('#sql-settings-cancel').onclick=()=>settings.close();$('#sql-browser-close').onclick=()=>browser.close();
  $('#sql-save').onclick=()=>TextEditor.save(current);$('#sql-reload').onclick=()=>$('#text-reload').onclick();
  $('#sql-find').onclick=()=>TextEditor.toggleSearch();
  form.onsubmit=async event=>{
    event.preventDefault();const doc=current;if(!doc||doc.busy||loadingCredentials)return;
    const config={maven:$('#sql-maven').value.trim(),url:$('#sql-url').value.trim(),credentialId:doc.sqlConfig?.credentialId||crypto.randomUUID()};
    const auth={user:$('#sql-user').value,password:$('#sql-clear-password').checked?'':$('#sql-password').value||null};
    // Validate connection metadata before persisting credentials or changing the document.
    if(!/^[^\s:]+:[^\s:]+:[^\s:]+$/.test(config.maven)||!config.url.startsWith('jdbc:')||/(?:password|passwd|pwd|user|username)\s*=|\/\/[^/]*@|^jdbc:oracle:(?:thin|oci):[^@]+@/i.test(config.url)){
      report('Provide Maven coordinates and a JDBC URL without embedded user/password.',doc);return;
    }
    doc.error=null;$('#sql-settings-error').hidden=true;TextEditor.setBusy(doc,true);let applied=false;
    try{
      await api('/sql/credentials/'+config.credentialId,'PUT',auth);
      TextEditor.configureSql(doc,config);if(current===doc){$('#sql-password').value='';$('#sql-clear-password').checked=false;}applied=true;
    }catch(e){report(e.message,doc);}
    finally{TextEditor.setBusy(doc,false);}
    if(applied){if(await TextEditor.save(doc))settings.close();else if(doc.error)report(doc.error,doc);}
  };
  return {render,run,vibe};
})();
