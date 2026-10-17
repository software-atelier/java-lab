const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const resources=path.join(__dirname,'../../main/resources/static');
const context={window:{}};vm.runInNewContext(fs.readFileSync(path.join(resources,'presentation.js'),'utf8'),context);const p=context.window.LabPresentation;
test('Markdown supports web/mail links with titles and workspace file links including spaces and parentheses',()=>{
  const html=p.markdown('[Docs](https://example.com/wiki/Example_(test) "Documentation") [Mail](mailto:help@example.com) [Data](<Data/sales 2026.csv>) [Photo](opencode.png)');
  assert.match(html,/href="https:\/\/example.com\/wiki\/Example_\(test\)" target="_blank" rel="noopener noreferrer" title="Documentation"/);
  assert.match(html,/href="mailto:help@example.com"/);
  assert.match(html,/data-workspace-link="Data\/sales 2026.csv"/);assert.match(html,/data-workspace-link="opencode.png"/);
});
test('Markdown section links have namespaced anchors and remain functional in standalone HTML',()=>{
  const doc={cells:[{cell_type:'markdown',source:'## My Section\n\n[Jump](#my-section)\n\n## My Section'}]};
  const html=p.exportHtml('Example.jnb',doc);
  assert.match(html,/id="md-my-section"/);assert.match(html,/id="md-my-section-1"/);assert.match(html,/href="#md-my-section"/);
  assert.doesNotMatch(p.markdown('## help-dialog'),/id="help-dialog"/);
});
test('unsafe targets stay text, code stays code, and exported workspace links avoid application endpoints',()=>{
  const html=p.markdown('[bad](javascript:alert(1)) [bad](data:text/html,x) [bad](../secret.txt) [bad](.hidden.txt) `[code](notes.txt)`');
  assert.doesNotMatch(html,/<a\b/);
  const exported=p.exportHtml('Example.jnb',{cells:[{cell_type:'markdown',source:'[File](<Data/notes one.txt>)'}]});
  assert.match(exported,/href="Data\/notes%20one.txt"/);assert.doesNotMatch(exported,/api\/files|data-workspace-link/);
});
test('clicking a workspace Markdown link uses validated file metadata and opens its corresponding tab',async()=>{
  const app=fs.readFileSync(path.join(resources,'app.js'),'utf8'),start=app.indexOf("document.addEventListener('click', async event"),end=app.indexOf("$('#file-search').addEventListener",start);
  let click;const calls=[];const sandbox={document:{addEventListener(name,fn){click=fn;}},
    api:async url=>{calls.push(url);return {path:'Data/notes.txt',name:'notes.txt',type:'text'};},openFile:async(...args)=>calls.push(args),notify(){}};
  vm.runInNewContext(app.slice(start,end),sandbox);
  let prevented=false;await click({target:{closest:selector=>selector==='a[data-workspace-link]'?{dataset:{workspaceLink:'Data/notes.txt'}}:null},preventDefault(){prevented=true;}});
  assert.equal(prevented,true);assert.equal(calls[0],'/files/info?path=Data%2Fnotes.txt');assert.deepEqual(calls[1],['notes.txt','Data','text']);
});
