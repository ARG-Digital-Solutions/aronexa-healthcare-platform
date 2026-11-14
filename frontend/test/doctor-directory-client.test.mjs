import test from 'node:test';
import assert from 'node:assert/strict';
import {loadDoctorChoices,DoctorDirectoryError}
  from '../src/doctorDirectoryClient.ts';

const id=n=>'33333333-3333-4333-8333-'+String(n).padStart(12,'0');
const actor={tenantId:id(1),accessToken:'A'.repeat(43),
  permissions:['APPOINTMENT_BOOK']};
const response=(body,status=200)=>new Response(JSON.stringify(body),{status});
const opts=[{membershipId:id(3),label:'doctor_synthetic'}];

test('doctor selection uses signed-in bearer and only verified server fields',async()=>{
  let called=0;
  const choices=await loadDoctorChoices(actor,id(2),async(url,init)=>{
    called++;
    assert.equal(url,'/api/v1/branches/'+id(2)+'/opd/doctors');
    assert.equal(init.method,'GET');
    assert.equal(init.cache,'no-store');
    assert.equal(init.credentials,'omit');
    assert.equal(init.headers.Authorization,'Bearer '+actor.accessToken);
    return response([{...opts[0],untrusted:'ignored'}]);
  });
  assert.equal(called,1);
  assert.deepEqual(choices,opts);
  assert.ok(Object.isFrozen(choices));
  assert.ok(Object.isFrozen(choices[0]));
  assert.equal(Object.hasOwn(choices[0],'untrusted'),false);
});

test('missing APPOINTMENT_BOOK, invalid token or branch refuses without HTTP',async()=>{
  let calls=0;
  const transport=async()=>{calls++;return response(opts)};
  for(const [staff,branch] of [
    [{...actor,permissions:[]},id(2)],
    [{...actor,accessToken:'bad'},id(2)],
    [actor,'not-a-uuid'],
    [actor,'00000000-0000-0000-0000-000000000000'],
  ])await assert.rejects(loadDoctorChoices(staff,branch,transport),
    DoctorDirectoryError);
  assert.equal(calls,0);
});

test('role-denied, unavailable, invalid or spoofed directory is not trusted',async()=>{
  for(const status of [401,403,404,503,500])
    await assert.rejects(loadDoctorChoices(actor,id(2),
      async()=>response({secret:'no'},status)),DoctorDirectoryError);
  for(const malformed of [
    null,{},[{membershipId:'INVALID',label:'doctor_abc'}],
    [{membershipId:id(3),label:''}],
    [{membershipId:id(3),label:' doctor_abc'}],
    [{membershipId:id(3),label:'doctor_abc\n'}],
    [opts[0],opts[0]],
  ])await assert.rejects(loadDoctorChoices(actor,id(2),
    async()=>response(malformed)),DoctorDirectoryError);
});

test('unreachable backend has no fallback doctor or stored identity',async()=>{
  await assert.rejects(loadDoctorChoices(actor,id(2),
    async()=>{throw Error('sensitive internal information')}),DoctorDirectoryError);
  const empty=await loadDoctorChoices(actor,id(2),async()=>response([]));
  assert.deepEqual(empty,[]);
});
