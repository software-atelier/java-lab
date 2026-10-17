const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const root=path.join(__dirname,'../../main/resources/static');
function harness(){
  const nodes=new Map(),copies=[],state={tabs:[],active:null,launcher:false};let uid=0;
  const document={activeElement:null,createElement:()=>({style:{},select(){},remove(){}}),body:{append(child){document.appended=child;}},execCommand(){copies.push(document.appended.value);return true;},addEventListener(){}};
  const element=id=>{if(!nodes.has(id))nodes.set(id,{textContent:'',hidden:false,children:[],replaceChildren(child){this.children=[child];}});return nodes.get(id);};
  const window={};const context={window,document,state,$:element,atob:value=>Buffer.from(value,'base64').toString('binary'),crypto:{randomUUID:()=>String(++uid)},navigator:{clipboard:{writeText:async text=>copies.push(text)}},
    openDocuments:()=>state.tabs,notebookPath:(folder,name)=>folder?folder+'/'+name:name,
    renderWorkspace(){window.ImageViewer.activate(state.active?.type==='image'?state.active:null);},renderTabs(){},notify(){},downloadWorkspaceFile(){},
    api:async url=>({path:new URL('http://local'+url).searchParams.get('path'),mime:'image/png',width:30,height:20,size:100,revision:'hash'})};
  vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(root,'image-viewer.js'),'utf8'),context);vm.runInContext(fs.readFileSync(path.join(root,'presentation.js'),'utf8'),context);
  return {window,state,copies,element,context,open:(name='photo (1).png')=>window.ImageViewer.open(name,'My pictures')};
}
test('image tabs provide Java/Markdown clipboard examples using the exact workspace-relative path',async()=>{
  const h=harness();await h.open();const path='My pictures/photo (1).png';
  assert.equal(h.element('#image-java-example').textContent,`Images.show(${JSON.stringify(path)});`);
  const md=h.element('#image-md-example').textContent;assert.match(md,/My%20pictures\/photo%20%281%29\.png/);
  assert.deepEqual([...h.window.LabPresentation.markdownImagePaths(md)],[path]);
  await h.element('#image-copy-java').onclick();await h.element('#image-copy-md').onclick();assert.equal(h.copies.length,2);assert.equal(h.copies[1],md);
  assert.match(h.element('#image-preview').children[0].src,/api\/files\/image\?path=/);
});
test('renaming refreshes preview examples and old image errors cannot corrupt another tab',async()=>{
  const h=harness();await h.open();const first=h.state.active,oldImage=h.element('#image-preview').children[0];
  first.path='My pictures/renamed.png';first.name='renamed.png';h.window.ImageViewer.refresh(first);assert.match(h.element('#image-java-example').textContent,/renamed\.png/);
  const staleReload=h.element('#image-preview').children[0];await h.element('#image-reload').onclick();staleReload.onerror();assert.equal(h.element('#image-error').hidden,true);
  await h.open('second.png');oldImage.onerror();assert.equal(h.element('#image-error').hidden,true);
});
test('copy buttons fall back to the selection clipboard if native clipboard permission is denied',async()=>{
  const h=harness();await h.open();h.context.navigator.clipboard.writeText=async()=>{throw Error('denied');};
  await h.element('#image-copy-java').onclick();assert.equal(h.copies[0],h.element('#image-java-example').textContent);
});
test('Markdown images reject external/traversal paths, ignore code examples and embed assets in HTML export',()=>{
  const h=harness(),p=h.window.LabPresentation;
  const source='![safe](<folder/photo%20one.png>)\n\n`![code](code.png)`\n\n```md\n![code](fenced.png)\n```\n\n![bad](https://example.com/a.png)\n![bad](../a.png)';
  assert.deepEqual([...p.markdownImagePaths(source)],['folder/photo one.png']);assert.doesNotMatch(p.markdown(source),/src="https:/);
  const doc={cells:[{cell_type:'markdown',source:'![safe](photo.png)'}]};assert.throws(()=>p.exportHtml('Example.jnb',doc),/asset is missing/);
  const uri='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB';
  const html=p.exportHtml('Example.jnb',doc,{markdownImages:{'photo.png':uri}});assert.ok(html.includes(uri));assert.doesNotMatch(html,/src="api\/files/);
});

test('supported embedded SVG, JPEG and WebP outputs render with safe MIME data URLs',()=>{
  const p=harness().context.window.LabPresentation;
  const svg=Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1"/>').toString('base64');
  const cases=[['image/svg+xml',svg],['image/jpeg','/9j/AA=='],['image/webp',Buffer.concat([Buffer.from('RIFF'),Buffer.from([4,0,0,0]),Buffer.from('WEBP')]).toString('base64')]];
  for(const [mime,data] of cases){
    const output={data:{[mime]:data},metadata:{[mime]:{width:30,height:20}}};
    const html=p.imageHtml(output);assert.match(html,new RegExp(`src="data:${mime.replace('+','\\+')};base64,`));assert.match(html,/width="30" height="20"/);
    const exportHtml=p.exportHtml('Image.jnb',{cells:[{cell_type:'code',source:'Images.show(path);',outputs:[{output_type:'display_data',...output}]}]});
    assert.ok(exportHtml.includes(`data:${mime};base64,${data}`));
  }
  assert.equal(p.imageHtml({data:{'image/svg+xml':'<svg>not base64</svg>'}}),'');
});
