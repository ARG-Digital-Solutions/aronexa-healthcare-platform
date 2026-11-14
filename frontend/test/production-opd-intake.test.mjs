import test from 'node:test';
import assert from 'node:assert/strict';
import {verifyProductionOpdIntake,OpdIntakeError}
  from '../src/productionOpdIntakeClient.ts';

const id=n=>'11111111-1111-4111-8111-'+String(n).padStart(12,'0');
const ref=Object.freeze({
  tenantId:id(1),branchId:id(2),registrationId:id(3),patientId:id(4),
});
const actor=Object.freeze({
  tenantId:id(1),accessToken:'A'.repeat(43),
  permissions:['APPOINTMENT_BOOK','PATIENT_REGISTRATION_READ'],
});
const result=Object.freeze({...ref,status:'REGISTRATION_VERIFIED'});
const reply=(body,status=200)=>new Response(
  JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});
const throwsCode=async(promise,code)=>assert.rejects(promise,e=>
  e instanceof OpdIntakeError&&e.code===code);

test('intake returns only frozen server-verified pseudonymous fields',async()=>{
  let requests=0;
  const found=await verifyProductionOpdIntake(actor,ref,async(url,init)=>{
    requests++;
    assert.equal(url,'/api/v1/branches/'+id(2)+'/opd/intake/'+id(3));
    assert.equal(init.method,'GET');
    assert.equal(init.credentials,'omit');
    assert.equal(init.cache,'no-store');
    assert.equal(init.headers.Authorization,'Bearer '+actor.accessToken);
    return reply({...result,irrelevant:'not echoed'});
  });
  assert.equal(requests,1);
  assert.deepEqual(found,result);
  assert.equal(Object.isFrozen(found),true);
  assert.equal(JSON.stringify(found).includes(actor.accessToken),false);
});

test('invalid bearer, wrong tenant or missing permissions fail before HTTP',async()=>{
  let calls=0;
  const transport=async()=>{calls++;return reply(result)};
  for(const [session,reference] of [
    [{...actor,accessToken:'bad'},ref],
    [{...actor,tenantId:id(8)},ref],
    [{...actor,permissions:['APPOINTMENT_BOOK']},ref],
    [{...actor,permissions:['PATIENT_REGISTRATION_READ']},ref],
    [actor,{...ref,registrationId:id(0).replace(/^11111111/,'00000000')}],
  ])await throwsCode(verifyProductionOpdIntake(session,reference,transport),
    'ACCESS_DENIED');
  assert.equal(calls,0);
});

test('revocation, role-denied and unverified statuses never advance',async()=>{
  for(const [status,code] of [
    [401,'SESSION_EXPIRED'],
    [403,'ACCESS_DENIED'],
    [404,'ACCESS_DENIED'],
    [500,'UNVERIFIED_RESPONSE'],
  ])await throwsCode(verifyProductionOpdIntake(actor,ref,
    async()=>reply({error:'never echo'},status)),code);
});

test('forged server fields, missing stage and network error fail closed',async()=>{
  for(const forged of [
    {...result,tenantId:id(8)},
    {...result,branchId:id(8)},
    {...result,patientId:id(8)},
    {...result,registrationId:id(8)},
    {...result,status:'BOOKED'},
    {...result,status:null},
  ])await throwsCode(verifyProductionOpdIntake(actor,ref,
    async()=>reply(forged)),'UNVERIFIED_RESPONSE');
  await throwsCode(verifyProductionOpdIntake(actor,ref,
    async()=>{throw Error('secret response body')}),'UNVERIFIED_RESPONSE');
});
