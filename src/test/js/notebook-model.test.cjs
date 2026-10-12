const {test}=require('node:test');
const assert=require('node:assert/strict');
globalThis.crypto ||= require('node:crypto').webcrypto;
const M=require('../../main/resources/static/notebook-model.js');
const notebook=(values=['a','b','c','d'])=>({selected:0,document:{cells:values.map(v=>M.newCell('code',v))}});
const values=n=>n.document.cells.map(M.text);

test('Range selection extends and shrinks around its original anchor',()=>{
  const n=notebook();M.select(n,1);M.select(n,3,true);assert.deepEqual(M.selected(n),[1,2,3]);
  M.select(n,2,true);assert.deepEqual(M.selected(n),[1,2]);M.select(n,0,true);assert.deepEqual(M.selected(n),[0,1]);
});
test('Copied cells preserve notebook metadata and outputs but paste with independent ids',()=>{
  const n=notebook(['code']);const original=n.document.cells[0];original.metadata.custom={note:'keep'};original.outputs=[{output_type:'stream',name:'stdout',text:'value'}];original.busy=true;original.editing=true;
  const pasted=M.pasted(M.copied(n));assert.notEqual(pasted[0].id,original.id);assert.deepEqual(pasted[0].outputs,original.outputs);assert.equal(pasted[0].busy,undefined);
  pasted[0].metadata.custom.note='changed';assert.equal(original.metadata.custom.note,'keep');
});
test('Deletion is reversible and leaves an editable empty cell when deleting everything',()=>{
  const n=notebook(['a','b']);M.select(n,0);M.select(n,1,true);M.remove(n);assert.equal(n.document.cells.length,1);assert.equal(M.text(n.document.cells[0]),'');
  M.undo(n);assert.deepEqual(values(n),['a','b']);assert.deepEqual(M.selected(n),[0,1]);M.undo(n,true);assert.equal(n.document.cells.length,1);
});
test('Cell undo preserves unrelated manual edits and newer execution outputs',()=>{
  const n=notebook(['a','b']);M.select(n,1);M.remove(n);
  n.document.cells[0].source=['edited a'];n.document.cells[0].outputs=[{output_type:'stream',text:'new result'}];n.document.cells[0].execution_count=8;
  M.undo(n);assert.deepEqual(values(n),['edited a','b']);assert.equal(n.document.cells[0].execution_count,8);assert.equal(n.document.cells[0].outputs[0].text,'new result');
});
test('Moving multiple cells preserves order, selection, and current source when undone',()=>{
  const n=notebook();M.select(n,1);M.select(n,2,true);M.move(n,-1);assert.deepEqual(values(n),['b','c','a','d']);assert.deepEqual(M.selected(n),[0,1]);
  n.document.cells[0].source=['edited b'];M.undo(n);assert.deepEqual(values(n),['a','edited b','c','d']);
});
test('Merge and split clear stale results and restore cell identities on undo',()=>{
  const n=notebook(['one','two','three']);const ids=n.document.cells.map(c=>c.id);M.select(n,0);M.select(n,1,true);M.merge(n);assert.deepEqual(values(n),['one\n\ntwo','three']);
  M.undo(n);assert.deepEqual(n.document.cells.map(c=>c.id),ids);
  M.select(n,0);M.split(n,1);assert.deepEqual(values(n),['o','ne','two','three']);assert.equal(n.selected,1);M.undo(n);assert.deepEqual(values(n),['one','two','three']);
});
test('Type conversion and heading collapse use persistent cell metadata',()=>{
  const n=notebook(['Title','code','Subheading','subcode','Next','last']);M.select(n,0);M.changeType(n,'markdown',1);
  M.select(n,2);M.changeType(n,'markdown',2);M.select(n,4);M.changeType(n,'markdown',1);
  n.document.cells[0].metadata.java_lab={heading_collapsed:true};assert.deepEqual([...M.hidden(n)],n.document.cells.slice(1,4).map(c=>c.id));
  M.select(n,0);M.changeType(n,'code');assert.deepEqual(M.hidden(n),new Set());M.undo(n);assert.equal(n.document.cells[0].cell_type,'markdown');
});
