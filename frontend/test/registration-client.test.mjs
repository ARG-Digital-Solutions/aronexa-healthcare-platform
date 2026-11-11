import test from 'node:test';
import assert from 'node:assert/strict';
import {createRegistrationAttempt, RegistrationError} from '../src/registrationClient.ts';

const branch = '11111111-1111-4111-8111-111111111111';
const token = 'a'.repeat(43);
const intake = () => ({name:' Synthetic   Patient ',precision:'FULL',dob:'1990-02-03',phone:''});
const receipt = {patientId:'22222222-2222-4222-8222-222222222222',registrationId:'33333333-3333-4333-8333-333333333333',mrn:'PAT-0123456789AB',replayed:false};
const json = (value,status=201) => new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
const attempt = (fetcher, input=intake(), branchId=branch, accessToken=token) => createRegistrationAttempt(input,branchId,accessToken,fetcher);

test('sends mapped intake with bearer authorization and privacy-safe request options',async()=>{
  let request;
  const result=await attempt(async(url,options)=>{request={url,options};return json(receipt);}).submit();
  assert.deepEqual(result,receipt);
  assert.equal(request.url,`/api/v1/branches/${branch}/patient-registrations`);
  assert.deepEqual(JSON.parse(request.options.body),{displayName:'Synthetic Patient',birthDatePrecision:'EXACT',dateOfBirth:'1990-02-03'});
  assert.equal(request.options.headers.Authorization,`Bearer ${token}`);
  assert.match(request.options.headers['Idempotency-Key'],/^[a-f0-9-]{36}$/);
  assert.equal(request.options.cache,'no-store');
  assert.equal(request.options.credentials,'omit');
  assert.equal(request.options.redirect,'error');
  assert.equal(request.options.mode,'same-origin');
});
test('maps unknown birth date to null',async()=>{
  await attempt(async(_url,options)=>{assert.equal(JSON.parse(options.body).dateOfBirth,null);return json(receipt);},{name:'Synthetic Patient',precision:'UNKNOWN',dob:'',phone:''}).submit();
});
for(const input of [ {...intake(),phone:'9000000000'}, {...intake(),precision:'OTHER'}, {...intake(),name:'Patient\u0000Name'}, {...intake(),dob:'2025-02-29'} ])
  test(`rejects unsupported or invalid intake ${JSON.stringify(input)}`,()=>assert.throws(()=>attempt(()=>assert.fail('must not send'),input),RegistrationError));
for(const [branchId,accessToken] of [['../auth/login',token],[branch,''],[branch,'abc\r\nInjected: true']])
  test(`rejects invalid route/session ${branchId} ${accessToken.length}`,()=>assert.throws(()=>attempt(()=>assert.fail('must not send'),intake(),branchId,accessToken),RegistrationError));
test('lost response retains the same request and key for explicit retry, without automatic resubmission',async()=>{
  const requests=[]; const input=intake();
  const operation=attempt(async(_url,options)=>{requests.push(options);if(requests.length===1)throw new Error('secret network details');return json({...receipt,replayed:true},200);},input);
  await assert.rejects(operation.submit(),e=>e.code==='OUTCOME_UNKNOWN'&&!e.message.includes('secret'));
  assert.equal(requests.length,1);
  input.name='Changed Patient';
  assert.equal((await operation.submit()).replayed,true);
  assert.equal(requests[0].headers['Idempotency-Key'],requests[1].headers['Idempotency-Key']);
  assert.equal(requests[0].body,requests[1].body);
});
test('double submit shares one in-flight write and confirmed success is not sent again',async()=>{
  let complete;let sends=0;
  const operation=attempt(()=>{sends++;return new Promise(resolve=>{complete=resolve;});});
  const first=operation.submit(); const second=operation.submit();
  await Promise.resolve(); complete(json(receipt));
  assert.deepEqual(await first,receipt);assert.deepEqual(await second,receipt);
  assert.deepEqual(await operation.submit(),receipt);assert.equal(sends,1);
});
for(const [status,body,code] of [
  [401,{},'SESSION_EXPIRED'],[403,{},'REGISTRATION_DENIED'],
  [409,{code:'DUPLICATE_REVIEW_REQUIRED'},'DUPLICATE_REVIEW_REQUIRED'],
  [409,{code:'IDEMPOTENCY_CONFLICT'},'IDEMPOTENCY_CONFLICT'],
  [409,{code:'patient secret'},'REGISTRATION_CONFLICT'],
  [400,{},'INVALID_REGISTRATION_REQUEST'],[429,{},'RATE_LIMITED'],[503,{},'OUTCOME_UNKNOWN']
])test(`maps HTTP ${status} to safe ${code}`,async()=>{
  await assert.rejects(attempt(async()=>json(body,status)).submit(),e=>e instanceof RegistrationError&&e.code===code&&!e.message.includes('patient secret'));
});
for(const [body,status] of [[{},201],[{...receipt,patientId:'bad'},201],[{...receipt,mrn:'DEMO-1'},201],[{...receipt,replayed:true},201],[receipt,200],[receipt,202]])
  test(`rejects malformed or inconsistent receipt ${status} ${JSON.stringify(body)}`,async()=>assert.rejects(attempt(async()=>json(body,status)).submit(),e=>e.code==='OUTCOME_UNKNOWN'));
test('non-JSON success is never reported as saved',async()=>assert.rejects(attempt(async()=>new Response('<html>error</html>',{status:201})).submit(),e=>e.code==='OUTCOME_UNKNOWN'));
test('separate registration attempts use different random keys',async()=>{
  const keys=[];const fetcher=async(_url,options)=>{keys.push(options.headers['Idempotency-Key']);return json(receipt);};
  await attempt(fetcher).submit();await attempt(fetcher).submit();assert.notEqual(keys[0],keys[1]);
});
