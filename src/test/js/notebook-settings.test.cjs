const {test}=require('node:test');
const assert=require('node:assert/strict');
const settings=require('../../main/resources/static/notebook-settings.js');

test('secret definitions never serialize a default value',()=>{
  const variables=settings.definitions([
    {name:'API_TOKEN',secret:true,env:'MY_TOKEN',defaultValue:'accidental secret'},
    {name:'REGION',secret:false,defaultValue:'eu-central-1'}
  ]);
  assert.deepEqual(variables,[
    {name:'API_TOKEN',secret:true,env:'MY_TOKEN'},
    {name:'REGION',secret:false,defaultValue:'eu-central-1'}
  ]);
  settings.validate(variables,['org.example:client:1.2.3']);
});

test('reject invalid or duplicate notebook definitions',()=>{
  assert.throws(()=>settings.validate([{name:'API_TOKEN',secret:false},{name:'API_TOKEN',secret:true}],[]),/Duplicate/);
  assert.throws(()=>settings.validate([{name:'API_TOKEN',secret:true,defaultValue:'secret'}],[]),/cannot have/);
  assert.throws(()=>settings.validate([{name:'bad-name'}],[]),/Invalid/);
  assert.throws(()=>settings.validate([],["org.example:client:1.2.3", "org.example:client:1.2.3"]),/Duplicate/);
});
