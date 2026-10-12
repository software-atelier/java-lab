const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const NotebookSettings=require('../../main/resources/static/notebook-settings.js');

test('Vibecode sends notebook definitions without any configured values when cell context is off',async()=>{
  const cell={cell_type:'code',source:['String region = labVar("REGION");'],outputs:[]};
  const notebook={name:'private.jnb',document:{metadata:{java_lab:{
    variables:[
      {name:'API_TOKEN',secret:true,env:'API_TOKEN_ENV',defaultValue:'FORGED_SECRET_DEFAULT',localValue:'LOCAL_SECRET',resolvedValue:'ENV_SECRET'},
      {name:'REGION',secret:false,defaultValue:'PRIVATE_DEFAULT',value:'LOCAL_REGION'}
    ],dependencies:['org.example:client:1.2.3']
  }},cells:[cell]},selected:0,running:false};
  const elements=new Map();
  function element(selector) {
    if(!elements.has(selector))elements.set(selector,{
      value:'',checked:false,hidden:false,disabled:false,textContent:'',innerHTML:'',
      selectionStart:0,selectionEnd:0,handlers:{},
      addEventListener(name,callback){this.handlers[name]=callback;},
      focus(){},showModal(){},close(){}
    });
    return elements.get(selector);
  }
  element('#vibe-notebook-context').checked=false;
  element('#vibe-prompt').value='Use the configured client';
  let sent;
  const context={
    window:{NotebookSettings,JavaEditor:{dismiss(){}}},
    $:element,active:()=>notebook,source:c=>c.source.join(''),
    LabPresentation:{highlightJava:source=>source},
    api:async (endpoint,method,body)=>{
      if(endpoint==='/ai/config')return {configured:true,provider:'test',model:'test-model'};
      if(endpoint==='/ai/generate'){sent=body;return {code:'String region = labVar("REGION");'};}
      throw new Error('Unexpected API request: '+endpoint);
    },
    state:{notebooks:[notebook]},markDirty(){},renderCells(){},selectCell(){},notify(){},
    AbortController
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/vibecode.js'),'utf8'),context);
  await context.window.Vibecode.open();
  element('#vibe-prompt').value='Use the configured client';
  await element('#vibe-form').handlers.submit({preventDefault(){}});

  assert.equal(sent.prompt,'Use the configured client');
  assert.equal(sent.context.length,1);
  const settings=sent.context[0];
  assert.equal(settings.kind,'notebook-settings');
  assert.deepEqual(JSON.parse(settings.content),{
    variables:[
      {name:'API_TOKEN',secret:true,env:'API_TOKEN_ENV'},
      {name:'REGION',secret:false}
    ],dependencies:['org.example:client:1.2.3']
  });
  for(const value of ['FORGED_SECRET_DEFAULT','LOCAL_SECRET','ENV_SECRET','PRIVATE_DEFAULT','LOCAL_REGION'])
    assert.equal(JSON.stringify(sent).includes(value),false);
});
