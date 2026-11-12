import test from 'node:test';
import assert from 'node:assert/strict';
import {
  loadAuthorizedRegistrationReference, RegistrationReferenceError,
} from '../src/registrationReferenceClient.ts';

const uuid = suffix => '11111111-1111-4111-8111-' + String(suffix).padStart(12, '0');
const context = Object.freeze({
  tenantId: uuid(1), branchId: uuid(2), registrationId: uuid(3),
});
const token = 'a'.repeat(43);
const receipt = Object.freeze({...context, patientId: uuid(4)});
const response = (body, status=200) =>
  new Response(JSON.stringify(body), {status, headers:{'Content-Type':'application/json'}});
const load = (transport, ctx=context, bearer=token) =>
  loadAuthorizedRegistrationReference(ctx,bearer,transport);
const denied = (error, code) =>
  error instanceof RegistrationReferenceError && error.code === code &&
  !/11111111|Bearer|password|secret/.test(error.message);

test('authorized lookup uses same-origin no-store GET and returns only immutable IDs',async()=>{
  let captured;
  const saved=await load(async(path,options)=>{
    captured={path,options}; return response({...receipt, name:'DO NOT EXPOSE', accessToken:'SECRET'});
  });
  assert.deepEqual(saved,receipt);
  assert.equal(Object.isFrozen(saved),true);
  assert.equal(captured.path,
    '/api/v1/branches/' + context.branchId + '/patient-registrations/' + context.registrationId);
  assert.equal(captured.options.method,'GET');
  assert.equal(captured.options.mode,'same-origin');
  assert.equal(captured.options.credentials,'omit');
  assert.equal(captured.options.cache,'no-store');
  assert.equal(captured.options.redirect,'error');
  assert.equal(captured.options.headers.Authorization,'Bearer ' + token);
  assert.deepEqual(Object.keys(saved).sort(),
    ['tenantId','branchId','registrationId','patientId'].sort());
});

test('malformed or synthetic identities never trigger HTTP',async()=>{
  for (const badContext of [
    {...context, tenantId:'DEMO-TENANT'}, {...context, branchId:'DEMO-BRANCH'},
    {...context, registrationId:'DEMO-REG-001'},
    {...context, registrationId:uuid(0).replace('11111111-1111-4111-8111','00000000-0000-0000-0000')},
    null,
  ]) {
    await assert.rejects(load(()=>assert.fail('No HTTP allowed'),badContext), e=>denied(e,'REGISTRATION_READ_DENIED'));
  }
  for (const bearer of ['','abc\nInjected','not-a-token']) {
    await assert.rejects(load(()=>assert.fail('No HTTP allowed'),context,bearer),
      e=>denied(e,'REGISTRATION_READ_DENIED'));
  }
});

test('HTTP 401/403/404 and other errors map to safe messages',async()=>{
  for (const [status,code] of [
    [401,'SESSION_EXPIRED'],[403,'REGISTRATION_READ_DENIED'],
    [404,'REGISTRATION_NOT_FOUND'],[500,'OUTCOME_UNKNOWN'],
  ]) {
    await assert.rejects(load(async()=>response({secret:'patient-name'},status)),
      e=>denied(e,code));
  }
});

test('rejects cross-tenant, cross-branch, altered registration or fake patient in 200',async()=>{
  for (const mutation of [
    {tenantId:uuid(9)}, {branchId:uuid(9)},
    {registrationId:uuid(9)}, {patientId:'DEMO-PATIENT-001'},
    {patientId:null},
  ]) {
    await assert.rejects(load(async()=>response({...receipt,...mutation})),
      e=>denied(e,'OUTCOME_UNKNOWN'));
  }
});

test('network failures, malformed JSON and HTML success never confirm a reference',async()=>{
  await assert.rejects(load(async()=>{throw new Error('secret URL and token');}),
    e=>denied(e,'OUTCOME_UNKNOWN'));
  await assert.rejects(load(async()=>new Response('<html>secret</html>',{status:200})),
    e=>denied(e,'OUTCOME_UNKNOWN'));
  await assert.rejects(load(async()=>response([])),
    e=>denied(e,'OUTCOME_UNKNOWN'));
  await assert.rejects(load(async()=>response({})),
    e=>denied(e,'OUTCOME_UNKNOWN'));
});
