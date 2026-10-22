(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.TextEditing=api;})(typeof window==='undefined'?globalThis:window,()=>{
  const escape=value=>String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  function highlight(text,format){
    if(format==='text'||text.length>300000)return escape(text);
    if(format==='yaml')return highlightYaml(text);
    const pattern=['xml','html'].includes(format)?/<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?\]\]>|<\?[\s\S]*?\?>|<\/?[A-Za-z_][^>]*>/g
      :format==='json'?/"(?:\\.|[^"\\])*"|\b(?:true|false|null)\b|-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b/g
      :/\/\*[\s\S]*?\*\/|\/\/[^\n]*|#[^\n]*|--[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b(?:true|false|null|public|class|var|return|if|else|select|from|where)\b|\b\d+\b/gi;
    let result='',end=0;
    for(const match of text.matchAll(pattern)){
      const token=match[0],kind=/^(<!--|\/\*|\/\/|#|--|<!\[CDATA)/.test(token)?'comment':token[0]==='<'?'keyword':/^["']/.test(token)?'string':/^[-\d]/.test(token)?'number':'keyword';
      result+=escape(text.slice(end,match.index))+`<span class="syntax-${kind}">${escape(token)}</span>`;end=match.index+token.length;
    }
    return result+escape(text.slice(end));
  }
  function highlightYaml(text){
    const span=(kind,value)=>`<span class="syntax-${kind}">${escape(value)}</span>`;
    let blockIndent=null;
    return text.split('\n').map(line=>{
      const indent=line.match(/^ */)[0].length;
      if(blockIndent!==null){
        if(!line.trim()||indent>blockIndent)return span('string',line);
        blockIndent=null;
      }
      // Consume plain scalars as a whole so URLs, hashes and apostrophes stay literal.
      const tokens=/[ \t\r]+|#[^\r]*|"(?:\\.|[^"\\])*"|'(?:''|[^'])*'|[\[\]{},]|:(?=\s|$)|[?\-](?=\s|$)|[|>][1-9+-]*(?=\s|$)|(?:(?!:(?:\s|$))[^\s\[\]{},])+/g;
      let result='',end=0;
      for(const match of line.matchAll(tokens)){
        let value=match[0],kind=null;
        const tail=line.slice(match.index+value.length);
        if(value[0]==='#')kind='comment';
        else if(/^\s*:(?:\s|$)/.test(tail))kind='keyword';
        else if(/^["']/.test(value))kind='string';
        else if(/^[|>][1-9+-]*$/.test(value)){kind='keyword';blockIndent=indent;}
        else if(/^(?:---|\.\.\.|[\[\]{},:?\-]|[&*!]\S+|%\S.*)$/.test(value))kind='keyword';
        else if(/^(?:true|false|null|yes|no|on|off|~)$/i.test(value))kind='keyword';
        else if(/^[+-]?(?:0[xX][\da-fA-F_]+|0[oO][0-7_]+|\d[\d_]*(?:\.[\d_]*)?(?:[eE][+-]?\d+)?|\.\d+(?:[eE][+-]?\d+)?|\.(?:inf|nan))$/i.test(value))kind='number';
        else if(value.trim())kind='string';
        result+=escape(line.slice(end,match.index))+(kind?span(kind,value):escape(value));end=match.index+value.length;
      }
      return result+escape(line.slice(end));
    }).join('\n');
  }
  function formatJson(text){
    JSON.parse(text); // Validate syntax only; keep original number literals, duplicate keys and key order.
    const tokens=text.match(/"(?:\\.|[^"\\])*"|[{}\[\],:]|[^\s{}\[\],:]+/g)||[];
    let depth=0,out='';const line=()=> '\n'+'  '.repeat(depth);
    tokens.forEach((token,i)=>{
      if(token==='{'||token==='['){out+=token;depth++;if(depth>100)throw new Error('JSON is too deeply nested to format (100 levels)');if(tokens[i+1]!=='}'&&tokens[i+1]!==']')out+=line();}
      else if(token==='}'||token===']'){depth--;if(tokens[i-1]!=='{'&&tokens[i-1]!=='[')out+=line();out+=token;}
      else if(token===',')out+=','+line();else if(token===':')out+=': ';else out+=token;
      if(out.length>2000000)throw new Error('Formatted output exceeds the 2 MB editor limit');
    });
    return out+(/\n$/.test(text)?'\n':'');
  }
  function assist(text,start,end,key,shift=false){
    const insert=value=>({text:text.slice(0,start)+value+text.slice(end),start:start+value.length,end:start+value.length});
    if(key==='Tab'){
      if(start===end&&!shift)return insert('  ');
      const from=text.lastIndexOf('\n',start-1)+1,tail=end>start&&text[end-1]==='\n'?end-1:end;
      const newline=text.indexOf('\n',tail),to=newline<0?text.length:newline;
      let total=0,first=0;
      const replacement=text.slice(from,to).split('\n').map((line,i)=>{
        const delta=shift?-(line.match(/^(?: {1,2}|\t)/)?.[0].length||0):2;total+=delta;if(!i)first=delta;
        return delta<0?line.slice(-delta):shift?line:'  '+line;
      }).join('\n');
      return {text:text.slice(0,from)+replacement+text.slice(to),start:Math.max(from,start+first),end:Math.max(from,end+total)};
    }
    const pairs={'{':'}','[':']','(':')','"':'"',"'":"'"};
    if(key==='Backspace'&&start===end&&start>0&&pairs[text[start-1]]&&pairs[text[start-1]]===text[start])return {text:text.slice(0,start-1)+text.slice(start+1),start:start-1,end:start-1};
    if('}])"\''.includes(key)&&start===end&&text[start]===key)return {text,start:start+1,end:start+1};
    if(key==='Enter'){
      const prefix=text.slice(text.lastIndexOf('\n',start-1)+1,start),indent=prefix.match(/^[ \t]*/)[0];
      const expand=/[{[(]\s*$/.test(prefix)||/<[A-Za-z_][^>]*>\s*$/.test(prefix)&&!/\/>\s*$/.test(prefix);
      const value='\n'+indent+(expand?'  ':'');
      if(expand&&/^\s*(?:[}\])]|<\/)/.test(text.slice(end))){const change=insert(value+'\n'+indent);change.start=change.end=start+value.length;return change;}
      return insert(value);
    }
    if(pairs[key]){
      if(start!==end)return {text:text.slice(0,start)+key+text.slice(start,end)+pairs[key]+text.slice(end),start:start+1,end:end+1};
      let quote=null,escaped=false;
      for(const c of text.slice(0,start)){if(escaped){escaped=false;continue;}if(c==='\\'){escaped=true;continue;}if(quote){if(c===quote)quote=null;}else if(c==='"'||c==="'")quote=c;}
      if(!quote&&(!text[end]||/[\s}\])>,:;]/.test(text[end]))){const change=insert(key+pairs[key]);change.start=change.end=start+1;return change;}
    }
    return null;
  }
  return {highlight,formatJson,assist};
});
