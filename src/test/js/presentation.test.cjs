const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const context = {window:{}};
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
  assert.match(html, /<h1>Heading<\/h1>/);
  assert.match(html, /<strong>bold<\/strong>/);
  assert.match(html, /<ul><li>item one<\/li><li>item two<\/li><\/ul>/);
  assert.match(html, /<th>Key<\/th>/);
  assert.match(html, /syntax-keyword/);
  assert.doesNotMatch(html, /<img|href="javascript:/);
  assert.match(html, /&lt;img/);
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
  assert.match(html, /<h2>An experiment<\/h2>/);
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
