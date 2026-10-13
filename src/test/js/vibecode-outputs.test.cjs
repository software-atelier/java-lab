const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');

async function requestFor(cells,checked=true) {
  const notebook={name:'schema.jnb',document:{metadata:{},cells},selected:cells.length-1,running:false};
  const elements=new Map();
  function element(selector) {
    if(!elements.has(selector))elements.set(selector,{
      value:'',checked:false,hidden:false,disabled:false,textContent:'',innerHTML:'',
      selectionStart:0,selectionEnd:0,handlers:{},
      addEventListener(name,callback){this.handlers[name]=callback;},focus(){},showModal(){},close(){}
    });
    return elements.get(selector);
  }
  element('#vibe-notebook-context').checked=checked;
  let sent;
  const context={window:{JavaEditor:{dismiss(){}}},$:element,active:()=>notebook,
    source:c=>c.source.join(''),LabPresentation:{highlightJava:source=>source},
    api:async(endpoint,method,body)=>{
      if(endpoint==='/ai/config')return {configured:true};
      if(endpoint==='/ai/generate'){sent=body;return {code:'// edited'};}
      throw new Error(endpoint);
    },state:{notebooks:[notebook]},markDirty(){},renderCells(){},selectCell(){},notify(){},AbortController};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/vibecode.js'),'utf8'),context);
  await context.window.Vibecode.open();
  element('#vibe-prompt').value='Use previous DB schema';
  await element('#vibe-form').handlers.submit({preventDefault(){}});
  return {sent,error:element('#vibe-error').textContent};
}

test('preceding textual and Grid outputs reach AI in notebook order, without chart payloads',async()=>{
  const cells=[
    {cell_type:'code',source:['System.out.println("schema");'],execution_count:1,outputs:[
      {output_type:'stream',name:'stdout',text:['TABLE users\n','id INTEGER\n']},
      {output_type:'display_data',data:{'application/vnd.gridjs+json':{title:'columns',columns:[{name:'name'},{name:'type'}],data:[['id','INTEGER'],['email','TEXT']]},'text/plain':'columns'}},
      {output_type:'display_data',data:{'image/png':'SECRET_IMAGE_BINARY','text/plain':'image'}},
      {output_type:'display_data',data:{'application/vnd.apache.echarts+json':{huge:'SECRET_CHART_OPTION'},'text/plain':'chart'}}
    ]},
    {cell_type:'code',source:['badSql();'],execution_count:2,outputs:[{output_type:'error',ename:'SQLException',evalue:'unknown column',traceback:['SQLException: unknown column']}]},
    {cell_type:'code',source:['// current'],outputs:[]}
  ];
  const {sent}=await requestFor(cells);
  const notebook=sent.context.find(item=>item.kind==='notebook');
  const prior=JSON.parse(notebook.content);
  assert.equal(prior.length,2);
  assert.deepEqual(prior[0].outputs.map(o=>o.type),['stdout','grid']);
  assert.match(prior[0].outputs[0].text,/TABLE users/);
  assert.match(prior[0].outputs[1].text,/email\tTEXT/);
  assert.match(prior[1].outputs[0].text,/unknown column/);
  assert.equal(JSON.stringify(sent).includes('SECRET_IMAGE_BINARY'),false);
  assert.equal(JSON.stringify(sent).includes('SECRET_CHART_OPTION'),false);
});

test('outputs are omitted when preceding-cell context is disabled',async()=>{
  const {sent}=await requestFor([
    {cell_type:'code',source:['x();'],outputs:[{output_type:'stream',text:'SCHEMA'}]},
    {cell_type:'code',source:['// current'],outputs:[]}
  ],false);
  assert.equal(sent.context.some(item=>item.kind==='notebook'),false);
  assert.equal(JSON.stringify(sent).includes('SCHEMA'),false);
});

test('large outputs are bounded newest-first with explicit truncation metadata',async()=>{
  const cells=[];
  for(let i=0;i<8;i++)cells.push({cell_type:'code',source:[`// ${i}`],outputs:[{output_type:'stream',text:String(i).repeat(20000)}]});
  cells.push({cell_type:'code',source:['// current'],outputs:[]});
  const {sent}=await requestFor(cells);
  const notebook=sent.context.find(item=>item.kind==='notebook');
  const prior=JSON.parse(notebook.content);
  assert.equal(notebook.metadata.outputBudgetChars,64000);
  assert.ok(notebook.metadata.outputsOmitted>0);
  assert.ok(notebook.metadata.outputsTruncated>0);
  assert.equal(prior[7].outputs[0].text.length,12000);
  assert.equal(prior[7].outputs[0].truncated,true);
  assert.ok(JSON.stringify(sent.context).length<=128000);
});

test('oversized full context fails visibly before AI request',async()=>{
  const {sent,error}=await requestFor([
    {cell_type:'code',source:['x'.repeat(130000)],outputs:[]},
    {cell_type:'code',source:['// current'],outputs:[]}
  ]);
  assert.equal(sent,undefined);
  assert.match(error,/overall limit: 128,000/);
});

test('current-cell diagnostics send bounded error text without arbitrary payload fields',async()=>{
  const {sent}=await requestFor([{cell_type:'code',source:['// current'],outputs:[
    {output_type:'error',ename:'SQLException',evalue:'bad query',traceback:['SQLException: '+ 'x'.repeat(20000)],secret:'DO_NOT_SEND',data:{'image/png':'DO_NOT_SEND_IMAGE'}},
    {output_type:'error',ename:'SQLException',evalue:'missing table',traceback:['SQLException: missing table']}
  ]}],false);
  const diagnostics=sent.context.find(item=>item.kind==='diagnostics');
  const errors=JSON.parse(diagnostics.content);
  assert.equal(errors.length,2);
  assert.equal(errors[0].type,'error');
  assert.equal(errors[0].truncated,true);
  assert.match(errors[1].text,/missing table/);
  assert.ok(errors.reduce((total,item)=>total+item.text.length,0)<=12000);
  assert.equal(JSON.stringify(sent).includes('DO_NOT_SEND'),false);
  assert.equal(diagnostics.metadata.errorsTruncated,1);
});
