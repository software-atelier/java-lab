/* Notebook configuration is shareable; runtime values are deliberately separate. */
(function(root, factory) {
  const helpers=factory();
  if(typeof module==='object'&&module.exports)module.exports=helpers;
  else root.NotebookSettings=helpers;
})(globalThis, () => {
  const namePattern=/^[A-Za-z_][A-Za-z0-9_]*$/;
  const gavPattern=/^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+$/;
  function validate(variables, dependencies) {
    if(variables.length>64)throw new Error('At most 64 variables are allowed.');
    if(dependencies.length>20)throw new Error('At most 20 Maven libraries are allowed.');
    const names=new Set();
    for(const variable of variables) {
      if(variable.name.length>64||!namePattern.test(variable.name))throw new Error(`Invalid variable name: ${variable.name || '(empty)'}. Use Java-style identifiers (max 64 characters).`);
      if(names.has(variable.name))throw new Error(`Duplicate variable: ${variable.name}`);
      names.add(variable.name);
      if(variable.env && (variable.env.length>64||!namePattern.test(variable.env)))throw new Error(`Invalid environment variable name for ${variable.name}.`);
      if(variable.defaultValue?.length>4096)throw new Error(`Default for ${variable.name} is too long.`);
      if(variable.secret && Object.hasOwn(variable,'defaultValue'))throw new Error(`Secret ${variable.name} cannot have a document default.`);
    }
    if(new Set(dependencies).size!==dependencies.length)throw new Error('Duplicate Maven library.');
    for(const dependency of dependencies)if(dependency.split(':').some(part=>part.length>100)||!gavPattern.test(dependency))throw new Error(`Invalid Maven coordinate: ${dependency || '(empty)'}. Use groupId:artifactId:version.`);
  }
  function definitions(variables) {
    return variables.map(variable=>{
      const copy={name:variable.name.trim(),secret:!!variable.secret};
      if(variable.env?.trim())copy.env=variable.env.trim();
      if(!copy.secret && variable.defaultValue !== undefined && variable.defaultValue !== '')copy.defaultValue=variable.defaultValue;
      return copy;
    });
  }
  function installedSettings(document) {
    const settings=document.metadata?.java_lab||{};
    return {variables:Array.isArray(settings.variables)?settings.variables:[],dependencies:Array.isArray(settings.dependencies)?settings.dependencies:[]};
  }
  function aiContext(document) {
    const settings=installedSettings(document);
    const variables=settings.variables.filter(variable=>variable && typeof variable.name==='string')
      .map(variable=>({name:variable.name,secret:variable.secret===true,
        ...(typeof variable.env==='string' && variable.env ? {env:variable.env} : {})}));
    const dependencies=settings.dependencies.filter(dependency=>typeof dependency==='string');
    if(!variables.length&&!dependencies.length)return null;
    return {kind:'notebook-settings',title:'Notebook variables and Maven libraries',
      content:JSON.stringify({variables,dependencies}),metadata:{}};
  }
  if(typeof document==='undefined')return {validate,definitions,installedSettings,aiContext};

  const dialog=document.querySelector('#settings-dialog');
  const form=document.querySelector('#settings-form');
  const variableList=document.querySelector('#settings-variables');
  const dependencyList=document.querySelector('#settings-dependencies');
  const error=document.querySelector('#settings-error');
  let notebook=null, status=new Map(), initialPending=false, applying=false;
  const html=value=>escapeHtml(value);
  const path=n=>'/notebooks/'+encodeURIComponent(n.name)+'/values'+folderQuery(n.folder);
  function statusLabel(name) {
    return {override:'Local value set',environment:'Environment value set',default:'Document default',unset:'Not set'}[status.get(name)]||'Not set';
  }
  function appendVariable(variable={name:'',secret:false}) {
    const row=document.createElement('div');row.className='settings-variable';
    row.innerHTML=`<div class="settings-variable-top"><label>Name<input class="settings-name" autocomplete="off" placeholder="API_TOKEN" value="${html(variable.name||'')}"></label><label class="settings-secret"><input class="settings-is-secret" type="checkbox" ${variable.secret?'checked':''}> Secret</label><button type="button" class="icon-button settings-remove" title="Remove variable" aria-label="Remove variable">×</button></div><div class="settings-variable-fields"><label>Environment variable<input class="settings-env" autocomplete="off" placeholder="API_TOKEN" value="${html(variable.env||'')}"></label><label class="settings-default-wrap">Shareable default<input class="settings-default" autocomplete="off" placeholder="Optional" value="${variable.secret?'':html(variable.defaultValue||'')}"></label></div><div class="settings-variable-fields"><label>Local value (leave blank to keep current)<input class="settings-override" type="${variable.secret?'password':'text'}" autocomplete="new-password" placeholder="Not stored in notebook"></label><label class="settings-clear"><input type="checkbox" class="settings-clear-value"> Clear local value</label></div><div class="settings-value-status" aria-live="polite">${html(statusLabel(variable.name))}</div>`;
    variableList.append(row);
    updateSecret(row);
    return row;
  }
  function updateSecret(row) {
    const secret=row.querySelector('.settings-is-secret').checked;
    const defaultInput=row.querySelector('.settings-default');
    if(secret)defaultInput.value='';
    row.querySelector('.settings-default-wrap').hidden=secret;
    row.querySelector('.settings-override').type=secret?'password':'text';
  }
  function appendDependency(value='') {
    const row=document.createElement('div');row.className='settings-dependency';
    row.innerHTML=`<input class="settings-gav" aria-label="Maven coordinate" placeholder="groupId:artifactId:version" value="${html(value)}"><button type="button" class="icon-button settings-remove" title="Remove library" aria-label="Remove library">×</button>`;
    dependencyList.append(row);
  }
  function reset() {
    variableList.replaceChildren();dependencyList.replaceChildren();error.hidden=true;error.textContent='';status=new Map();notebook=null;applying=false;
  }
  async function open(n) {
    if(!n)return;
    if(n.running)throw new Error('Interrupt execution before editing notebook settings.');
    reset();notebook=n;initialPending=!!n.configPending;
    const settings=installedSettings(n.document);
    try {
      const result=await api(path(n));
      status=new Map((Array.isArray(result)?result:result.values||[]).map(value=>[value.name,value.source]));
    }catch(e){error.textContent='Could not load local value status: '+e.message;error.hidden=false;}
    for(const variable of settings.variables)appendVariable(variable);
    for(const dependency of settings.dependencies)appendDependency(dependency);
    dialog.showModal();dialog.querySelector('#settings-add-variable').focus();
  }
  function close() {
    if(applying)return;
    if(notebook){notebook.configPending=initialPending;updateStatus();}
    dialog.close();reset();
  }
  function changed() {
    if(!notebook)return;
    notebook.configPending=true;updateStatus();
  }
  function collect() {
    const rows=[...variableList.querySelectorAll('.settings-variable')];
    const variables=definitions(rows.map(row=>({
      name:row.querySelector('.settings-name').value,
      secret:row.querySelector('.settings-is-secret').checked,
      env:row.querySelector('.settings-env').value,
      defaultValue:row.querySelector('.settings-default').value
    })));
    const dependencies=[...dependencyList.querySelectorAll('.settings-gav')].map(input=>input.value.trim());
    validate(variables,dependencies);
    const values={},clear=[];
    rows.forEach((row,index)=>{
      const name=variables[index].name,override=row.querySelector('.settings-override').value;
      if(override.length>8192)throw new Error(`Local value for ${name} is too long.`);
      if(row.querySelector('.settings-clear-value').checked)clear.push(name);
      else if(override!=='')values[name]=override;
    });
    return {variables,dependencies,values,clear};
  }
  async function apply(event) {
    event.preventDefault();if(!notebook||applying)return;
    const n=notebook;
    if(n.running){error.textContent='Interrupt execution before applying settings.';error.hidden=false;return;}
    applying=true;error.hidden=true;document.querySelector('#settings-apply').disabled=true;
    try {
      const next=collect();
      const previous=installedSettings(n.document);
      const metadataChanged=JSON.stringify(previous)!==JSON.stringify({variables:next.variables,dependencies:next.dependencies});
      const valuesChanged=Object.keys(next.values).length>0||next.clear.length>0;
      if(!metadataChanged&&!valuesChanged&&!n.configPending){dialog.close();reset();return;}
      n.configPending=true;initialPending=true;updateStatus();
      if(metadataChanged){
        n.document.metadata||={};n.document.metadata.java_lab={...(n.document.metadata.java_lab||{}),variables:next.variables,dependencies:next.dependencies};
        markDirty(n);
      }
      if(n.dirty||n.savePromise)await saveNotebook(n);
      if(valuesChanged)await api(path(n),'PUT',{values:next.values,clear:next.clear});
      const replacement=await api('/kernels','POST',{notebook:n.path});
      const previousKernel=n.kernel;n.kernel=replacement.id;n.configPending=false;
      dialog.close();reset();updateStatus();
      if(previousKernel)api(`/kernels/${previousKernel}`,'DELETE').catch(e=>notify('Old kernel cleanup failed: '+e.message,true));
      notify('Settings applied · fresh Java session');
    }catch(e){error.textContent=e.message;error.hidden=false;n.configPending=true;updateStatus();}
    finally{applying=false;document.querySelector('#settings-apply').disabled=false;}
  }
  form.addEventListener('submit',apply);
  form.addEventListener('input',event=>{
    const row=event.target.closest('.settings-variable');
    if(event.target.matches('.settings-is-secret'))updateSecret(row);
    if(row && event.target.matches('.settings-name'))row.querySelector('.settings-value-status').textContent=statusLabel(event.target.value.trim());
    changed();
  });
  form.addEventListener('change',changed);
  form.addEventListener('click',event=>{
    if(event.target.closest('.settings-remove')){event.target.closest('.settings-variable,.settings-dependency').remove();changed();}
  });
  dialog.querySelector('#settings-add-variable').onclick=()=>{appendVariable();changed();};
  dialog.querySelector('#settings-add-dependency').onclick=()=>{appendDependency();changed();};
  dialog.querySelector('#settings-cancel').onclick=close;
  dialog.querySelector('#settings-close').onclick=close;
  dialog.addEventListener('cancel',event=>{event.preventDefault();close();});
  return {open,validate,definitions,installedSettings,aiContext};
});
