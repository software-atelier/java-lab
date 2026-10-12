/* Pure notebook cell operations; shared by keyboard controls, menus, and tests. */
(function(root, factory) {
  const model=factory();
  if(typeof module==='object'&&module.exports)module.exports=model;
  else root.NotebookModel=model;
})(globalThis, () => {
  const clone=value=>structuredClone(value);
  const id=()=>globalThis.crypto.randomUUID();
  const text=c=>Array.isArray(c.source)?c.source.join(''):c.source||'';
  function newCell(type='code',source='') {
    const c={id:id(),cell_type:type,metadata:{},source:[source]};
    if(type==='code')Object.assign(c,{execution_count:null,outputs:[]});return c;
  }
  function clean(c) {const {busy,editing,...value}=c;return clone(value);}
  function ensure(n) {
    n.document.cells.forEach(c=>{c.id ||= id();c.metadata ||= {};});
    n.selected=Math.max(0,Math.min(n.selected||0,n.document.cells.length-1));
    if(!n.controls)n.controls={mode:'command',ids:new Set(),anchor:null,undo:[],redo:[],collapsed:new Set(),texts:new Map()};
    const s=n.controls,valid=new Set(n.document.cells.map(c=>c.id));
    s.ids=new Set([...s.ids].filter(key=>valid.has(key)));
    if(!s.ids.size&&n.document.cells[n.selected])s.ids.add(n.document.cells[n.selected].id);
    if(!valid.has(s.anchor))s.anchor=n.document.cells[n.selected]?.id||null;
    return s;
  }
  function selected(n) {const s=ensure(n);return n.document.cells.flatMap((c,i)=>s.ids.has(c.id)?[i]:[]);}
  function select(n,index,extend=false,preserve=false) {
    const s=ensure(n);index=Math.max(0,Math.min(index,n.document.cells.length-1));
    const c=n.document.cells[index];if(!c)return;
    if(extend) {
      const anchor=n.document.cells.findIndex(c=>c.id===s.anchor);
      s.ids=new Set(n.document.cells.slice(Math.min(index,anchor),Math.max(index,anchor)+1).map(c=>c.id));
    }else if(!preserve){s.ids=new Set([c.id]);s.anchor=c.id;}
    n.selected=index;
  }
  function snapshot(n) {
    const s=ensure(n);return {cells:n.document.cells.map(clean),active:n.document.cells[n.selected]?.id,ids:[...s.ids]};
  }
  function record(n,operation) {
    const s=ensure(n),before=snapshot(n);operation();const after=snapshot(n);
    if(JSON.stringify(before.cells)===JSON.stringify(after.cells))return false;
    s.undo.push({before,after});if(s.undo.length>50)s.undo.shift();s.redo=[];return true;
  }
  function restore(n,target,origin) {
    // Preserve unrelated cell edits and execution results made after the cell action.
    const current=new Map(n.document.cells.map(c=>[c.id,c])),old=new Map(origin.cells.map(c=>[c.id,c]));
    n.document.cells=target.cells.map(c=>{
      const live=current.get(c.id),prior=old.get(c.id),copy=clean(c);
      if(live&&prior)for(const key of new Set([...Object.keys(c),...Object.keys(prior),...Object.keys(live)])) {
        if(JSON.stringify(c[key])===JSON.stringify(prior[key])) {
          if(key in live)copy[key]=clone(live[key]);else delete copy[key];
        }
      }
      return copy;
    });
    const s=ensure(n);s.ids=new Set(target.ids);s.anchor=target.active;
    n.selected=Math.max(0,n.document.cells.findIndex(c=>c.id===target.active));ensure(n);
  }
  function undo(n,redo=false) {
    const s=ensure(n),from=redo?s.redo:s.undo,to=redo?s.undo:s.redo,entry=from.pop();if(!entry)return false;
    restore(n,redo?entry.after:entry.before,redo?entry.before:entry.after);to.push(entry);return true;
  }
  function insert(n,index,cells) {
    return record(n,()=>{
      index=Math.max(0,Math.min(index,n.document.cells.length));n.document.cells.splice(index,0,...cells);
      const s=ensure(n);s.ids=new Set(cells.map(c=>c.id));n.selected=index;s.anchor=cells[0]?.id;
    });
  }
  function remove(n) {
    const indices=selected(n),ids=ensure(n).ids;
    return record(n,()=>{
      n.document.cells=n.document.cells.filter(c=>!ids.has(c.id));
      if(!n.document.cells.length)n.document.cells.push(newCell());
      select(n,Math.min(indices[0]||0,n.document.cells.length-1));
    });
  }
  function copied(n) {return selected(n).map(i=>clean(n.document.cells[i]));}
  function pasted(cells) {return cells.map(c=>({...clean(c),id:id()}));}
  function move(n,direction) {
    const s=ensure(n),active=n.document.cells[n.selected]?.id;
    return record(n,()=>{
      const cells=n.document.cells;
      if(direction<0)for(let i=1;i<cells.length;i++) {
        if(s.ids.has(cells[i].id)&&!s.ids.has(cells[i-1].id))[cells[i-1],cells[i]]=[cells[i],cells[i-1]];
      }
      else for(let i=cells.length-2;i>=0;i--) {
        if(s.ids.has(cells[i].id)&&!s.ids.has(cells[i+1].id))[cells[i],cells[i+1]]=[cells[i+1],cells[i]];
      }
      n.selected=cells.findIndex(c=>c.id===active);
    });
  }
  function clearResult(c) {if(c.cell_type==='code'){c.outputs=[];c.execution_count=null;}}
  function merge(n,direction=0) {
    let indices=selected(n);
    if(direction<0&&indices[0]>0)indices.unshift(indices[0]-1);
    else if(direction>0&&indices.at(-1)<n.document.cells.length-1)indices.push(indices.at(-1)+1);
    else if(!direction&&indices.length===1&&indices[0]<n.document.cells.length-1)indices.push(indices[0]+1);
    if(indices.length<2)return false;
    return record(n,()=>{
      const keep=n.document.cells[indices[0]];
      keep.source=[indices.map(i=>text(n.document.cells[i])).join('\n\n')];clearResult(keep);
      n.document.cells=n.document.cells.filter((c,i)=>i===indices[0]||!indices.includes(i));select(n,indices[0]);
    });
  }
  function split(n,offset) {
    return record(n,()=>{
      const index=n.selected,c=n.document.cells[index],value=text(c);
      const next={...clean(c),id:id(),source:[value.slice(offset)]};
      c.source=[value.slice(0,offset)];clearResult(c);clearResult(next);
      n.document.cells.splice(index+1,0,next);select(n,index+1);
    });
  }
  function changeType(n,type,heading=0) {
    return record(n,()=>selected(n).forEach(i=>{
      const c=n.document.cells[i],old=c.cell_type;c.cell_type=type;delete c.editing;
      if(type==='code'){if(old!=='code'){c.outputs=[];c.execution_count=null;}}
      else {delete c.outputs;delete c.execution_count;}
      if(heading)c.source=['#'.repeat(heading)+' '+text(c).replace(/^#{1,6}\s+/, '')];
    }));
  }
  function heading(c) {return c.cell_type==='markdown'?text(c).match(/^(#{1,6})\s+/)?.[1].length||0:0;}
  function hidden(n) {
    ensure(n);const ids=new Set();let level=0;
    for(const c of n.document.cells){const h=heading(c);if(level&&h&&h<=level)level=0;if(level)ids.add(c.id);if(!level&&h&&c.metadata.java_lab?.heading_collapsed)level=h;}
    return ids;
  }
  return {newCell,clean,ensure,selected,select,snapshot,record,undo,insert,remove,copied,pasted,move,merge,split,changeType,clearResult,heading,hidden,text};
});
