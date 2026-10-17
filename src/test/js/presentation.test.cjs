const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const context = {window:{},atob:value=>Buffer.from(value,'base64').toString('binary')};
vm.runInNewContext(fs.readFileSync(require('node:path').join(__dirname,'../../main/resources/static/presentation.js'),'utf8'), context);
const {highlightJava, markdown, exportHtml} = context.window.LabPresentation;

test('Java lexer distinguishes text blocks, comments, strings, types, and keywords while preserving code', () => {
  const code = '/* class Fake */\nString text = "hello <world>";\nint count = 42;\nString block = """\nrecord inside a string\n""";';
  const html = highlightJava(code);
  assert.match(html, /class="syntax-comment">\/\* class Fake/);
  assert.match(html, /class="syntax-type">String/);
  assert.match(html, /class="syntax-keyword">int/);
  assert.match(html, /class="syntax-number">42/);
  assert.match(html, /hello &lt;world&gt;/);
  const restored = html.replace(/<[^>]*>/g,'').replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&quot;/g,'"').replace(/&#39;/g,"'").replace(/&amp;/g,'&');
  assert.equal(restored,code);
});

test('Markdown preview supports structured prose, code, and tables without permitting HTML injection', () => {
  const html = markdown('# Heading\n\n**bold** and `code`\n\n- item one\n- item two\n\n| Key | Value |\n| --- | --- |\n| A | 42 |\n\n```java\nint total = 42;\n```\n\n<img src=x onerror="alert(1)">\n[bad](javascript:alert)');
  assert.match(html, /<h1[^>]*>Heading<\/h1>/);
  assert.match(html, /<strong>bold<\/strong>/);
  assert.match(html, /<ul><li>item one<\/li><li>item two<\/li><\/ul>/);
  assert.match(html, /<th>Key<\/th>/);
  assert.match(html, /syntax-keyword/);
  assert.doesNotMatch(html, /<img|href="javascript:/);
  assert.match(html, /&lt;img/);
});

test('Markdown links are safe, section anchors are globally stable and image paths are discoverable',()=>{
  const first=markdown('## Shared heading\n\n[Web](https://example.com) [Local](<docs/a b.txt>) [Section](#shared-heading)\n\n![Photo](<images/a b.png>)');
  const second=markdown('## Shared heading', {headingCounts:new Map([['shared-heading',1]])});
  assert.match(first,/id="md-shared-heading"/);assert.match(second,/id="md-shared-heading-1"/);
  assert.match(first,/href="https:\/\/example.com"/);assert.match(first,/data-workspace-link="docs\/a b.txt"/);assert.match(first,/href="#md-shared-heading"/);
  assert.deepEqual([...context.window.LabPresentation.markdownImagePaths('![Photo](<images/a b.png>)')],['images/a b.png']);
  for(const unsafe of ['![bad](https://example.com/x.png)','![bad](../x.png)','![bad](%2e%2e/x.png)','![bad](.hidden/x.png)'])assert.deepEqual([...context.window.LabPresentation.markdownImagePaths(unsafe)],[]);
});

test('HTML export embeds styling, highlighted Java, Markdown, and escaped saved outputs with no scripts or asset dependencies', () => {
  const document = {cells:[
    {cell_type:'markdown',source:['## An experiment\n\nA **good** idea.']},
    {cell_type:'code',source:['int answer = 42;'],execution_count:3,outputs:[
      {output_type:'stream',name:'stdout',text:'42'},
      {output_type:'stream',name:'stdout',text:'<script>alert(1)</script>'},
      {output_type:'error',ename:'Error',evalue:'bad',traceback:['Error: bad','  at cell:1']}
    ]}
  ]};
  const html = exportHtml('Experiment <unsafe>.jnb',document);
  assert.match(html, /^<!doctype html>/);
  assert.match(html, /<style>/);
  assert.match(html, /Experiment &lt;unsafe&gt;/);
  assert.match(html, /<h2[^>]*>An experiment<\/h2>/);
  assert.match(html, /syntax-keyword/);
  assert.match(html, /EXECUTION 3/);
  assert.match(html, /<pre>42<\/pre>/);
  assert.match(html, /Error: bad\n  at cell:1/);
  assert.match(html, /@media print/);
  assert.doesNotMatch(html, /<script|<link|<img|<iframe/);
});

test('Export includes the last text result and logs while ignoring unsupported rich output types', () => {
  const html = exportHtml('Quiet.jnb',{cells:[{cell_type:'code',source:'Object value = new Object(); log("Total = %", "42");',outputs:[
    {output_type:'execute_result',data:{'text/plain':'42'}},
    {output_type:'display_data',data:{'text/plain':'unsupported type'}},
    {output_type:'stream',name:'stdout',text:'Total = 42\n'}
  ]}]});
  assert.doesNotMatch(html,/unsupported type/);
  assert.match(html,/<pre>42<\/pre>/);
  assert.match(html,/<pre>Total = 42\n<\/pre>/);
});

test('Chart outputs embed PNGs in notebook rendering and HTML export, with escaped titles and no remote image URLs', () => {
  const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aT20AAAAASUVORK5CYII=';
  const output = {output_type:'display_data',data:{'image/png':png,'text/plain':'Sales " onerror="alert(1) <chart>'},metadata:{'image/png':{width:900,height:520}}};
  const image = context.window.LabPresentation.imageHtml(output);
  assert.match(image,/src="data:image\/png;base64,iVBORw0KGgo/);
  assert.match(image,/width="900" height="520"/);
  assert.match(image,/Sales &quot; onerror=&quot;alert\(1\) &lt;chart&gt;/);
  const html = exportHtml('Charts.jnb',{cells:[{cell_type:'code',source:'Charts.show(chart);',outputs:[output]}]});
  assert.match(html,/class="export-output chart"/);
  assert.match(html,/class="chart-image"/);
  assert.doesNotMatch(html,/<script|src="https?:/);
  assert.equal(context.window.LabPresentation.imageHtml({data:{'image/png':'https://example.com/image.png'}}),'');
});

test('ECharts options are inert escaped JSON and standalone exports embed the runtime only when needed', () => {
  const mime = 'application/vnd.apache.echarts+json';
  const option = {title:{text:'</script><img src=x onerror="alert(1)">'},series:[{type:'bar',data:[1,2]}]};
  const output = {output_type:'display_data',data:{[mime]:option,'text/plain':'Interactive <chart>'},metadata:{[mime]:{width:900,height:450}}};
  const markup = context.window.LabPresentation.echartsHtml(output);
  assert.match(markup,/class="echarts-chart"/);
  assert.match(markup,/height:450px/);
  assert.match(markup,/&lt;\/script&gt;&lt;img/);
  assert.doesNotMatch(markup,/<script|<img/);
  const document = {cells:[{cell_type:'code',source:'ECharts.show(option);',outputs:[output]}]};
  assert.throws(()=>exportHtml('Chart.jnb',document),/runtime is required/);
  const html = exportHtml('Chart.jnb',document,{runtime:'window.echarts={}; // </script>',bootstrap:'window.initialized=true;'});
  assert.match(html,/window.echarts/);
  assert.match(html,/window.initialized=true/);
  assert.match(html,/<\\\/script>/);
  assert.doesNotMatch(html,/script src=|<img src=x/);
  assert.equal(context.window.LabPresentation.echartsHtml({data:{[mime]:'alert(1)'}}),'');
});

test('Grid tables carry escaped local data and offline export embeds both runtime and styling', () => {
  const mime='application/vnd.gridjs+json';
  const option={title:'Sales <report>',columns:[{name:'Name',id:'name'}],data:[['<img src=x onerror="alert(1)">']],search:true,sort:true,pagination:{limit:2}};
  const output={output_type:'display_data',data:{[mime]:option},metadata:{[mime]:{width:1000}}};
  const markup=context.window.LabPresentation.gridHtml(output);
  assert.match(markup,/class="grid-table"/);assert.match(markup,/Sales &lt;report&gt;/);
  assert.match(markup,/&lt;img src=x/);assert.doesNotMatch(markup,/<img|<script/);
  const encoded=markup.match(/data-grid="([^"]+)"/)[1];
  const decoded=encoded.replace(/&quot;/g,'"').replace(/&#39;/g,"'").replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&amp;/g,'&');
  assert.equal(JSON.parse(decoded).data[0][0],option.data[0][0]);
  const document={cells:[{cell_type:'code',source:'Grid.table().columns("Name").row("Ada").show();',outputs:[output]}]};
  assert.throws(()=>exportHtml('Grid.jnb',document),/Grid.js assets are required/);
  const html=exportHtml('Grid.jnb',document,{gridRuntime:'window.gridjs={};',gridBootstrap:'window.tablesReady=true;',gridCss:'.gridjs-table{color:black}'});
  assert.match(html,/window.gridjs/);assert.match(html,/tablesReady/);assert.match(html,/gridjs-table\{color:black\}/);
  assert.doesNotMatch(html,/script src=|<img src=x/);
  assert.equal(context.window.LabPresentation.gridHtml({data:{[mime]:{columns:'invalid',data:[]}}}),'');
});

test('Formatted code safely highlights JSON and exports without executing source markup', () => {
  const output={output_type:'display_data',data:{'application/vnd.java-lab.code+json':{format:'json',content:'{"name":"<script>alert(1)</script>","ok":true}'}}};
  const markup=context.window.LabPresentation.codeHtml(output);
  assert.match(markup,/formatted-output/);
  assert.match(markup,/class="structured-explorer"/);
  assert.match(markup,/class="output-tree-node" open/);
  assert.match(markup,/data-output-action="copy"/);
  assert.match(markup,/data-output-action="download"/);
  assert.match(markup,/\n  <span class="syntax-string">&quot;name&quot;<\/span>/);
  assert.match(markup,/&lt;script&gt;/);
  assert.doesNotMatch(markup,/<script>/);
  const html=exportHtml('Code.jnb',{cells:[{cell_type:'code',source:'Formatted.json("test");',outputs:[output]}]});
  assert.match(html,/formatted-output/);
  assert.match(html,/<script>\(function installOutputActions\(\)/);
  assert.doesNotMatch(html,/<script>alert\(1\)<\/script>/);
  assert.doesNotMatch(html,/<script src=|src="https?:/);
});

test('JSON explorer escapes keys and values, and falls back to source for invalid JSON', () => {
  const codeHtml=context.window.LabPresentation.codeHtml;
  const dangerous={output_type:'display_data',data:{'application/vnd.java-lab.code+json':{format:'json',content:'{"<img onerror=alert(1)>":["</script>",2]}'}}};
  const html=codeHtml(dangerous);
  assert.match(html,/&lt;img onerror=alert\(1\)&gt;/);
  assert.match(html,/&lt;\/script&gt;/);
  assert.doesNotMatch(html,/<img|<\/script>/);
  assert.match(html,/Array \(2\)/);
  const invalid={output_type:'display_data',data:{'application/vnd.java-lab.code+json':{format:'json',content:'not JSON'}}};
  assert.doesNotMatch(codeHtml(invalid),/structured-explorer/);
  assert.match(codeHtml(invalid),/not JSON/);
});

test('XML explorer renders an escaped tree and rejects document type declarations', () => {
  const text={nodeType:3,nodeValue:'<unsafe>',childNodes:[]};
  const child={nodeType:1,tagName:'item',attributes:[{name:'label',value:'<img src=x>'}],childNodes:[text]};
  const root={nodeType:1,tagName:'root',attributes:[],childNodes:[child]};
  context.DOMParser=class {parseFromString() {return {documentElement:root,querySelector:()=>null};}};
  const codeHtml=context.window.LabPresentation.codeHtml;
  const output=content=>({output_type:'display_data',data:{'application/vnd.java-lab.code+json':{format:'xml',content}}});
  const html=codeHtml(output('<root><item label="&lt;img src=x&gt;">&lt;unsafe&gt;</item></root>'));
  assert.match(html,/class="structured-explorer"/);
  assert.match(html,/&lt;img src=x&gt;/);
  assert.match(html,/&lt;unsafe&gt;/);
  assert.doesNotMatch(html,/<img|<unsafe>/);
  assert.doesNotMatch(codeHtml(output('<!DOCTYPE root><root/>')),/structured-explorer/);
  delete context.DOMParser;
});

test('Forms render escaped controls, use persisted values, and export as disabled snapshots', () => {
  const output={output_type:'display_data',data:{'application/vnd.java-lab.form+json':{id:'prefs',title:'<Preferences>',fields:[
    {id:'name',label:'Name',type:'text',value:'Ada'},
    {id:'role',label:'Role',type:'select',value:'reader',options:['reader','admin']},
    {id:'enabled',label:'Enabled',type:'checkbox',value:false},
    {id:'note',label:'Note',type:'textarea',value:''}
  ]}}};
  const markup=context.window.LabPresentation.formHtml(output,{name:'<img src=x onerror=alert(1)>',role:'admin',enabled:true,note:'<script>'});
  assert.match(markup,/value="&lt;img src=x onerror=alert\(1\)&gt;"/);
  assert.match(markup,/value="admin" selected/);
  assert.match(markup,/type="checkbox"[^>]+checked/);
  assert.match(markup,/&lt;script&gt;/);
  assert.doesNotMatch(markup,/<img|<script/);
  const html=exportHtml('Forms.jnb',{metadata:{java_lab:{form_values:{name:'Grace'}}},cells:[{cell_type:'code',source:'Forms.form("prefs").show();',outputs:[output]}]});
  assert.match(html,/value="Grace"/);
  assert.match(html,/data-form-field="name"[^>]+disabled/);
});

test('Form validation metadata and multi-select values render safely in notebook and export', () => {
  const output={output_type:'display_data',data:{'application/vnd.java-lab.form+json':{id:'validated',fields:[
    {id:'name',type:'text',label:'Name',value:'Ada',required:true},
    {id:'score',type:'number',label:'Score',value:4,min:0,max:10},
    {id:'email',type:'email',label:'Email',value:''},
    {id:'date',type:'date',label:'Date',value:''},
    {id:'tags',type:'multiselect',label:'Tags',value:[],options:['Java','<unsafe>']}
  ]}}};
  const render=context.window.LabPresentation.formHtml;
  const html=render(output,{tags:['Java','<unsafe>']});
  assert.match(html,/data-form-field="name"[^>]*required/);
  assert.match(html,/data-form-field="score"[^>]*min="0"[^>]*max="10"/);
  assert.match(html,/type="email"/);
  assert.match(html,/type="date"/);
  assert.match(html,/multiple size="4"/);
  assert.match(html,/value="Java" selected/);
  assert.match(html,/value="&lt;unsafe&gt;" selected/);
  assert.doesNotMatch(html,/<unsafe>/);
  const snapshot=exportHtml('Forms.jnb',{metadata:{java_lab:{form_values:{tags:['Java']}}},cells:[{cell_type:'code',source:'',outputs:[output]}]});
  assert.match(snapshot,/multiple size="4"/);
  assert.match(snapshot,/data-form-field="tags"[^>]*disabled/);
});
