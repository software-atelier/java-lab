const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const code=fs.readFileSync(path.join(__dirname,'../../main/resources/static/app.js'),'utf8');
const start=code.indexOf('function markFollowingStale(');
const end=code.indexOf('function hydrateFormValues(',start);
const context={active:()=>null,updateStatus:()=>{}};
vm.runInNewContext(code.slice(start,end),context);

test('Stale outputs stay attached to cell identities across reorder and selective rerun',()=>{
  const cell=(id)=>({id,outputs:[{output_type:'stream',text:id}]});
  const n={document:{cells:[cell('form'),cell('first'),cell('second'),cell('third')]}};
  context.markFollowingStale(n,0);
  assert.equal(n.staleCells.has('first'),true);
  n.document.cells.splice(1,0,cell('inserted'));
  [n.document.cells[2],n.document.cells[3]]=[n.document.cells[3],n.document.cells[2]];
  context.clearStaleThrough(n,4);
  assert.equal(n.staleCells.has('third'),false);
  assert.equal(n.staleCells.has('first'),true);
  assert.equal(n.staleCells.has('second'),true);
  assert.equal(n.staleCells.has('inserted'),false);
});
