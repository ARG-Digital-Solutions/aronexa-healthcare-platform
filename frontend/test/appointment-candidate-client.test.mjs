import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createAppointmentCandidateFlow,AppointmentCandidateError,
} from '../src/appointmentCandidateClient.ts';

const id=n=>'11111111-1111-4111-8111-'+String(n).padStart(12,'0');
const t=id(1),branch=id(2),patient=id(3),registration=id(4),doctor=id(5),appointment=id(6);
const token='a'.repeat(43);
const session={tenantId:t,accessToken:token,
  permissions:['APPOINTMENT_BOOK','APPOINTMENT_READ','APPOINTMENT_CHECK_IN']};
const reference={tenantId:t,branchId:branch,patientId:patient,registrationId:registration};
const booking={doctorMembershipId:doctor,visitDate:'2026-11-13',slotStart:'09:00'};
const booked={...reference,doctorMembershipId:doctor,appointmentId:appointment,
  stage:'BOOKED',revision:1,visitDate:booking.visitDate,slotStart:'09:00:00',replayed:false};
const checked={...booked,stage:'CHECKED_IN',revision:2};
const respond=(status,data)=>new Response(JSON.stringify(data),{status});
const code=expected=>e=>e instanceof AppointmentCandidateError&&e.code===expected;

test('real-shaped booking, lookup and check-in receipts are verified in sequence',async()=>{
  const requests=[];
  const flow=createAppointmentCandidateFlow(session,reference,async(path,opts)=>{
    requests.push({path,opts});
    if(path.endsWith('/check-in'))return respond(200,checked);
    if(opts.method==='GET')return respond(200,booked);
    return respond(201,booked);
  });
  const a=await flow.book(booking);
  assert.equal(a.stage,'BOOKED');
  assert.equal(a.appointmentId,appointment);
  assert.equal(requests[0].opts.headers.Authorization,'Bearer '+token);
  const body=JSON.parse(requests[0].opts.body);
  assert.equal(body.registrationId,registration);
  assert.match(body.requestId,/^[0-9a-f-]{36}$/i);
  assert.equal(JSON.stringify(flow.snapshot()).includes(token),false);
  const b=await flow.reopen(appointment);
  assert.equal(b.appointmentId,appointment);
  const c=await flow.checkIn();
  assert.equal(c.stage,'CHECKED_IN');
  assert.equal(c.revision,2);
  assert.deepEqual(JSON.parse(requests[2].opts.body),{expectedRevision:1});
  assert.equal(flow.snapshot().receipt.stage,'CHECKED_IN');
});

test('failed booking retains same request UUID on deliberate retry',async()=>{
  const ids=[];
  let calls=0;
  const flow=createAppointmentCandidateFlow(session,reference,async(path,opts)=>{
    calls++;
    ids.push(JSON.parse(opts.body).requestId);
    if(calls===1)throw new Error('network');
    return respond(201,booked);
  });
  await assert.rejects(flow.book(booking),code('NETWORK_FAILURE'));
  assert.equal(flow.snapshot().receipt,null);
  await flow.book(booking);
  assert.equal(ids[0],ids[1]);
});

test('after an unknown booking outcome, changing doctor or slot is blocked',async()=>{
  const flow=createAppointmentCandidateFlow(session,reference,async()=>{throw Error('offline');});
  await assert.rejects(flow.book(booking),code('NETWORK_FAILURE'));
  await assert.rejects(flow.book({...booking,slotStart:'09:15'}),code('APPOINTMENT_CONFLICT'));
});

test('forged cross-tenant, patient and appointment receipts fail closed',async()=>{
  const invalid=[
    {...booked,tenantId:id(9)},{...booked,patientId:id(9)},
    {...booked,registrationId:id(9)},{...booked,appointmentId:'DEMO-APT-001'},
  ];
  for(const response of invalid){
    const flow=createAppointmentCandidateFlow(session,reference,
      async()=>respond(201,response));
    await assert.rejects(flow.book(booking),code('UNVERIFIED_RESPONSE'));
    assert.equal(flow.snapshot().receipt,null);
  }
});

test('401, 403, 404 and 409 never produce a fake saved booking',async()=>{
  for(const [status,reason] of [
    [401,'SESSION_EXPIRED'],[403,'NOT_AUTHORIZED'],
    [404,'NOT_FOUND'],[409,'APPOINTMENT_CONFLICT']]){
    const flow=createAppointmentCandidateFlow(session,reference,
      async()=>respond(status,{}));
    await assert.rejects(flow.book(booking),code(reason));
    assert.equal(flow.snapshot().receipt,null);
  }
});

test('role permission, UUID and slot validation reject untrusted inputs before network',async()=>{
  assert.throws(()=>createAppointmentCandidateFlow(
    {...session,permissions:['APPOINTMENT_READ']},reference),code('NOT_AUTHORIZED'));
  assert.throws(()=>createAppointmentCandidateFlow(
    session,{...reference,tenantId:id(7)}),code('NOT_AUTHORIZED'));
  const flow=createAppointmentCandidateFlow(session,reference,
    ()=>assert.fail('Should not contact server'));
  await assert.rejects(flow.book({...booking,doctorMembershipId:'DEMO-D01'}),
    code('INVALID_REQUEST'));
  await assert.rejects(flow.book({...booking,slotStart:'09:07'}),
    code('INVALID_REQUEST'));
});

test('check-in failure discards stale state; signout invalidates pending lookup',async()=>{
  let status=200;
  const flow=createAppointmentCandidateFlow(session,reference,async(path,opts)=>{
    if(path.endsWith('/check-in'))return respond(409,{});
    return respond(opts.method==='GET'?200:201,booked);
  });
  await flow.book(booking);
  await assert.rejects(flow.checkIn(),code('APPOINTMENT_CONFLICT'));
  assert.equal(flow.snapshot().receipt,null);
  let done;
  const another=createAppointmentCandidateFlow(session,reference,()=>
    new Promise(resolve=>{done=resolve}));
  const pending=another.reopen(appointment);
  another.dispose();
  done(respond(200,booked));
  await assert.rejects(pending,code('SESSION_EXPIRED'));
  assert.equal(another.snapshot().receipt,null);
});
