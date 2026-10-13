/* JupyterLab 4.6 command/edit controls, plus classic Notebook aliases. */
window.NotebookControls = (() => {
  const M=NotebookModel;
  let clipboard=[],sequence=null,paletteIndex=0,closedTabs=[];
  const shortcuts=[
    ['Both','Shift Enter','Run selected cells and advance'],['Both','Ctrl / Cmd Enter','Run selected cells'],['Both','Alt Enter','Run and insert below'],
    ['Command','Enter','Enter edit mode'],['Edit','Escape / Ctrl M','Enter command mode'],
    ['Command','↑ / K, ↓ / J','Select previous / next cell'],['Command','Home / End','Select first / last cell'],
    ['Command','Shift ↑ / K, Shift ↓ / J','Extend cell selection'],['Command','Shift Home / End','Extend to first / last cell'],
    ['Command','Ctrl / Cmd A','Select all cells'],['Command','A / B','Insert cell above / below'],['Command','Shift A / B','Insert heading above / below'],
    ['Command','Y / M / R','Code / Markdown / raw'],['Command','1–6','Set Markdown heading level'],
    ['Command','X / C / V','Cut / copy / paste cells below'],['Command','Shift V','Paste cells above (classic)'],['Command','D D','Delete selected cells'],
    ['Command','Z / Shift Z','Undo / redo cell action'],['Command','Shift M','Merge selected cells / merge below'],
    ['Command','Ctrl Backspace','Merge with cell above'],['Command','Ctrl Shift M','Merge with cell below'],
    ['Command','Ctrl Shift ↑ / ↓','Move selected cells up / down'],['Edit','Ctrl Shift -','Split cell at cursor'],
    ['Command','L / Shift L','Toggle cell / notebook line numbers'],['Command','O / Shift O','Toggle outputs / output scrolling (classic)'],
    ['Command','← / →','Collapse / expand heading or navigate headings'],['Command','Ctrl Shift ← / →','Collapse / expand all headings'],
    ['Command','Shift R','Toggle side-by-side cell layout'],['Command','I I','Interrupt kernel'],['Command','0 0','Restart kernel'],
    ['Command','S','Save notebook (classic)'],['Command','H / ?','Shortcut help (classic)'],
    ['Edit','Tab','Complete Java or indent'],['Edit','Shift Tab','Inspect signature or outdent'],['Edit','Ctrl / Cmd Space','Java completion'],
    ['Edit','Ctrl / Cmd [ / ]','Outdent / indent'],['Edit','Ctrl / Cmd /','Toggle line comment'],['Edit','Alt A','Toggle block comment'],
    ['Edit','Ctrl / Cmd D, Ctrl / Cmd Shift K','Delete line'],['Edit','Ctrl / Cmd Shift D','Select next occurrence'],
    ['Edit','Ctrl / Cmd B','Toggle Markdown bold'],['Edit','Ctrl / Cmd Z','Undo text edit'],['Edit','Ctrl / Cmd Shift Z / Ctrl Y','Redo text edit'],
    ['Edit','Alt ↑ / ↓','Browse executed cell history'],['Edit','↑ / ↓ at editor boundary','Move to adjacent cell'],
    ['Application','Ctrl / Cmd S','Save notebook'],['Application','Ctrl / Cmd Shift S','Save as'],
    ['Application','Ctrl / Cmd F / H','Find / find and replace'],['Application','Ctrl / Cmd G / Shift G','Next / previous match'],
    ['Application','Ctrl / Cmd Shift C / P','Command palette'],['Application','Ctrl / Cmd Shift H','Shortcut help'],
    ['Application','Ctrl / Cmd B','Toggle file browser (outside Markdown editor)'],['Application','Ctrl / Cmd Shift F / Alt 1','Focus file browser'],
    ['Application','Alt W / Ctrl Shift Q','Close notebook tab'],['Application','Ctrl / Cmd Shift [ / ]','Previous / next notebook tab'],
    ['Application','Ctrl / Cmd Shift T','Reopen closed notebook'],['Application','Ctrl / Cmd Shift D','Toggle simple interface (outside editor)'],
    ['Application','Ctrl / Cmd P','Print notebook'],['Application','Alt N','New Java notebook (Java Lab)']
    ,['File browser','↑ / ↓','Focus previous / next notebook file'],['File browser','Enter','Open notebook'],['File browser','F2 / Delete','Rename / delete notebook'],['File browser','Ctrl / Cmd D','Duplicate notebook file']
  ];
  function nstate(n=active()){return n?M.ensure(n):null;}
  function indexes(n=active()){return n?M.selected(n):[];}
  function resetSequence(){sequence=null;}
  function selected(n,index,extend=false,preserve=false){resetSequence();M.select(n,index,extend,preserve);paint(n);}
  function paint(n=active()) {
    if(!n||n!==active())return;
    const s=nstate(n);$('#notebook').classList.toggle('command-mode',s.mode==='command');$('#notebook').classList.toggle('edit-mode',s.mode==='edit');
    $$('#cells .cell').forEach((element,i)=>{
      const c=n.document.cells[i];element.classList.toggle('selected',i===n.selected);element.classList.toggle('marked',s.ids.has(c.id));
      element.classList.toggle('editing',i===n.selected&&s.mode==='edit');element.tabIndex=i===n.selected?0:-1;
      element.setAttribute('aria-current',i===n.selected?'true':'false');
    });
    $('#notebook-mode').textContent=s.mode==='edit'?'Edit':'Command';$('#notebook-mode').hidden=false;
  }
  function focusCommand(n=active()) {
    if(!n||n!==active())return;const element=$(`#cells [data-cell="${n.selected}"]`);
    (element||$('#notebook-scroll')).focus({preventScroll:true});element?.scrollIntoView({block:'nearest'});
  }
  function command(n=active(),focus=true) {
    if(!n)return;resetSequence();nstate(n).mode='command';window.JavaEditor?.dismiss();paint(n);updateStatus();if(focus)focusCommand(n);
  }
  function edit(n=active(),position=null) {
    if(!n||n!==active()||!n.document.cells[n.selected])return;
    resetSequence();const c=n.document.cells[n.selected];selected(n,n.selected);nstate(n).mode='edit';
    if(c.cell_type==='markdown'&&!c.editing){c.editing=true;renderCells();}
    paint(n);const editor=$(`#cells [data-cell="${n.selected}"] textarea`);if(!editor)return;
    editor.focus({preventScroll:true});if(position!==null)editor.setSelectionRange(position,position);
    editor.closest('.cell').scrollIntoView({block:'nearest'});updateStatus();
  }
  function changed(n,operation,editAfter=false) {
    if(!n||n.running)return;
    if(operation()===false)return;
    markDirty(n);renderCells();if(!$('#notebook-search').hidden)scanSearch();if(editAfter)edit(n);else command(n);
  }
  function insert(index,type='code',text='',editAfter=false) {
    const n=active();changed(n,()=>M.insert(n,index,[M.newCell(type,text)]),editAfter);
  }
  function insertHeading(direction) {
    const n=active();if(!n)return;const level=M.heading(n.document.cells[n.selected])||1;
    insert(n.selected+(direction>0?1:0),'markdown','#'.repeat(level)+' New section',true);
  }
  function moveSelection(delta,extend=false,boundary=null) {
    const n=active();if(!n)return;const hidden=M.hidden(n),available=n.document.cells.flatMap((c,i)=>hidden.has(c.id)?[]:[i]);
    const current=Math.max(0,available.indexOf(n.selected));
    const next=boundary==='first'?available[0]:boundary==='last'?available.at(-1):available[Math.max(0,Math.min(current+delta,available.length-1))];
    if(next!==undefined){selected(n,next,extend);command(n);}
  }
  function copy(){const n=active();if(n){clipboard=M.copied(n);notify(`${clipboard.length} cell${clipboard.length===1?'':'s'} copied`);}}
  function paste(above=false){const n=active();if(n&&clipboard.length)changed(n,()=>M.insert(n,(above?indexes(n)[0]:indexes(n).at(-1)+1),M.pasted(clipboard)));}
  function cut(){const n=active();if(!n||n.running)return;copy();changed(n,()=>M.remove(n));}
  function changeType(type,heading=0){const n=active();changed(n,()=>M.changeType(n,type,heading));}
  function metadata(operation){const n=active();changed(n,()=>M.record(n,()=>indexes(n).forEach(i=>operation(n.document.cells[i]))));}
  function lineNumbers(c,n=active()){return c.metadata?.java_lab?.line_numbers??n?.document.metadata?.java_lab?.line_numbers??false;}
  function setMeta(c,section,key,value){c.metadata ||= {};c.metadata[section] ||= {};c.metadata[section][key]=value;}
  function toggleLines(all=false) {
    const n=active();if(!n)return;
    if(all){n.document.metadata ||= {};n.document.metadata.java_lab ||= {};const value=!n.document.metadata.java_lab.line_numbers;n.document.metadata.java_lab.line_numbers=value;n.document.cells.forEach(c=>setMeta(c,'java_lab','line_numbers',value));markDirty(n);renderCells();command(n);}
    else metadata(c=>setMeta(c,'java_lab','line_numbers',!lineNumbers(c,n)));
  }
  function fold(direction,all=false) {
    const n=active();if(!n)return;const headings=n.document.cells.flatMap((c,i)=>M.heading(c)?[i]:[]);
    if(all){changed(n,()=>M.record(n,()=>headings.forEach(i=>setMeta(n.document.cells[i],'java_lab','heading_collapsed',direction<0))));return;}
    const c=n.document.cells[n.selected];if(!c)return;
    if(M.heading(c)&&((direction<0&&!c.metadata.java_lab?.heading_collapsed)||(direction>0&&c.metadata.java_lab?.heading_collapsed))) {
      metadata(cell=>{if(M.heading(cell))setMeta(cell,'java_lab','heading_collapsed',direction<0);});
    }else{const target=direction<0?headings.filter(i=>i<n.selected).at(-1):headings.find(i=>i>n.selected);if(target!==undefined){selected(n,target);command(n);}}
  }
  function syncLineNumbers(editor) {
    const c=active()?.document.cells[Number(editor.closest('[data-cell]')?.dataset.cell)];if(!c)return;
    const parent=editor.parentElement;parent.classList.toggle('with-line-numbers',lineNumbers(c));
    let gutter=$('.line-gutter',parent);
    if(lineNumbers(c)){if(!gutter){gutter=document.createElement('pre');gutter.className='line-gutter';gutter.setAttribute('aria-hidden','true');parent.prepend(gutter);}gutter.textContent=editor.value.split('\n').map((_,i)=>i+1).join('\n');}
    else gutter?.remove();
  }
  function decorate(n=active()) {
    if(!n)return;const s=nstate(n),hidden=M.hidden(n);
    $$('#cells .cell').forEach((element,i)=>{
      const c=n.document.cells[i];element.hidden=hidden.has(c.id);element.setAttribute('aria-label',`${c.cell_type} cell ${i+1}`);
      element.classList.toggle('side-by-side',!!c.metadata.java_lab?.side_by_side);
      const editor=$('textarea',element);if(editor){syncLineNumbers(editor);observeText(n,c,editor,true);}
    });
    if(hidden.has(n.document.cells[n.selected]?.id)){const above=n.document.cells.findLastIndex((c,i)=>i<n.selected&&!hidden.has(c.id));selected(n,Math.max(0,above));}
    paint(n);
  }
  function textState(n,c){const states=nstate(n).texts;if(!states.has(c.id))states.set(c.id,{observed:source(c),undo:[],redo:[],draft:null,last:0});return states.get(c.id);}
  function observeText(n,c,editor,render=false) {
    const s=textState(n,c),value=editor.value;if(value===s.observed)return;
    const before=s.draft||{value:s.observed,start:0,end:0},after={value,start:editor.selectionStart,end:editor.selectionEnd};
    const now=Date.now();
    if(!render&&s.undo.length&&now-s.last<600&&s.undo.at(-1).after.value===before.value&&s.undo.at(-1).after.end===before.start)s.undo.at(-1).after=after;
    else{s.undo.push({before,after});if(s.undo.length>100)s.undo.shift();}
    s.observed=value;s.draft=null;s.redo=[];s.last=render?0:now;
  }
  function writeText(editor,value,start=editor.selectionStart,end=start) {
    const n=active(),c=n?.document.cells[Number(editor.closest('[data-cell]').dataset.cell)];if(!c)return;
    const s=textState(n,c);s.last=0;s.draft={value:editor.value,start:editor.selectionStart,end:editor.selectionEnd};
    editor.value=value;editor.setSelectionRange(start,end);editor.dispatchEvent(new Event('input',{bubbles:true}));s.last=0;
  }
  function undoText(editor,redo=false) {
    const n=active(),c=n?.document.cells[Number(editor.closest('[data-cell]').dataset.cell)];if(!c)return;
    const s=textState(n,c),from=redo?s.redo:s.undo,to=redo?s.undo:s.redo,entry=from.pop();if(!entry)return;
    const target=redo?entry.after:entry.before;s.observed=target.value;s.draft=null;s.last=0;
    editor.value=target.value;editor.setSelectionRange(target.start,target.end);editor.dispatchEvent(new Event('input',{bubbles:true}));to.push(entry);
  }
  function lineRange(editor) {
    const start=editor.value.lastIndexOf('\n',editor.selectionStart-1)+1;
    const tail=editor.selectionEnd>editor.selectionStart&&editor.value[editor.selectionEnd-1]==='\n'?editor.selectionEnd-1:editor.selectionEnd;
    const found=editor.value.indexOf('\n',tail);return {start,end:found<0?editor.value.length:found};
  }
  function indent(editor,out=false) {
    if(!out&&editor.selectionStart===editor.selectionEnd){const p=editor.selectionStart;writeText(editor,editor.value.slice(0,p)+'    '+editor.value.slice(p),p+4);return;}
    const {start,end}=lineRange(editor),value=editor.value.slice(start,end),lines=value.split('\n');
    let removedFirst=0,total=0;
    const replacement=lines.map((line,i)=>{if(!out){total+=4;return '    '+line;}const count=line.match(/^(?: {1,4}|\t)/)?.[0].length||0;if(i===0)removedFirst=count;total+=count;return line.slice(count);}).join('\n');
    writeText(editor,editor.value.slice(0,start)+replacement+editor.value.slice(end),out?Math.max(start,editor.selectionStart-removedFirst):editor.selectionStart+4,out?Math.max(start,editor.selectionEnd-total):editor.selectionEnd+total);
  }
  function toggleComment(editor,block=false) {
    const n=active(),c=n.document.cells[n.selected],s=editor.selectionStart,e=editor.selectionEnd;
    if(block){const open=c.cell_type==='markdown'?'<!--':'/*',close=c.cell_type==='markdown'?'-->':'*/',value=editor.value.slice(s,e);if(value.startsWith(open)&&value.endsWith(close))writeText(editor,editor.value.slice(0,s)+value.slice(open.length,-close.length)+editor.value.slice(e),s,e-open.length-close.length);else if(editor.value.slice(s-open.length,s)===open&&editor.value.slice(e,e+close.length)===close)writeText(editor,editor.value.slice(0,s-open.length)+value+editor.value.slice(e+close.length),s-open.length,e-open.length);else writeText(editor,editor.value.slice(0,s)+open+value+close+editor.value.slice(e),s+open.length,e+open.length);return;}
    const range=lineRange(editor),lines=editor.value.slice(range.start,range.end).split('\n');
    if(c.cell_type==='markdown') {
      const value=lines.join('\n');const marked=value.startsWith('<!--')&&value.endsWith('-->');const result=marked?value.slice(4,-3):'<!--'+value+'-->';
      writeText(editor,editor.value.slice(0,range.start)+result+editor.value.slice(range.end),range.start,range.start+result.length);return;
    }
    const uncomment=lines.filter(line=>line.trim()).every(line=>/^\s*\/\//.test(line));
    const value=lines.map(line=>uncomment?line.replace(/^(\s*)\/\/ ?/,'$1'):line.replace(/^(\s*)/,'$1// ')).join('\n');
    writeText(editor,editor.value.slice(0,range.start)+value+editor.value.slice(range.end),range.start,range.start+value.length);
  }
  function bold(editor) {
    let s=editor.selectionStart,e=editor.selectionEnd,value=editor.value.slice(s,e);
    if(value.startsWith('**')&&value.endsWith('**')&&value.length>=4)writeText(editor,editor.value.slice(0,s)+value.slice(2,-2)+editor.value.slice(e),s,e-4);
    else if(editor.value.slice(s-2,s)==='**'&&editor.value.slice(e,e+2)==='**')writeText(editor,editor.value.slice(0,s-2)+value+editor.value.slice(e+2),s-2,e-2);
    else writeText(editor,editor.value.slice(0,s)+'**'+value+'**'+editor.value.slice(e),s+2,e+2);
  }
  function deleteLine(editor){const {start,end}=lineRange(editor);const next=end<editor.value.length?end+1:end;const first=start>0&&next===editor.value.length?start-1:start;writeText(editor,editor.value.slice(0,first)+editor.value.slice(next),first);}
  function nextOccurrence(editor) {
    let s=editor.selectionStart,e=editor.selectionEnd;
    if(s===e){const before=editor.value.slice(0,s).match(/[\w$]+$/)?.[0]||'',after=editor.value.slice(s).match(/^[\w$]+/)?.[0]||'';editor.setSelectionRange(s-before.length,s+after.length);return;}
    const needle=editor.value.slice(s,e);let next=editor.value.indexOf(needle,e);if(next<0)next=editor.value.indexOf(needle);if(next>=0)editor.setSelectionRange(next,next+needle.length);
  }
  function splitCell(){const n=active(),editor=$(`#cells [data-cell="${n?.selected}"] textarea`);if(editor)changed(n,()=>M.split(n,editor.selectionStart),true);}
  function history(editor,direction) {
    const n=active(),list=n.executionHistory||[];if(!list.length)return;
    if(!n.historyBrowse)n.historyBrowse={index:list.length,draft:editor.value};
    n.historyBrowse.index=Math.max(0,Math.min(n.historyBrowse.index+direction,list.length));
    const value=n.historyBrowse.index===list.length?n.historyBrowse.draft:list[n.historyBrowse.index];writeText(editor,value,value.length);
  }
  const commands={
    'insert-above':()=>insert(indexes()[0]??0),'insert-below':()=>insert((indexes().at(-1)??-1)+1),
    'heading-above':()=>insertHeading(-1),'heading-below':()=>insertHeading(1),
    'copy-cell':copy,'cut-cell':cut,'paste-cell':()=>paste(),'paste-above':()=>paste(true),
    'delete-cell':()=>changed(active(),()=>M.remove(active())),
    duplicate:()=>{const n=active();if(n)changed(n,()=>M.insert(n,indexes(n).at(-1)+1,M.pasted(M.copied(n))));},
    'undo-cell':()=>changed(active(),()=>M.undo(active())),'redo-cell':()=>changed(active(),()=>M.undo(active(),true)),
    'merge-cells':()=>changed(active(),()=>M.merge(active())),'merge-above':()=>changed(active(),()=>M.merge(active(),-1)),
    'merge-below':()=>changed(active(),()=>M.merge(active(),1)),'split-cell':splitCell,
    'move-up':()=>changed(active(),()=>M.move(active(),-1)),'move-down':()=>changed(active(),()=>M.move(active(),1)),
    'code-cell':()=>changeType('code'),'markdown-cell':()=>changeType('markdown'),'raw-cell':()=>changeType('raw'),
    'line-numbers':()=>toggleLines(),'all-line-numbers':()=>toggleLines(true),
    'toggle-outputs':()=>metadata(c=>setMeta(c,'jupyter','outputs_hidden',!c.metadata.jupyter?.outputs_hidden)),
    'scroll-outputs':()=>metadata(c=>{c.metadata.scrolled=!c.metadata.scrolled;}),
    'side-by-side':()=>metadata(c=>setMeta(c,'java_lab','side_by_side',!c.metadata.java_lab?.side_by_side)),
    'collapse-heading':()=>fold(-1),'expand-heading':()=>fold(1),'collapse-headings':()=>fold(-1,true),'expand-headings':()=>fold(1,true),
    'select-all':()=>{const n=active();if(n){selected(n,0);selected(n,n.document.cells.length-1,true);command(n);}},
    'clear-selected':()=>metadata(c=>M.clearResult(c)),
    clear:()=>{const n=active();changed(n,()=>M.record(n,()=>n.document.cells.forEach(M.clearResult)));},
    palette:()=>showPalette(),find:()=>showSearch(false),'find-replace':()=>showSearch(true),
    'find-next':()=>findMatch(1),'find-previous':()=>findMatch(-1),'save-as':()=>saveAs(),
    'simple-mode':()=>document.body.classList.toggle('simple-interface'),
    'next-tab':()=>switchTab(1),'previous-tab':()=>switchTab(-1),'reopen-tab':()=>{const last=closedTabs.pop();if(last)return openNotebook(last.name,last.folder);},
    'file-browser':()=>{const button=$('[data-panel="files"]');button.click();$('#file-search').focus();},
    print:()=>window.print()
  };
  const paletteCommands=[
    ['new','New Java notebook'],['launcher','Open launcher'],['save','Save notebook'],['save-as','Save notebook as…'],['close','Close notebook tab'],
    ['run','Run selected cells'],['run-next','Run selected cells and advance'],['run-all','Run all cells'],['interrupt','Interrupt kernel'],['restart','Restart kernel'],
    ['insert-above','Insert cell above'],['insert-below','Insert cell below'],['heading-above','Insert heading above'],['heading-below','Insert heading below'],
    ['cut-cell','Cut selected cells'],['copy-cell','Copy selected cells'],['paste-cell','Paste cells below'],['paste-above','Paste cells above'],['duplicate','Duplicate selected cells'],
    ['delete-cell','Delete selected cells'],['undo-cell','Undo cell action'],['redo-cell','Redo cell action'],['merge-cells','Merge selected cells'],['merge-above','Merge with cell above'],['merge-below','Merge with cell below'],['split-cell','Split cell at cursor'],
    ['move-up','Move cells up'],['move-down','Move cells down'],['code-cell','Change to Java code'],['markdown-cell','Change to Markdown'],['raw-cell','Change to raw'],['select-all','Select all cells'],
    ['toggle-outputs','Toggle selected outputs'],['scroll-outputs','Toggle output scrolling'],['clear-selected','Clear selected outputs'],['clear','Clear all outputs'],
    ['line-numbers','Toggle cell line numbers'],['all-line-numbers','Toggle all line numbers'],['side-by-side','Toggle cell side-by-side layout'],['collapse-headings','Collapse all headings'],['expand-headings','Expand all headings'],
    ['find','Find notebook source'],['find-replace','Find and replace'],['sidebar','Toggle sidebar'],['simple-mode','Toggle simple interface'],['export-html','Export HTML'],['download','Download notebook'],['print','Print notebook'],['vibecode','Vibecode selected cell'],['help','Keyboard shortcuts']
  ];
  async function perform(name){try{await (commands[name]||actions[name])?.();}catch(e){notify(e.message,true);}}
  function switchTab(direction){const list=state.notebooks;if(!list.length)return;const i=list.indexOf(state.active);state.active=list[(i+direction+list.length)%list.length];state.launcher=false;renderWorkspace();command();}
  async function saveAs(){const n=active();if(!n)return;const name=await modal('Save notebook as','Choose a filename for a copy of this notebook.','Save as',n.name);if(!name)return;const created=await api('/notebooks'+folderQuery(n.folder),'POST',{name});await api('/notebooks/'+encodeURIComponent(created.name)+folderQuery(n.folder),'PUT',notebookSnapshot(n.document));await refreshFiles();await openNotebook(created.name,n.folder);}
  function paletteItems(){const query=$('#palette-query').value.toLowerCase().split(/\s+/).filter(Boolean);return paletteCommands.filter(([,label])=>query.every(word=>label.toLowerCase().includes(word)));}
  function renderPalette(){const items=paletteItems();paletteIndex=Math.max(0,Math.min(paletteIndex,items.length-1));$('#palette-list').innerHTML=items.map(([name,label],i)=>`<button type="button" role="option" aria-selected="${i===paletteIndex}" class="palette-item ${i===paletteIndex?'active':''}" data-palette-command="${name}">${escapeHtml(label)}</button>`).join('')||'<div class="empty-files">No matching commands.</div>';$('.palette-item.active')?.scrollIntoView({block:'nearest'});}
  function showPalette(){resetSequence();paletteIndex=0;$('#palette-query').value='';renderPalette();$('#command-palette').showModal();$('#palette-query').focus();}
  $('#palette-query').addEventListener('input',()=>{paletteIndex=0;renderPalette();});
  $('#command-palette').addEventListener('keydown',event=>{if(['ArrowDown','ArrowUp','Enter'].includes(event.key)){event.preventDefault();if(event.key==='Enter'){const name=paletteItems()[paletteIndex]?.[0];$('#command-palette').close();if(name)perform(name);}else{paletteIndex+=(event.key==='ArrowDown'?1:-1);renderPalette();}}});
  $('#palette-list').addEventListener('click',event=>{const name=event.target.closest('[data-palette-command]')?.dataset.paletteCommand;if(name){$('#command-palette').close();perform(name);}});
  $('#palette-close').onclick=()=>$('#command-palette').close();
  let searchResults=[],searchIndex=-1;
  function showSearch(replace){if(!active())return;resetSequence();$('#notebook-search').hidden=false;$('#replace-row').hidden=!replace;$('#notebook-query').focus();$('#notebook-query').select();scanSearch();}
  function scanSearch(){const n=active(),query=$('#notebook-query').value.toLowerCase();searchResults=[];searchIndex=-1;if(n&&query)n.document.cells.forEach((c,i)=>{const value=source(c).toLowerCase();let at=0;while((at=value.indexOf(query,at))>=0){searchResults.push({id:c.id,index:i,start:at,end:at+query.length});at+=query.length;}});$('#search-count').textContent=`${searchResults.length} matches`;}
  function findMatch(direction=1){const n=active();if(!n)return;if($('#notebook-search').hidden){showSearch(false);return;}if(!searchResults.length)return;searchIndex=(searchIndex+direction+searchResults.length)%searchResults.length;const match=searchResults[searchIndex];selected(n,match.index);edit(n,match.start);const editor=$(`#cells [data-cell="${n.selected}"] textarea`);editor?.setSelectionRange(match.start,match.end);$('#search-count').textContent=`${searchIndex+1} / ${searchResults.length}`;}
  function replaceMatch(all=false){const n=active(),query=$('#notebook-query').value,replacement=$('#notebook-replacement').value;if(!n||n.running||!query)return;
    if(all){changed(n,()=>M.record(n,()=>n.document.cells.forEach(c=>{const escaped=query.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');const re=new RegExp(escaped,'gi');const value=source(c).replace(re,()=>replacement);if(value!==source(c)){c.source=[value];M.clearResult(c);}})));scanSearch();}
    else{if(searchIndex<0)findMatch(1);const match=searchResults[searchIndex];if(!match)return;const c=n.document.cells.find(c=>c.id===match.id);changed(n,()=>M.record(n,()=>{c.source=[source(c).slice(0,match.start)+replacement+source(c).slice(match.end)];M.clearResult(c);}));scanSearch();findMatch(1);}
  }
  $('#notebook-query').addEventListener('input',scanSearch);
  $('#notebook-query').addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();findMatch(event.shiftKey?-1:1);}else if(event.key==='Escape'){$('#notebook-search').hidden=true;command();}});
  $('#search-next').onclick=()=>findMatch(1);$('#search-previous').onclick=()=>findMatch(-1);$('#search-close').onclick=()=>{$('#notebook-search').hidden=true;command();};
  $('#replace-one').onclick=()=>replaceMatch();$('#replace-all').onclick=()=>replaceMatch(true);

  function consume(event){event.preventDefault();event.stopImmediatePropagation();}
  function commandScope(event){return !!active()&&!event.target.closest('input,textarea,select,button,a,[contenteditable="true"]')&&!!event.target.closest('#notebook');}
  function sequenceKey(event,key,action) {
    consume(event);if(event.repeat)return;const n=active(),current=n.document.cells[n.selected]?.id;
    if(sequence&&sequence.key===key&&sequence.uid===n.uid&&sequence.id===current&&Date.now()-sequence.time<1000){sequence=null;perform(action);}
    else sequence={key,uid:n.uid,id:current,time:Date.now()};
  }
  document.addEventListener('keydown',event=>{
    if(event.defaultPrevented||event.isComposing||$$('dialog[open]').length)return;
    if(event.target.closest?.('.lab-form'))return;
    const key=event.key.toLowerCase(),accel=event.ctrlKey||event.metaKey,editor=event.target.matches?.('#cells .cell-editor textarea')?event.target:null,n=active();
    if(sequence&&(accel||event.altKey||event.shiftKey||key!==sequence.key))resetSequence();
    if(event.key==='Escape'&&$('.menu.open')){consume(event);$$('.menu').forEach(menu=>menu.classList.remove('open'));if(n)command(n);return;}
    const file=event.target.closest?.('#file-list [data-file]');
    if(file) {
      if(event.key==='F2'){consume(event);renameNotebook(file.dataset.file,file.dataset.fileFolder).catch(e=>notify(e.message,true));return;}
      if(event.key==='Delete'){consume(event);deleteNotebook(file.dataset.file,file.dataset.fileFolder).catch(e=>notify(e.message,true));return;}
      if(accel&&key==='d'){consume(event);(async()=>{const folder=file.dataset.fileFolder || '';const document=await api('/notebooks/'+encodeURIComponent(file.dataset.file)+folderQuery(folder));const copy=await api('/notebooks'+folderQuery(folder),'POST',{name:file.dataset.file});await api('/notebooks/'+encodeURIComponent(copy.name)+folderQuery(folder),'PUT',document);await refreshFiles();notify('Notebook duplicated');})().catch(e=>notify(e.message,true));return;}
      if(['ArrowUp','ArrowDown'].includes(event.key)){consume(event);const files=$$('#file-list [data-file]'),i=files.indexOf(file),next=Math.max(0,Math.min(i+(event.key==='ArrowUp'?-1:1),files.length-1));files[next]?.focus();return;}
    }
    if(accel&&event.shiftKey&&(key==='c'||key==='p')){consume(event);showPalette();return;}
    if(accel&&event.shiftKey&&key==='h'){consume(event);perform('help');return;}
    if(accel&&key==='s'){consume(event);perform(event.shiftKey?'save-as':'save');return;}
    if(event.altKey&&!accel&&key==='n'){consume(event);perform('new');return;}
    if(n&&event.altKey&&key==='w'||n&&event.ctrlKey&&event.shiftKey&&key==='q'){consume(event);perform('close');return;}
    if(accel&&event.shiftKey&&event.code==='BracketRight'){consume(event);switchTab(1);return;}
    if(accel&&event.shiftKey&&event.code==='BracketLeft'){consume(event);switchTab(-1);return;}
    if(accel&&event.shiftKey&&key==='t'){consume(event);perform('reopen-tab');return;}
    if(accel&&event.shiftKey&&key==='f'||event.altKey&&!accel&&key==='1'){consume(event);perform('file-browser');return;}
    if(n&&accel&&key==='f'){consume(event);showSearch(false);return;}
    if(n&&accel&&key==='h'){consume(event);showSearch(true);return;}
    if(n&&accel&&key==='g'){consume(event);findMatch(event.shiftKey?-1:1);return;}
    if(n&&accel&&!event.shiftKey&&key==='p'){consume(event);perform('print');return;}
    if((!editor||n?.document.cells[n.selected]?.cell_type!=='markdown')&&accel&&key==='b'){consume(event);perform('sidebar');return;}
    if(!editor&&accel&&event.shiftKey&&key==='d'){consume(event);perform('simple-mode');return;}
    if(event.key==='Enter'&&event.target.matches?.('[data-tab]')){consume(event);event.target.click();return;}
    if(!n)return;
    if((editor||commandScope(event))&&event.key==='Enter'&&(accel||event.shiftKey||event.altKey)) {
      consume(event);resetSequence();runSelected(!accel,!!event.altKey).catch(e=>notify(e.message,true));return;
    }
    if(editor) {
      if(event.key==='Escape'||event.ctrlKey&&key==='m'){consume(event);command(n);return;}
      if(accel&&key==='z'||event.ctrlKey&&key==='y'){consume(event);undoText(editor,event.shiftKey||key==='y');return;}
      if(event.ctrlKey&&event.shiftKey&&event.code==='Minus'){consume(event);splitCell();return;}
      if(accel&&event.code==='BracketLeft'){consume(event);indent(editor,true);return;}
      if(accel&&event.code==='BracketRight'){consume(event);indent(editor);return;}
      if(accel&&event.code==='Slash'){consume(event);toggleComment(editor);return;}
      if(event.altKey&&key==='a'){consume(event);toggleComment(editor,true);return;}
      if(accel&&key==='d'){consume(event);if(event.shiftKey)nextOccurrence(editor);else deleteLine(editor);return;}
      if(accel&&event.shiftKey&&key==='k'){consume(event);deleteLine(editor);return;}
      if(accel&&key==='b'&&n.document.cells[n.selected].cell_type==='markdown'){consume(event);bold(editor);return;}
      if(event.altKey&&['ArrowUp','ArrowDown'].includes(event.key)){consume(event);history(editor,event.key==='ArrowUp'?-1:1);return;}
      if(event.key==='Tab') {
        consume(event);const start=editor.selectionStart,before=editor.value.slice(editor.value.lastIndexOf('\n',start-1)+1,start);
        if(event.shiftKey){if(start===editor.selectionEnd&&before.trim()&&n.document.cells[n.selected].cell_type==='code')window.JavaEditor?.inspect(editor);else indent(editor,true);}
        else if(start===editor.selectionEnd&&before.trim()&&n.document.cells[n.selected].cell_type==='code'&&!n.running)window.JavaEditor?.request(editor,true,()=>indent(editor));
        else indent(editor);return;
      }
      if(!accel&&!event.shiftKey&&!event.altKey&&editor.selectionStart===editor.selectionEnd&&['ArrowUp','ArrowDown'].includes(event.key)) {
        const before=editor.value.slice(0,editor.selectionStart),after=editor.value.slice(editor.selectionEnd);
        if(event.key==='ArrowUp'&&!before.includes('\n')&&n.selected>0){consume(event);selected(n,n.selected-1);edit(n,source(n.document.cells[n.selected]).length);}
        else if(event.key==='ArrowDown'&&!after.includes('\n')&&n.selected<n.document.cells.length-1){consume(event);selected(n,n.selected+1);edit(n,0);}
      }
      return;
    }
    if(!commandScope(event))return;
    if(event.key==='Escape'){consume(event);selected(n,n.selected);command(n);return;}
    if(event.key==='Enter'){consume(event);edit(n);return;}
    if(event.ctrlKey&&event.shiftKey&&['ArrowUp','ArrowDown','ArrowLeft','ArrowRight'].includes(event.key)){consume(event);perform({'ArrowUp':'move-up','ArrowDown':'move-down','ArrowLeft':'collapse-headings','ArrowRight':'expand-headings'}[event.key]);return;}
    if(event.ctrlKey&&event.key==='Backspace'){consume(event);perform('merge-above');return;}
    if(event.ctrlKey&&event.shiftKey&&key==='m'){consume(event);perform('merge-below');return;}
    if(accel&&key==='a'){consume(event);perform('select-all');return;}
    if(accel&&['c','x','v','z','y'].includes(key)){consume(event);perform({c:'copy-cell',x:'cut-cell',v:event.shiftKey?'paste-above':'paste-cell',z:event.shiftKey?'redo-cell':'undo-cell',y:'redo-cell'}[key]);return;}
    if(accel||event.altKey)return;
    if(['ArrowUp','ArrowDown','Home','End'].includes(event.key)||key==='j'||key==='k') {
      consume(event);moveSelection(event.key==='ArrowUp'||key==='k'?-1:1,event.shiftKey,event.key==='Home'?'first':event.key==='End'?'last':null);return;
    }
    if(event.key==='ArrowLeft'||event.key==='ArrowRight'){consume(event);fold(event.key==='ArrowLeft'?-1:1);return;}
    if(!event.shiftKey&&['d','i','0'].includes(key)){sequenceKey(event,key,{d:'delete-cell',i:'interrupt','0':'restart'}[key]);return;}
    resetSequence();
    if(/^[1-6]$/.test(key)){consume(event);changeType('markdown',Number(key));return;}
    const action=event.shiftKey?{a:'heading-above',b:'heading-below',m:'merge-cells',v:'paste-above',z:'redo-cell',l:'all-line-numbers',o:'scroll-outputs',r:'side-by-side'}[key]
      :{a:'insert-above',b:'insert-below',y:'code-cell',m:'markdown-cell',r:'raw-cell',t:'raw-cell',x:'cut-cell',c:'copy-cell',v:'paste-cell',z:'undo-cell',l:'line-numbers',o:'toggle-outputs',s:'save',h:'help'}[key];
    if(action||event.key==='?'){consume(event);perform(action||'help');}
  },true);
  $('#cells').addEventListener('beforeinput',event=>{if(event.target.matches('.cell-editor textarea')){const n=active(),c=n.document.cells[Number(event.target.closest('[data-cell]').dataset.cell)];textState(n,c).draft={value:event.target.value,start:event.target.selectionStart,end:event.target.selectionEnd};}});
  document.addEventListener('pointerdown',resetSequence);
  $('#cells').addEventListener('input',event=>{if(event.target.matches('.cell-editor textarea')){const n=active(),c=n.document.cells[Number(event.target.closest('[data-cell]').dataset.cell)];observeText(n,c,event.target);syncLineNumbers(event.target);}});
  Object.assign(actions,commands);
  return {shortcuts,indexes,selected,command,edit,paint,decorate,insert,changeType,perform,resetSequence,
    closed:n=>{closedTabs.push({name:n.name,folder:n.folder});if(closedTabs.length>20)closedTabs.shift();},
    restoreFocus:()=>{const n=active();if(n)nstate(n).mode==='edit'?edit(n):focusCommand(n);},
    clicked:(event,index)=>{const n=active();if(!n)return;const s=nstate(n);selected(n,index,event.shiftKey&&!event.target.matches('textarea'),false);if(event.target.matches('textarea')){s.mode='edit';paint(n);updateStatus();}else if(event.target.closest('input,button,select,a,.echarts-chart,.grid-table'))command(n,false);else command(n);},
    setEditing:()=>{const n=active();if(n){nstate(n).mode='edit';paint(n);}},
    cellAction:name=>{const action={up:'move-up',down:'move-down','heading':'collapse-heading'}[name];if(action){perform(action);return true;}return false;}
  };
})();
