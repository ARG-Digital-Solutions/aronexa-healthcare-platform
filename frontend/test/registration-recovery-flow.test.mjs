import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createRegistrationRecoveryFlow, RegistrationRecoveryError,
} from '../src/registrationRecoveryFlow.ts';
import {RegistrationReferenceError} from '../src/registrationReferenceClient.ts';

const id=n=>'11111111-1111-4111-8111-'+String(n).padStart(12,'0');
const tenant=id(1),branch=id(2),registration=id(3),patient=id(4);
const token='a'.repeat(43);
const member=Object.freeze({tenantId:tenant,userId:id(8),membershipId:id(9),
  username:'reception',permissions:['PATIENT_REGISTRATION_READ'],
  roles:['RECEPTION'],accessToken:token});
const receipt=Object.freeze({tenantId:tenant,branchId:branch,
  registrationId:registration,patientId:patient});
const response=(data,status=200)=>new Response(JSON.stringify(data),{status});
const codes=code=>e=>e instanceof RegistrationRecoveryError&&e.code===code;

test('authorized lookup retrieves only minimal server reference with no persistence',async()=>{
  let path,options,calls=0;
  const recovery=createRegistrationRecoveryFlow(member,branch,async(url,opts)=>{
    path=url;options=opts;calls++;return response({...receipt,name:'SECRET-NOT-FOR-UI'});
  });
  const result=await recovery.recover(registration);
  assert.deepEqual(result,receipt);
  assert.equal(Object.isFrozen(result),true);
  assert.equal(path,'/api/v1/branches/'+branch+'/patient-registrations/'+registration);
  assert.equal(options.method,'GET');
  assert.equal(options.credentials,'omit');
  assert.equal(options.cache,'no-store');
  assert.equal(options.headers.Authorization,'Bearer '+token);
  assert.equal(calls,1);
  assert.equal(recovery.snapshot().status,'FOUND');
  assert.deepEqual(Object.keys(recovery.snapshot().reference).sort(),
    ['tenantId','branchId','registrationId','patientId'].sort());
  assert.equal(JSON.stringify(recovery.snapshot()).includes(token),false);
  assert.equal(JSON.stringify(recovery.snapshot()).includes('SECRET'),false);
});

test('rejects missing permission, synthetic identifiers and bad token without network',()=>{
  assert.throws(()=>createRegistrationRecoveryFlow(
    {...member,permissions:['PATIENT_REGISTER']},branch),codes('NOT_AUTHORIZED'));
  assert.throws(()=>createRegistrationRecoveryFlow(member,'DEMO-BRANCH'),codes('NOT_AUTHORIZED'));
  assert.throws(()=>createRegistrationRecoveryFlow({...member,accessToken:'bad'},branch),
    codes('NOT_AUTHORIZED'));
  const flow=createRegistrationRecoveryFlow(member,branch,()=>assert.fail('No request'));
  return assert.rejects(flow.recover('DEMO-0001'),codes('INVALID_REFERENCE'));
});

test('404, 403 and revoked sessions fail closed and do not reuse old patient IDs',async()=>{
  let status=200;
  const recovery=createRegistrationRecoveryFlow(member,branch,async()=>
    response(status===200?receipt:{secret:'patient'},status));
  await recovery.recover(registration);
  for(const [http,code] of [[404,'REGISTRATION_NOT_FOUND'],
    [403,'REGISTRATION_READ_DENIED'],[401,'SESSION_EXPIRED']]){
    status=http;
    await assert.rejects(recovery.recover(registration),
      e=>e instanceof RegistrationReferenceError && e.code===code);
    assert.equal(recovery.snapshot().reference,null);
    assert.equal(recovery.snapshot().status,'FAILED');
    assert.equal(recovery.snapshot().errorCode,code);
  }
});

test('wrong tenant, branch, registration, patient value or malformed JSON is never accepted',async()=>{
  for(const bad of [
    {...receipt,tenantId:id(6)},{...receipt,branchId:id(6)},
    {...receipt,registrationId:id(6)},{...receipt,patientId:'DEMO-0001'},
    {...receipt,patientId:null},[],
  ]){
    const recovery=createRegistrationRecoveryFlow(member,branch,
      async()=>response(bad));
    await assert.rejects(recovery.recover(registration),
      e=>e instanceof RegistrationReferenceError && e.code==='OUTCOME_UNKNOWN');
    assert.equal(recovery.snapshot().reference,null);
  }
});

test('concurrent same-ID requests are deduplicated and different ID rejected',async()=>{
  let resolve,calls=0;
  const recovery=createRegistrationRecoveryFlow(member,branch,()=>{
    calls++;return new Promise(done=>{resolve=done;});
  });
  const a=recovery.recover(registration);
  const b=recovery.recover(registration);
  assert.equal(a,b);
  await assert.rejects(recovery.recover(id(7)),codes('BUSY'));
  resolve(response(receipt));
  assert.deepEqual(await a,await b);
  assert.equal(calls,1);
});

test('signout invalidates in-flight response and clears active patient reference',async()=>{
  let resolve;
  const recovery=createRegistrationRecoveryFlow(member,branch,()=>
    new Promise(done=>{resolve=done;}));
  const pending=recovery.recover(registration);
  recovery.dispose();
  resolve(response(receipt));
  await assert.rejects(pending,codes('SESSION_CLOSED'));
  assert.deepEqual(recovery.snapshot(),{status:'IDLE',reference:null,errorCode:null});
  await assert.rejects(recovery.recover(registration),codes('SESSION_CLOSED'));
});
