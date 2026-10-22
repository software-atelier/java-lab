const {test}=require('node:test');const assert=require('node:assert/strict');
const editing=require('../../main/resources/static/text-editing.js');
const unescape=text=>text.replace(/<span[^>]*>|<\/span>/g,'').replace(/&quot;/g,'"').replace(/&#39;/g,"'").replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&amp;/g,'&');
test('JSON/XML highlighting preserves every source character and escapes markup',()=>{
  for(const [format,text] of [['json','{"id":9007199254740993,"script":"</textarea><script>x</script>","ok":true}'],['xml','<!-- x --><root attr="a>b"><![CDATA[<script>]]></root>']]){
    const html=editing.highlight(text,format);assert.equal(unescape(html),text);assert.doesNotMatch(html,/<script>/);assert.match(html,/syntax-/);
  }
  assert.equal(editing.highlight('plain <text>','text'),'plain &lt;text&gt;');
});
test('JSON formatting preserves big numeric literals, duplicate keys, key order and escaped strings',()=>{
  // Use a correctly escaped JSON string while retaining non-string source tokens verbatim.
  const input='{"z":9007199254740993,"a":1e400,"z":-0.0001,"message":'+JSON.stringify('a, { "quote"')+'}';
  const formatted=editing.formatJson(input);assert.match(formatted,/9007199254740993/);assert.match(formatted,/1e400/);
  assert.equal((formatted.match(/"z"/g)||[]).length,2);assert.ok(formatted.indexOf('"z"')<formatted.indexOf('"a"'));assert.equal(JSON.parse(formatted).message,'a, { "quote"');
  assert.throws(()=>editing.formatJson('{ invalid'));assert.throws(()=>editing.formatJson('['.repeat(101)+'0'+']'.repeat(101)),/deeply/);
});
test('YAML highlighting distinguishes keys, scalars, comments and literal blocks without changing source',()=>{
  const text='---\nname: Ada\nactive: true\ncount: -12.5\nurl: https://example.org/a#part\nquote: "it\'s # literal" # comment\nmessage: |-\n  # not a comment\n  <script>alert(1)</script>\nnext: null\n';
  const html=editing.highlight(text,'yaml');
  assert.equal(unescape(html),text);assert.doesNotMatch(html,/<script>/);
  assert.match(html,/<span class="syntax-keyword">name<\/span>/);
  assert.match(html,/<span class="syntax-keyword">true<\/span>/);
  assert.match(html,/<span class="syntax-number">-12.5<\/span>/);
  assert.match(html,/<span class="syntax-string">https:\/\/example.org\/a#part<\/span>/);
  assert.match(html,/<span class="syntax-comment"># comment<\/span>/);
  assert.match(html,/<span class="syntax-string">  # not a comment<\/span>/);
  assert.equal(editing.highlight('x'.repeat(300001),'yaml'),'x'.repeat(300001));
});
test('editing assists indent, auto-indent and pair brackets without damaging ordinary backspace',()=>{
  assert.deepEqual(editing.assist('{}',1,1,'Enter'),{text:'{\n  \n}',start:4,end:4});
  assert.deepEqual(editing.assist('',0,0,'{'),{text:'{}',start:1,end:1});
  assert.deepEqual(editing.assist('{}',1,1,'Backspace'),{text:'',start:0,end:0});
  assert.equal(editing.assist('😀',2,2,'Backspace'),null);
  assert.equal(editing.assist('"hello',6,6,'{'),null);
  const indented=editing.assist('a\nb',0,3,'Tab');assert.equal(indented.text,'  a\n  b');
  assert.equal(editing.assist(indented.text,indented.start,indented.end,'Tab',true).text,'a\nb');
});
