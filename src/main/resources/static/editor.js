/* Session-aware Java completion, layered onto the native cell editor. */
window.JavaEditor = (() => {
  let timer, generation = 0, menu = null, completion = null, inspection = null;

  function dismiss() {
    clearTimeout(timer); generation++;
    if (completion?.editor) {
      completion.editor.removeAttribute('aria-controls');
      completion.editor.removeAttribute('aria-activedescendant');
      completion.editor.setAttribute('aria-expanded','false');
    }
    menu?.remove(); menu=null; completion=null;
    inspection?.remove();inspection=null;
  }

  function caretBounds(editor) {
    const style = getComputedStyle(editor);
    const bounds = editor.getBoundingClientRect();
    const mirror = document.createElement('div');
    Object.assign(mirror.style, {position:'fixed',visibility:'hidden',whiteSpace:'pre',top:bounds.top+'px',left:bounds.left+'px'});
    for (const key of ['fontFamily','fontSize','fontWeight','lineHeight','letterSpacing','padding','tabSize']) mirror.style[key]=style[key];
    mirror.textContent=editor.value.slice(0,editor.selectionStart);
    const marker=document.createElement('span');marker.textContent='|';mirror.append(marker);document.body.append(mirror);
    const caret=marker.getBoundingClientRect();
    const result={left:caret.left-editor.scrollLeft,bottom:caret.bottom-editor.scrollTop,top:caret.top-editor.scrollTop};
    mirror.remove();return result;
  }

  function render() {
    if (!completion) return;
    if (!menu) {
      menu=document.createElement('div');menu.className='completion-popup';
      menu.addEventListener('mousedown',event=>event.preventDefault());
      menu.addEventListener('click',event=>{const option=event.target.closest('[data-suggestion]');if(option)accept(Number(option.dataset.suggestion));});
      document.body.append(menu);
    }
    const {items, selected, editor}=completion;
    menu.innerHTML='<div class="completion-header">JAVA · SESSION COMPLETION</div><div class="completion-items" id="java-completions" role="listbox" aria-label="Java completions">'+(items.length ? items.map((item,index)=>`<button id="java-suggestion-${index}" role="option" aria-selected="${index===selected}" class="completion-option ${index===selected?'active':''}" data-suggestion="${index}" tabindex="-1"><span class="completion-kind">${item.text.includes('(')?'m':/^[A-Z]/.test(item.text)?'C':'v'}</span><code>${escapeHtml(item.text)}</code>${item.matchesType?'<small>match</small>':''}</button>`).join('') : '<div class="completion-empty">No suggestions at this position.</div>')+'</div><div class="completion-footer">↑ ↓ navigate · Enter / Tab accept · Esc dismiss</div>';
    editor.setAttribute('aria-controls','java-completions');editor.setAttribute('aria-expanded','true');editor.setAttribute('aria-autocomplete','list');
    if(items.length)editor.setAttribute('aria-activedescendant',`java-suggestion-${selected}`);
    const caret=caretBounds(editor);
    const height=menu.offsetHeight;
    menu.style.left=Math.max(10,Math.min(caret.left,innerWidth-menu.offsetWidth-10))+'px';
    menu.style.top=Math.max(10,caret.bottom+height+10<innerHeight?caret.bottom+5:caret.top-height-5)+'px';
    $('.completion-option.active',menu)?.scrollIntoView({block:'nearest'});
  }

  async function request(editor, manual = false, fallback = null) {
    if (!editor.isConnected || !editor.closest('.code-editor') || editor.selectionStart!==editor.selectionEnd) return;
    const n=active();if(!n || n.running)return;
    const value=editor.value, cursor=editor.selectionStart, kernel=n.kernel;
    dismiss();const version=generation;
    try {
      const result=await api(`/kernels/${kernel}/complete`,'POST',{code:value,cursor});
      if(version!==generation || document.activeElement!==editor || editor.value!==value || editor.selectionStart!==cursor || n!==active())return;
      if(!result.suggestions.length&&fallback){fallback();return;}
      if(!result.suggestions.length && !manual)return;
      if(result.anchor<0 || result.anchor>cursor)return;
      completion={editor,value,cursor,anchor:result.anchor,items:result.suggestions,selected:0};render();
    }catch(error){if(manual && version===generation)notify(error.message,true);}
  }

  async function inspect(editor) {
    const n=active();if(!n||n.running)return;
    const value=editor.value,cursor=editor.selectionStart;dismiss();const version=generation;
    try {
      const result=await api(`/kernels/${n.kernel}/inspect`,'POST',{code:value,cursor});
      if(version!==generation||document.activeElement!==editor||editor.value!==value||editor.selectionStart!==cursor)return;
      inspection=document.createElement('div');inspection.className='inspection-tooltip';inspection.setAttribute('role','tooltip');
      const content=document.createElement('pre');content.textContent=result.signatures.length?result.signatures.join('\n\n'):'No signature available at this position.';
      inspection.append(content);document.body.append(inspection);
      const caret=caretBounds(editor);inspection.style.left=Math.max(10,Math.min(caret.left,innerWidth-inspection.offsetWidth-10))+'px';
      inspection.style.top=Math.max(10,caret.bottom+inspection.offsetHeight+10<innerHeight?caret.bottom+5:caret.top-inspection.offsetHeight-5)+'px';
    }catch(error){if(version===generation)notify(error.message,true);}
  }

  function accept(index) {
    const current=completion;
    if(!current || !current.items[index])return;
    const {editor,value,cursor,anchor}=current;
    if(editor.value!==value || editor.selectionStart!==cursor){dismiss();return;}
    let text=current.items[index].text;
    let caret=anchor+text.length;
    if(text.endsWith('(')) {
      if(editor.value[cursor]==='(') {text=text.slice(0,-1);caret=anchor+text.length+1;}
      else {text+=')';caret=anchor+text.length-1;}
    }
    dismiss();editor.focus();editor.setRangeText(text,anchor,cursor,'end');editor.setSelectionRange(caret,caret);
    editor.dispatchEvent(new Event('input',{bubbles:true}));
    clearTimeout(timer); // Accepting a suggestion does not immediately reopen it.
  }

  $('#cells').addEventListener('input',event=>{
    const editor=event.target;if(!editor.matches('.code-editor textarea'))return;
    dismiss();if(event.isComposing)return;
    const before=editor.value.slice(0,editor.selectionStart);
    if (/(?:\.|[a-zA-Z_$][\w$]{1,})$/.test(before)) timer=setTimeout(()=>request(editor),300);
  });
  document.addEventListener('keydown',event=>{
    const editor=event.target;
    if(!editor.matches?.('.code-editor textarea'))return;
    if(inspection&&event.key==='Escape'){event.preventDefault();event.stopImmediatePropagation();dismiss();return;}
    if((event.ctrlKey||event.metaKey) && event.code==='Space'){
      event.preventDefault();event.stopImmediatePropagation();request(editor,true);return;
    }
    if(!completion || completion.editor!==editor)return;
    if(event.key==='Tab'&&event.shiftKey){event.preventDefault();event.stopImmediatePropagation();if(completion.items.length){completion.selected=(completion.selected-1+completion.items.length)%completion.items.length;render();}else dismiss();return;}
    if(event.key==='Escape') {event.preventDefault();event.stopImmediatePropagation();dismiss();return;}
    if(!event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey && ['ArrowDown','ArrowUp','Enter','Tab'].includes(event.key)) {
      if (!completion.items.length) {dismiss();return;}
      event.preventDefault();event.stopImmediatePropagation();
      if(event.key==='Enter'||event.key==='Tab')accept(completion.selected);
      else {completion.selected=(completion.selected+(event.key==='ArrowDown'?1:-1)+completion.items.length)%completion.items.length;render();}
    }else if(['ArrowLeft','ArrowRight','Home','End','PageUp','PageDown'].includes(event.key)||event.ctrlKey||event.metaKey||event.altKey) dismiss();
  },true);
  document.addEventListener('mousedown',event=>{if(!event.target.closest('.completion-popup,.inspection-tooltip'))dismiss();});
  document.addEventListener('focusin',event=>{if(completion && event.target!==completion.editor)dismiss();});
  $('#notebook-scroll').addEventListener('scroll',dismiss);
  window.addEventListener('resize',dismiss);
  return {dismiss,request,inspect};
})();
