/* Shared, offline rendering for the editor, Markdown preview, and HTML export. */
window.LabPresentation = (() => {
  const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const keywords = new Set(('abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while var record sealed permits non-sealed yield true false null').split(' '));
  const tokenPattern = /\/\/[^\n]*|\/\*[\s\S]*?(?:\*\/|$)|"""[\s\S]*?(?:"""|$)|"(?:\\[\s\S]|[^"\\\n])*(?:"|$)|'(?:\\.|[^'\\\n])*(?:'|$)|@[\w$.]+|\b(?:0[xX][\da-fA-F_]+|0[bB][01_]+|\d[\d_]*(?:\.[\d_]*)?(?:[eE][+-]?[\d_]+)?)[fFdDlL]?\b|[a-zA-Z_$][\w$]*|[{}()[\];,.]|[+\-*/%=!<>?:&|^~]+/g;

  function highlightJava(text) {
    let html = '', end = 0;
    for (const match of text.matchAll(tokenPattern)) {
      const token = match[0];
      html += escape(text.slice(end, match.index));
      const kind = token.startsWith('//') || token.startsWith('/*') ? 'comment'
        : token.startsWith('"') || token.startsWith("'") ? 'string'
        : token.startsWith('@') ? 'annotation'
        : /^\d/.test(token) ? 'number'
        : keywords.has(token) ? 'keyword'
        : /^[A-Z][\w$]*$/.test(token) ? 'type'
        : /^[a-z_$][\w$]*$/.test(token) && /^\s*\(/.test(text.slice(match.index + token.length)) ? 'method'
        : /^[{}()[\];,.]+$/.test(token) ? 'punctuation' : 'plain';
      html += kind === 'plain' ? escape(token) : `<span class="syntax-${kind}">${escape(token)}</span>`;
      end = match.index + token.length;
    }
    return html + escape(text.slice(end));
  }

  function workspaceFilePath(value){
    let path;try{path=decodeURIComponent(value);}catch(_){return null;}
    if(!path||path.startsWith('/')||path.includes('\\')||path.includes(':')||/[\x00-\x1f\x7f]/.test(path))return null;
    const segments=path.split('/').filter(s=>s!=='.');if(segments.some(s=>!s||s==='..'||s.startsWith('.')))return null;
    return segments.length?segments.join('/'):null;
  }
  function workspaceImagePath(value){const path=workspaceFilePath(value);return path&&/\.(png|jpe?g|gif|bmp|webp|svg)$/i.test(path)?path:null;}
  const headingSlug=value=>value.normalize('NFKD').replace(/[\u0300-\u036f]/g,'').replace(/[`*_]/g,'').toLowerCase().replace(/[^\p{L}\p{N}]+/gu,'-').replace(/^-|-$/g,'')||'section';
  function linkHtml(label,target,title,options){
    const caption=escape(label),description=title?` title="${escape(title)}"`:'';
    if(/^https?:\/\//i.test(target)&&!/[\x00-\x20\x7f\\]/.test(target))return `<a href="${escape(target)}" target="_blank" rel="noopener noreferrer"${description}>${caption}</a>`;
    if(/^mailto:[^\s]+$/i.test(target)&&!/[\x00-\x1f\x7f]/.test(target))return `<a href="${escape(target)}"${description}>${caption}</a>`;
    if(target.startsWith('#')){
      let fragment;try{fragment=decodeURIComponent(target.slice(1));}catch(_){return null;}
      const slug=headingSlug(fragment);return `<a href="#md-${escape(encodeURIComponent(slug))}" data-md-anchor-link="${escape(slug)}"${description}>${caption}</a>`;
    }
    const path=workspaceFilePath(target);if(!path)return null;
    const href=options.standalone?path.split('/').map(encodeURIComponent).join('/'):'api/files/download?path='+encodeURIComponent(path);
    return `<a href="${escape(href)}"${options.standalone?'':` data-workspace-link="${escape(path)}"`}${description}>${caption}</a>`;
  }
  function inline(text,options={}) {
    const pattern = /`([^`]+)`|!\[([^\]]*)\]\((?:<([^>\r\n]+)>|((?:\\.|[^\s()]|\((?:\\.|[^\s()])*\))+))\)|\[([^\]]+)\]\((?:<([^>\r\n]+)>|((?:\\.|[^\s()]|\((?:\\.|[^\s()])*\))+))(?:\s+"([^"\r\n]*)")?\)|\*\*([^*]+)\*\*|__([^_]+)__|\*([^*]+)\*|_([^_]+)_/g;
    let html = '', end = 0;
    for (const m of text.matchAll(pattern)) {
      html += escape(text.slice(end, m.index));
      if (m[1] !== undefined) html += `<code>${escape(m[1])}</code>`;
      else if(m[2]!==undefined){
        const path=workspaceImagePath((m[3]??m[4]).replace(/\\([()])/g,'$1'));
        if(!path)html+=escape(m[0]);
        else{
          options.collect?.(path);
          const embedded=options.imageSources?.[path];
          if(options.standalone&&!embedded)throw new Error('Markdown image asset is missing: '+path);
          const source=embedded||'api/files/image?path='+encodeURIComponent(path);
          if(embedded&&!/^data:image\/(?:png|jpeg|gif|bmp|webp|svg\+xml);base64,[A-Za-z0-9+/]+=*$/.test(embedded))throw new Error('Invalid embedded image asset');
          html+=`<img class="workspace-image" style="max-width:100%;height:auto" src="${escape(source)}" alt="${escape(m[2])}" data-image-path="${escape(path)}"${options.standalone?'':' loading="lazy"'}>`;
        }
      }
      else if (m[5] !== undefined) html += linkHtml(m[5],(m[6]??m[7]).replace(/\\([()])/g,'$1'),m[8],options)||escape(m[0]);
      else if (m[9] !== undefined || m[10] !== undefined) html += `<strong>${escape(m[9] ?? m[10])}</strong>`;
      else html += `<em>${escape(m[11] ?? m[12])}</em>`;
      end = m.index + m[0].length;
    }
    return html + escape(text.slice(end));
  }

  function markdown(text,options={}) {
    options.headingCounts ||= new Map();
    const lines = text.replace(/\r\n?/g, '\n').split('\n');
    const html = [];
    const startsBlock = line => /^\s*$|^\s*(```|~~~)|^#{1,6}\s|^\s*([-*+] |\d+\. )|^> ?|^\s*(---+|\*\*\*+)\s*$/.test(line);
    const tableCells = line => line.trim().replace(/^\||\|$/g, '').split('|').map(s => s.trim());
    for (let i = 0; i < lines.length;) {
      const line = lines[i];
      if (!line.trim()) { i++; continue; }
      const fence = line.match(/^\s*(```|~~~)\s*([\w-]*)/);
      if (fence) {
        const code = []; i++;
        while (i < lines.length && !lines[i].trim().startsWith(fence[1])) code.push(lines[i++]);
        if (i < lines.length) i++;
        const value = code.join('\n');
        html.push(`<pre class="markdown-code"><code>${['java',''].includes(fence[2]) ? highlightJava(value) : escape(value)}</code></pre>`);
        continue;
      }
      const heading = line.match(/^(#{1,6})\s+(.+?)\s*#*$/);
      if (heading) {
        const base=headingSlug(heading[2]),count=options.headingCounts.get(base)||0;options.headingCounts.set(base,count+1);
        const slug=base+(count?'-'+count:'');options.collectHeading?.(slug);
        html.push(`<h${heading[1].length} id="md-${escape(slug)}" data-md-anchor="${escape(slug)}">${inline(heading[2],options)}</h${heading[1].length}>`); i++; continue;
      }
      if (/^\s*(---+|\*\*\*+)\s*$/.test(line)) { html.push('<hr>'); i++; continue; }
      if (/^> ?/.test(line)) {
        const quote = []; while (i < lines.length && /^> ?/.test(lines[i])) quote.push(lines[i++].replace(/^> ?/,''));
        html.push(`<blockquote>${markdown(quote.join('\n'),options)}</blockquote>`); continue;
      }
      const list = line.match(/^\s*([-*+]|\d+\.)\s+(.+)/);
      if (list) {
        const ordered = /\d/.test(list[1]); const items = [];
        while (i < lines.length) {
          const item = lines[i].match(ordered ? /^\s*\d+\.\s+(.+)/ : /^\s*[-*+]\s+(.+)/);
          if (!item) break;
          items.push(`<li>${inline(item[1],options)}</li>`); i++;
        }
        const tag = ordered ? 'ol' : 'ul';
        html.push(`<${tag}>${items.join('')}</${tag}>`); continue;
      }
      if (line.includes('|') && i + 1 < lines.length && /^\s*\|?\s*:?-{3,}/.test(lines[i+1]) && tableCells(lines[i+1]).every(c => /^:?-{3,}:?$/.test(c))) {
        const headers = tableCells(line); i += 2; const rows = [];
        while (i < lines.length && lines[i].includes('|') && lines[i].trim()) rows.push(`<tr>${tableCells(lines[i++]).map(c=>`<td>${inline(c,options)}</td>`).join('')}</tr>`);
        html.push(`<div class="table-scroll"><table><thead><tr>${headers.map(c=>`<th>${inline(c,options)}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table></div>`); continue;
      }
      const paragraph = [line]; i++;
      while (i < lines.length && !startsBlock(lines[i])) {
        if (lines[i].includes('|') && i+1 < lines.length && /^\s*\|?\s*:?-{3,}/.test(lines[i+1])) break;
        paragraph.push(lines[i++]);
      }
      html.push(`<p>${paragraph.map(line=>inline(line,options)).join('<br>')}</p>`);
    }
    return html.join('\n');
  }

  function imageHtml(output) {
    const mime=['image/png','image/jpeg','image/gif','image/bmp','image/webp','image/svg+xml'].find(type=>output.data?.[type]);
    if(!mime)return '';
    const value = output.data[mime];
    const encoded = (Array.isArray(value) ? value.join('') : typeof value === 'string' ? value : '').replace(/\s/g,'');
    // Only embedded image bytes, never notebook-controlled URLs or markup.
    if (!/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)) return '';
    const signatures={'image/png':['iVBORw0KGgo'],'image/jpeg':['/9j/'],'image/gif':['R0lGODdh','R0lGODlh'],'image/bmp':['Qk']};
    if(signatures[mime]&&!signatures[mime].some(prefix=>encoded.startsWith(prefix)))return '';
    if(mime==='image/webp'){
      try{if(typeof atob!=='function')return '';const bytes=atob(encoded);if(bytes.length<12||bytes.slice(0,4)!=='RIFF'||bytes.slice(8,12)!=='WEBP')return '';}
      catch(_){return '';}
    }
    const title = output.data?.['text/plain'];
    const alt = Array.isArray(title) ? title.join('') : title || 'Java chart';
    const size = output.metadata?.[mime] || {};
    const width = Number.isInteger(size.width) && size.width >= 1 && size.width <= 10000 ? size.width : 900;
    const height = Number.isInteger(size.height) && size.height >= 1 && size.height <= 10000 ? size.height : 520;
    return `<img class="chart-image" width="${width}" height="${height}"${mime==='image/svg+xml'?' style="max-width:100%;height:auto"':''} src="data:${mime};base64,${encoded}" alt="${escape(alt)}">`;
  }

  const echartsMime = 'application/vnd.apache.echarts+json';
  function echartsHtml(output) {
    const option = output.data?.[echartsMime];
    if (!option || typeof option !== 'object' || Array.isArray(option)) return '';
    const size = output.metadata?.[echartsMime] || {};
    const width = Number.isInteger(size.width) && size.width >= 100 && size.width <= 2400 ? size.width : 900;
    const height = Number.isInteger(size.height) && size.height >= 100 && size.height <= 2400 ? size.height : 520;
    const title = output.data?.['text/plain'] || 'Apache ECharts';
    return `<div class="echarts-chart" role="img" aria-label="${escape(title)}" style="height:${height}px;max-width:${width}px" data-echarts="${escape(JSON.stringify(option))}"></div>`;
  }

  const gridMime = 'application/vnd.gridjs+json';
  function gridHtml(output) {
    const option=output.data?.[gridMime];
    if(!option||typeof option!=='object'||!Array.isArray(option.columns)||!Array.isArray(option.data))return '';
    const size=output.metadata?.[gridMime]||{};
    const width=Number.isInteger(size.width)&&size.width>=100&&size.width<=2400?size.width:1000;
    const title=option.title||output.data?.['text/plain']||'Grid.js table';
    return `<section class="grid-output" style="max-width:${width}px"><h3 class="grid-title">${escape(title)}</h3><div class="grid-table" aria-label="${escape(title)}" data-grid="${escape(JSON.stringify(option))}"></div></section>`;
  }

  const codeMime = 'application/vnd.java-lab.code+json';
  const formMime = 'application/vnd.java-lab.form+json';
  const codeFormats = new Set(['json','yaml','xml','html','css','javascript','java','sql','markdown','csv','text']);
  const formTypes = new Set(['text','textarea','number','checkbox','select','email','date','multiselect']);
  // The tree is deliberately bounded: large or malformed documents remain readable as code.
  const treeLimit = 1000, treeDepth = 32;
  function jsonTree(value) {
    let count = 0;
    function node(item, label, depth) {
      if (++count > treeLimit || depth > treeDepth) throw new Error('Tree too large');
      const prefix = label === null ? '' : `<span class="output-tree-key">${escape(label)}</span>: `;
      if (item && typeof item === 'object') {
        const entries = Object.entries(item);
        const type = Array.isArray(item) ? 'Array' : 'Object';
        return `<details class="output-tree-node" ${depth === 0 ? 'open' : ''}><summary>${prefix}<span class="output-tree-type">${type} (${entries.length})</span></summary><div class="output-tree-children">${entries.map(([key, child]) => node(child, key, depth + 1)).join('')}</div></details>`;
      }
      return `<div class="output-tree-leaf">${prefix}<span class="output-tree-value">${escape(JSON.stringify(item))}</span></div>`;
    }
    return node(value, null, 0);
  }
  function xmlTree(source) {
    if (typeof DOMParser === 'undefined' || /<!\s*(?:DOCTYPE|ENTITY)\b/i.test(source)) return '';
    const doc = new DOMParser().parseFromString(source, 'application/xml');
    if (doc.querySelector('parsererror')) return '';
    let count = 0;
    function node(item, depth) {
      if (++count > treeLimit || depth > treeDepth) throw new Error('Tree too large');
      if (item.nodeType === 3 || item.nodeType === 4) {
        const value = item.nodeValue.trim();
        return value ? `<div class="output-tree-leaf output-tree-value">${escape(value)}</div>` : '';
      }
      if (item.nodeType !== 1) return '';
      const attributes = Array.from(item.attributes).map(attr => ` <span class="output-tree-key">${escape(attr.name)}</span>=<span class="output-tree-value">&quot;${escape(attr.value)}&quot;</span>`).join('');
      const children = Array.from(item.childNodes).map(child => node(child, depth + 1)).join('');
      return `<details class="output-tree-node" ${depth === 0 ? 'open' : ''}><summary>&lt;<span class="output-tree-key">${escape(item.tagName)}</span>${attributes}&gt;</summary><div class="output-tree-children">${children}</div></details>`;
    }
    try { return node(doc.documentElement, 0); } catch (_) { return ''; }
  }
  // This handler contains no notebook data. It also runs in standalone HTML exports.
  function installOutputActions() {
    document.addEventListener('click', async function(event) {
    const button = event.target.closest('[data-output-action]');
    if (!button) return;
    const container = button.closest('.formatted-output');
    const content = container && container.querySelector('.formatted-source');
    if (!content) return;
    const value = content.textContent;
    const format = container.getAttribute('data-output-format');
    if (button.dataset.outputAction === 'copy') {
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) await navigator.clipboard.writeText(value);
        else {
          const input = document.createElement('textarea');
          input.value = value; input.style.position = 'fixed'; input.style.opacity = '0';
          document.body.appendChild(input); input.select();
          const copied = document.execCommand('copy'); input.remove();
          if (!copied) throw new Error('Copy unavailable');
        }
        button.textContent = 'Copied';
      }
      catch (_) { button.textContent = 'Copy failed'; }
      setTimeout(function() { button.textContent = 'Copy'; }, 2000);
    } else if (button.dataset.outputAction === 'download') {
      const extension = {javascript:'js',markdown:'md',text:'txt'}[format] || format;
      const url = URL.createObjectURL(new Blob([value], {type:'text/plain;charset=utf-8'}));
      const link = document.createElement('a');
      link.href = url; link.download = 'output.' + extension; link.style.display = 'none';
      document.body.appendChild(link); link.click(); link.remove();
      setTimeout(function() { URL.revokeObjectURL(url); }, 1000);
    }
    });
  }
  if (typeof document !== 'undefined') installOutputActions();
  function codeHtml(output) {
    const payload = output.data?.[codeMime];
    if (!payload || !codeFormats.has(payload.format) || typeof payload.content !== 'string') return '';
    let content = payload.content;
    if (payload.format === 'json') { try { content = JSON.stringify(JSON.parse(content), null, 2); } catch (_) { /* preserve invalid source */ } }
    const token = payload.format === 'json' ? /"(?:\\.|[^"\\])*"(?=\s*:)|"(?:\\.|[^"\\])*"|\b(?:true|false|null|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\b/g
      : ['xml','html'].includes(payload.format) ? /<\/?[A-Za-z][^>]*>|<!--[\s\S]*?-->/g
      : ['yaml','sql','javascript','java','css'].includes(payload.format) ? /#[^\n]*|\/\/[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b\d+(?:\.\d+)?\b/g : null;
    let highlighted = '', end = 0;
    if (token) for (const match of content.matchAll(token)) {
      highlighted += escape(content.slice(end, match.index));
      const value = match[0];
      const kind = value.startsWith('#') || value.startsWith('//') || value.startsWith('<!--') ? 'comment' : /^[-\d]/.test(value) || /^(true|false|null)$/.test(value) ? 'number' : value.startsWith('<') ? 'keyword' : 'string';
      highlighted += `<span class="syntax-${kind}">${escape(value)}</span>`;
      end = match.index + value.length;
    }
    highlighted += escape(content.slice(end));
    let tree = '';
    if (payload.format === 'json') {
      try { tree = jsonTree(JSON.parse(content)); } catch (_) { /* code remains available */ }
    } else if (payload.format === 'xml') tree = xmlTree(content);
    const explorer = tree ? `<div class="structured-explorer" aria-label="${escape(payload.format.toUpperCase())} structure">${tree}</div>` : '';
    const source = `<pre class="formatted-source"><code>${highlighted}</code></pre>`;
    return `<section class="formatted-output" data-output-format="${escape(payload.format)}"><div class="formatted-heading"><span>${escape(payload.format.toUpperCase())}</span><span class="formatted-actions"><button type="button" data-output-action="copy" aria-label="Copy ${escape(payload.format.toUpperCase())} output">Copy</button><button type="button" data-output-action="download" aria-label="Download ${escape(payload.format.toUpperCase())} output">Download</button></span></div>${explorer}${tree ? `<details class="formatted-code"><summary>Source</summary>${source}</details>` : source}</section>`;
  }
  function formHtml(output, values = {}, disabled = false) {
    const form = output.data?.[formMime];
    if (!form || typeof form !== 'object' || Array.isArray(form) || typeof form.id !== 'string' || !Array.isArray(form.fields)) return '';
    const fields = form.fields.map(field => {
      if (!field || typeof field.id !== 'string' || !formTypes.has(field.type)) return '';
      const id = escape(field.id), label = escape(field.label ?? field.id);
      const value = Object.prototype.hasOwnProperty.call(values, field.id) ? values[field.id] : field.value;
      const attr = `data-form-field="${id}" data-form-type="${field.type}" ${field.required ? 'required' : ''} ${field.type === 'number' && Number.isFinite(field.min) ? `min="${field.min}"` : ''} ${field.type === 'number' && Number.isFinite(field.max) ? `max="${field.max}"` : ''} ${disabled ? 'disabled' : ''}`;
      let control;
      if (field.type === 'checkbox') control = `<input type="checkbox" ${attr} ${value === true ? 'checked' : ''}>`;
      else if (field.type === 'textarea') control = `<textarea ${attr}>${escape(value ?? '')}</textarea>`;
      else if (field.type === 'select' || field.type === 'multiselect') {
        if (!Array.isArray(field.options)) return '';
        control = `<select ${attr} ${field.type === 'multiselect' ? 'multiple size="4"' : ''}>${field.options.filter(option => typeof option === 'string').map(option => `<option value="${escape(option)}" ${field.type === 'multiselect' ? Array.isArray(value) && value.includes(option) ? 'selected' : '' : option === value ? 'selected' : ''}>${escape(option)}</option>`).join('')}</select>`;
      } else control = `<input type="${field.type}" ${attr} value="${escape(value ?? '')}">`;
      return `<label class="form-field ${field.type === 'checkbox' ? 'form-checkbox' : ''}"><span>${label}</span>${control}</label>`;
    }).join('');
    return `<section class="lab-form" aria-label="${escape(form.title ?? form.id)}"><h3>${escape(form.title ?? form.id)}</h3><div class="form-fields">${fields}</div></section>`;
  }

  const syntaxCss = `.syntax-comment{color:#899791;font-style:italic}.syntax-string{color:#428777}.syntax-keyword{color:#9560ad}.syntax-number{color:#b9793c}.syntax-type{color:#417eaa}.syntax-method{color:#ab7146}.syntax-annotation{color:#b89039}.syntax-punctuation{color:#86909f}`;
  const outputCss = `.formatted-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.formatted-actions{display:flex;gap:6px}.formatted-actions button{font:inherit;font-size:11px;letter-spacing:0;text-transform:none;color:#536174;border:1px solid #d8dfe8;border-radius:4px;background:#fff;padding:3px 8px;cursor:pointer}.formatted-actions button:hover{background:#edf4f7}.structured-explorer{max-height:450px;overflow:auto;padding:9px 12px;font:12px/1.65 ui-monospace,SFMono-Regular,Consolas,monospace}.output-tree-node{margin:2px 0}.output-tree-node summary{cursor:pointer;white-space:pre-wrap;overflow-wrap:anywhere}.output-tree-children{margin-left:14px;padding-left:9px;border-left:1px solid #d8dfe8}.output-tree-leaf{white-space:pre-wrap;overflow-wrap:anywhere}.output-tree-key{color:#9560ad}.output-tree-type{color:#75808f}.output-tree-value{color:#428777}.formatted-code>summary{cursor:pointer;border-top:1px solid #e5e9ee;padding:5px 12px;color:#75808f;font-size:11px}.formatted-code:not([open]) pre{display:none}.formatted-output pre.formatted-source{white-space:pre;overflow:auto}@media print{.formatted-actions{display:none}.structured-explorer{max-height:none;overflow:visible}}`;
  const exportCss = `
    .grid-output{width:100%;margin:auto}.grid-title{font-size:15px;font-weight:500;margin:0 0 9px;color:#536174}.grid-table{font-size:12px}.grid-table .gridjs-th,.grid-table .gridjs-td{padding:9px 12px}.table-render-error{color:#af6056;padding:12px}
    .formatted-output,.lab-form{border:1px solid #e5e9ee;border-radius:7px;background:#f8fafc;overflow:hidden}.formatted-heading{padding:6px 12px;font-size:10px;letter-spacing:1px;color:#75808f;border-bottom:1px solid #e5e9ee}.formatted-output pre{padding:12px;white-space:pre;overflow:auto;margin:0}.lab-form{padding:14px}.lab-form h3{font-size:15px;margin:0 0 12px}.form-fields{display:grid;gap:12px}.form-field{display:grid;gap:4px;font-size:12px}.form-field input,.form-field textarea,.form-field select{font:inherit;padding:7px;border:1px solid #d8dfe8;border-radius:4px;max-width:100%}.form-field textarea{min-height:72px}.form-checkbox{display:flex;align-items:center;gap:9px}.form-checkbox input{order:-1}
    .echarts-chart{width:100%;margin:0 auto;break-inside:avoid}.chart-render-error{color:#ab5c51;padding:15px;font-size:12px}
    .chart-image{display:block;max-width:100%;height:auto;margin:auto;border-radius:5px}.export-output.chart{background:#fff;border:1px solid #e5e9ee;border-radius:8px;padding:12px}
    :root{color-scheme:light;font-family:Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#2d3748;background:#f5f3ef}
    *{box-sizing:border-box}body{margin:0;font-size:15px;line-height:1.8}a{color:#bb652f}main{max-width:960px;margin:60px auto;background:#fff;border:1px solid #e8e3db;border-radius:18px;overflow:hidden;box-shadow:0 15px 60px #4b34280a}
    .cover{padding:52px 64px 42px;background:radial-gradient(ellipse at top right,#ffebd9,transparent 65%),#fffdfa;border-bottom:1px solid #eee8df}.brand{font-size:11px;font-weight:700;letter-spacing:3px;color:#c47443}.cover h1{font-size:42px;line-height:1.2;letter-spacing:-1.5px;margin:20px 0;color:#293343;overflow-wrap:anywhere}.subtitle{color:#9299a1;font-size:13px}.pill{display:inline-block;border:1px solid #e8d4c0;padding:2px 11px;border-radius:20px;color:#9c744e;font-size:11px;margin-right:9px}.document{padding:36px 64px 42px}.export-cell{margin:0 0 30px;break-inside:avoid}.cell-label{font-size:9px;font-weight:600;letter-spacing:1.6px;color:#a5a8ae;margin-bottom:8px}.export-code{display:flex;border:1px solid #e5e9ee;border-radius:8px;background:#f8fafc;overflow:hidden}.export-code pre{padding:17px 19px;margin:0;overflow:auto;flex:1}.line-numbers{padding:17px 10px 17px 14px;border-right:1px solid #e7ebef;user-select:none;white-space:pre;text-align:right;color:#bcc2cb;background:#f2f5f8;font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px;line-height:1.8}pre,code{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px;line-height:1.8}pre{white-space:pre;tab-size:4}.export-output{margin-top:8px;padding:13px 18px;border-left:3px solid #c9dcd4;background:#f6faf8;border-radius:0 6px 6px 0}.export-output pre{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;color:#547068}.export-output.error{border-color:#d7958b;background:#fff5f2}.export-output.error pre{color:#ab5c51}.export-output.stderr{border-color:#dfbd8d;background:#fffbf3}.output-label{font-size:8px;letter-spacing:1.3px;color:#9aa7a0;margin-bottom:5px}.prose{color:#657080;overflow-wrap:anywhere}.prose h1,.prose h2,.prose h3,.prose h4,.prose h5,.prose h6{color:#303b4b;line-height:1.35;letter-spacing:-.5px;margin:26px 0 13px}.prose h1{font-size:30px}.prose h2{font-size:24px}.prose h3{font-size:19px}.prose p{margin:12px 0}.prose li{margin:4px 0}.prose code:not(pre code){background:#f4f0eb;color:#a96e40;padding:3px 5px;border-radius:4px}.prose pre{padding:17px 20px;background:#f8fafc;border:1px solid #e5e9ee;border-radius:8px;overflow:auto}.prose blockquote{border-left:3px solid #e2b795;padding:2px 20px;margin:18px 0;background:#fdfaf6}.prose hr{border:0;border-top:1px solid #e9e4dd;margin:25px 0}.table-scroll{overflow:auto}table{border-collapse:collapse;width:100%;font-size:13px}th,td{text-align:left;padding:9px 14px;border-bottom:1px solid #e7ebef}th{background:#f6f8fa;color:#536174}footer{padding:20px 64px;background:#faf9f7;border-top:1px solid #eee8df;font-size:10px;color:#a1a5ac;display:flex;justify-content:space-between}
    @media(max-width:700px){main{margin:15px;border-radius:10px}.cover{padding:30px 25px}.cover h1{font-size:30px}.document{padding:20px 25px}footer{padding:17px 25px;gap:15px}.line-numbers{display:none}}
    @media print{:root{background:#fff}main{border:0;box-shadow:none;margin:0;max-width:none}.cover{padding:20px 25px}.document{padding:20px 25px}footer{padding:15px 25px}pre{white-space:pre-wrap}.export-cell{break-inside:auto}.export-code,.export-output{break-inside:avoid}a{color:inherit;text-decoration:none}}
  `;

  function exportHtml(name, document, assets = {}) {
    const hasECharts = document.cells.some(c => (c.outputs || []).some(o => !!echartsHtml(o)));
    const hasGrid = document.cells.some(c => (c.outputs || []).some(o => !!gridHtml(o)));
    if (hasECharts && (!assets.runtime || !assets.bootstrap)) throw new Error('ECharts runtime is required for standalone HTML export');
    if(hasGrid&&(!assets.gridRuntime||!assets.gridBootstrap||!assets.gridCss))throw new Error('Grid.js assets are required for standalone HTML export');
    const getSource = c => Array.isArray(c.source) ? c.source.join('') : c.source || '';
    const text = value => Array.isArray(value) ? value.join('') : value ?? '';
    const title = name.replace(/\.(jnb|ipynb)$/, '');
    const codeCount = document.cells.filter(c => c.cell_type === 'code').length;
    const date = new Date().toLocaleDateString(undefined, {year:'numeric',month:'long',day:'numeric'});
    const headingCounts=new Map();
    const cells = document.cells.map((c,index) => {
      const value = getSource(c);
      if (c.cell_type === 'markdown') return `<section class="export-cell prose">${markdown(value,{standalone:true,headingCounts,imageSources:assets.markdownImages||{}})}</section>`;
      const code = c.cell_type === 'code';
      const outputs = (c.outputs || []).map(o => {
        const formatted=codeHtml(o);
        if(formatted)return `<div class="export-output">${formatted}</div>`;
        const form=formHtml(o,document.metadata?.java_lab?.form_values || {},true);
        if(form)return `<div class="export-output">${form}</div>`;
        const table=gridHtml(o);
        if(table)return `<div class="export-output chart"><div class="output-label">GRID.JS · INTERACTIVE TABLE</div>${table}</div>`;
        const interactive = echartsHtml(o);
        if (interactive) return `<div class="export-output chart"><div class="output-label">APACHE ECHARTS · INTERACTIVE</div>${interactive}</div>`;
        const image = imageHtml(o);
        if (image) return `<div class="export-output chart"><div class="output-label">CHART</div>${image}</div>`;
        if (!['stream','error','execute_result'].includes(o.output_type)) return '';
        const error = o.output_type === 'error';
        const content = error ? (o.traceback || [`${o.ename}: ${o.evalue}`]).join('\n') : o.output_type === 'stream' ? text(o.text) : text(o.data?.['text/plain']);
        return `<div class="export-output ${error ? 'error' : o.name === 'stderr' ? 'stderr' : ''}"><div class="output-label">${error ? 'ERROR' : o.name === 'stderr' ? 'STDERR' : o.output_type === 'stream' ? 'CONSOLE' : 'RESULT'}</div><pre>${escape(content)}</pre></div>`;
      }).join('');
      const numbers = value.split('\n').map((_,i)=>i+1).join('\n');
      return `<section class="export-cell"><div class="cell-label">${code ? 'JAVA' : 'RAW'} · CELL ${index+1}${c.execution_count != null ? ' · EXECUTION '+escape(c.execution_count) : ''}</div><div class="export-code"><div class="line-numbers" aria-hidden="true">${numbers}</div><pre><code>${code ? highlightJava(value) : escape(value)}</code></pre></div>${outputs}</section>`;
    }).join('\n');
    const script = text => text.replace(/<\/script/gi,'<\\/script');
    const hasFormatted = document.cells.some(c => (c.outputs || []).some(o => !!codeHtml(o)));
    const scripts = (hasECharts ? `<script>${script(assets.runtime)}</script><script>${script(assets.bootstrap)}</script>` : '')
      +(hasGrid?`<script>${script(assets.gridRuntime)}</script><script>${script(assets.gridBootstrap)}</script>`:'')
      +(hasFormatted?`<script>(${installOutputActions.toString()})();</script>`:'');
    const gridCss=hasGrid?assets.gridCss.replace(/<\/style/gi,'<\\/style'):'';
    return `<!doctype html>\n<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${escape(title)} · Java Lab</title><style>${gridCss}${syntaxCss}${exportCss}${outputCss}</style></head><body><main><header class="cover"><div class="brand">JAVA LAB / NOTEBOOK</div><h1>${escape(title)}</h1><div class="subtitle"><span class="pill">Java · JShell</span>${codeCount} code ${codeCount===1 ? 'cell' : 'cells'} · ${escape(date)}</div></header><article class="document">${cells}</article><footer><span>Crafted with Java Lab</span><span>A self-contained notebook · Code, notes & results</span></footer></main>${scripts}</body></html>`;
  }
  function markdownImagePaths(text){const paths=new Set();markdown(text,{collect:path=>paths.add(path)});return [...paths];}
  return {highlightJava, markdown, markdownImagePaths, workspaceImagePath, workspaceFilePath, exportHtml, imageHtml, echartsHtml, echartsMime, gridHtml, gridMime, codeHtml, codeMime, formHtml, formMime, syntaxCss};
})();
