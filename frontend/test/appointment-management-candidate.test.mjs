import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createAppointmentCandidateFlow,
  loadAppointmentCandidateQueue,
  AppointmentCandidateError,
} from '../src/appointmentCandidateClient.ts';

const id=n=>'11111111-1111-4111-8111-'+String(n).padStart(12,'0');
const tenant=id(1),branch=id(2),patient=id(3),registration=id(4),
  doctor=id(5),appointment=id(6);
const token='a'.repeat(43),date='2026-11-13';
const session={tenantId:tenant,accessToken:token,
  permissions:['APPOINTMENT_BOOK','APPOINTMENT_READ','APPOINTMENT_CHECK_IN',
    'APPOINTMENT_RESCHEDULE','APPOINTMENT_CANCEL']};
const reference={tenantId:tenant,branchId:branch,patientId:patient,registrationId:registration};
const initial={...reference,appointmentId:appointment,doctorMembershipId:doctor,
  stage:'BOOKED',revision:1,visitDate:date,slotStart:'09:00:00',replayed:false};
const moved={...initial,slotStart:'09:15:00',revision:2};
const cancelled={...initial,stage:'CANCELLED',revision:2};
const respond=(status,body)=>new Response(JSON.stringify(body),{status});
const code=expected=>error=>error instanceof AppointmentCandidateError
  &&error.code===expected;

test('reschedule rechecks active appointment and its exact revision',async()=>{
  const requests=[];
  const flow=createAppointmentCandidateFlow(session,reference,async(path,options)=>{
    requests.push({path,options});
    if(options.method==='GET')return respond(200,initial);
    return respond(200,moved);
  });
  await flow.reopen(appointment);
  const changed=await flow.reschedule({
    doctorMembershipId:doctor,visitDate:date,slotStart:'09:15'});
  assert.equal(changed.revision,2);
  assert.equal(changed.slotStart,'09:15:00');
  assert.match(requests[1].path,/\/reschedule$/);
  assert.deepEqual(JSON.parse(requests[1].options.body),{
    doctorMembershipId:doctor,visitDate:date,slotStart:'09:15',expectedRevision:1,
  });
  assert.equal(flow.snapshot().receipt.stage,'BOOKED');
  assert.equal(JSON.stringify(flow.snapshot()).includes(token),false);
});

test('cancellation confirms server revision and terminates local operation',async()=>{
  const flow=createAppointmentCandidateFlow(session,reference,async(path,options)=>{
    return respond(200,options.method==='GET'?initial:cancelled);
  });
  await flow.reopen(appointment);
  const result=await flow.cancel();
  assert.equal(result.stage,'CANCELLED');
  assert.equal(result.revision,2);
  await assert.rejects(flow.cancel(),code('INVALID_REQUEST'));
  await assert.rejects(flow.checkIn(),code('INVALID_REQUEST'));
});

test('conflicted update discards local appointment receipt: reload is required',async()=>{
  const flow=createAppointmentCandidateFlow(session,reference,async(path,options)=>{
    if(options.method==='GET')return respond(200,initial);
    return respond(409,{code:'APPOINTMENT_CONFLICT'});
  });
  await flow.reopen(appointment);
  await assert.rejects(flow.reschedule({
    doctorMembershipId:doctor,visitDate:date,slotStart:'09:30'}),
    code('APPOINTMENT_CONFLICT'));
  assert.equal(flow.snapshot().receipt,null);
  await assert.rejects(flow.cancel(),code('INVALID_REQUEST'));
});

test('forged or stale update receipt never moves the local appointment state',async()=>{
  for(const response of [
    {...moved,patientId:id(9)},
    {...moved,appointmentId:id(9)},
    {...moved,revision:1},
    {...moved,stage:'CHECKED_IN'},
  ]){
    const flow=createAppointmentCandidateFlow(session,reference,
      async(path,options)=>respond(200,options.method==='GET'?initial:response));
    await flow.reopen(appointment);
    await assert.rejects(flow.reschedule({
      doctorMembershipId:doctor,visitDate:date,slotStart:'09:15'}),
      code('UNVERIFIED_RESPONSE'));
    assert.equal(flow.snapshot().receipt,null);
  }
});

test('only staff with exact separate management permissions may mutate',async()=>{
  let network=0;
  const limited={...session,permissions:['APPOINTMENT_BOOK','APPOINTMENT_READ']};
  const flow=createAppointmentCandidateFlow(limited,reference,async()=>{network++;return respond(200,initial);});
  await flow.reopen(appointment);
  await assert.rejects(flow.cancel(),code('NOT_AUTHORIZED'));
  await assert.rejects(flow.reschedule({
    doctorMembershipId:doctor,visitDate:date,slotStart:'09:15'}),
    code('NOT_AUTHORIZED'));
  assert.equal(network,1);
});

test('daily queue accepts no-cache scoped server rows and no client storage',async()=>{
  let url,options;
  const results=await loadAppointmentCandidateQueue(session,branch,date,
    async(u,o)=>{url=u;options=o;return respond(200,[initial,moved].map(
      (item,i)=>({...item,appointmentId:id(20+i)})));});
  assert.equal(results.length,2);
  assert.equal(Object.isFrozen(results),true);
  assert.equal(options.method,'GET');
  assert.equal(options.credentials,'omit');
  assert.equal(options.cache,'no-store');
  assert.equal(options.headers.Authorization,'Bearer '+token);
  assert.equal(url,'/api/v1/branches/'+branch+'/appointments?visitDate='+date);
  assert.equal(JSON.stringify(results).includes(token),false);
  assert.equal(results[0].stage,'BOOKED');
});

test('queue rejects forged scopes, cancelled rows, duplicates and oversized responses',async()=>{
  for(const response of [
    [{...initial,tenantId:id(10)}],
    [{...initial,branchId:id(11)}],
    [{...initial,patientId:'DEMO-001'}],
    [{...initial,visitDate:'2026-11-15'}],
    [cancelled],
    [initial,initial],
    Array.from({length:101},(_,i)=>({...initial,appointmentId:id(i+100)})),
    {bad:'shape'},
  ]){
    await assert.rejects(
      loadAppointmentCandidateQueue(session,branch,date,
        async()=>respond(200,response)),
      code('UNVERIFIED_RESPONSE'));
  }
});

test('queue refuses permission denials, revoked session and malformed date',async()=>{
  const restricted={...session,permissions:['APPOINTMENT_BOOK']};
  await assert.rejects(loadAppointmentCandidateQueue(restricted,branch,date,
    ()=>assert.fail('No request')),code('NOT_AUTHORIZED'));
  await assert.rejects(loadAppointmentCandidateQueue(session,branch,'DEMO-DATE',
    ()=>assert.fail('No request')),code('INVALID_REQUEST'));
  for(const [status,result] of [
    [401,'SESSION_EXPIRED'],[403,'NOT_AUTHORIZED'],
    [409,'APPOINTMENT_CONFLICT'],
  ]){
    await assert.rejects(loadAppointmentCandidateQueue(session,branch,date,
      async()=>respond(status,{})),code(result));
  }
});
