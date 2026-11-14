import test from 'node:test';
import assert from 'node:assert/strict';
import {
  bookProductionOpd,checkInProductionOpd,ProductionBookingError,
} from '../src/productionOpdBookingClient.ts';

const id=n=>'11111111-1111-4111-8111-'+String(n).padStart(12,'0');
const intake=Object.freeze({tenantId:id(1),branchId:id(2),
  registrationId:id(3),patientId:id(4),status:'REGISTRATION_VERIFIED'});
const session=Object.freeze({tenantId:id(1),accessToken:'A'.repeat(43),
  permissions:['APPOINTMENT_BOOK','PATIENT_REGISTRATION_READ',
    'APPOINTMENT_CHECK_IN']});
const input=Object.freeze({doctorMembershipId:id(5),requestId:id(6),
  visitDate:'2026-11-14',slotStart:'10:30'});
const booked=Object.freeze({...intake,appointmentId:id(7),
  doctorMembershipId:id(5),stage:'BOOKED',revision:1,replayed:false});
const checked=Object.freeze({...booked,stage:'CHECKED_IN',revision:2});
const json=(value,status)=>new Response(JSON.stringify(value),
  {status,headers:{'Content-Type':'application/json'}});
const deny=async(promise,code)=>assert.rejects(promise,e=>
  e instanceof ProductionBookingError&&e.code===code);

test('BOOK posts only linked registration/doctor/date/slot/stable request ID',async()=>{
  let calls=0;
  const receipt=await bookProductionOpd(session,intake,input,async(path,init)=>{
    calls++;
    assert.equal(path,'/api/v1/branches/'+id(2)+'/opd/appointments');
    assert.equal(init.method,'POST');
    assert.equal(init.credentials,'omit');
    assert.equal(init.headers.Authorization,'Bearer '+session.accessToken);
    const request=JSON.parse(init.body);
    assert.deepEqual(request,{registrationId:id(3),
      doctorMembershipId:id(5),visitDate:'2026-11-14',
      slotStart:'10:30',requestId:id(6)});
    assert.ok(!JSON.stringify(request).includes(id(4)));
    return json({...booked,untrusted:'not passed through'},201);
  });
  assert.equal(calls,1);
  assert.deepEqual(receipt,booked);
  assert.equal(Object.isFrozen(receipt),true);
});

test('CHECK IN posts revision + request ID and checks same server object',async()=>{
  const result=await checkInProductionOpd(session,intake,booked,id(8),
    async(path,init)=>{
      assert.equal(path,'/api/v1/branches/'+id(2)
        +'/opd/appointments/'+id(7)+'/check-in');
      assert.deepEqual(JSON.parse(init.body),{expectedRevision:1,requestId:id(8)});
      return json(checked,200);
    });
  assert.deepEqual(result,checked);
});

test('no HTTP when caller role or input is invalid',async()=>{
  let calls=0;
  const fake=async()=>{calls++;return json(booked,201)};
  for(const [s,i,r] of [
    [{...session,permissions:['APPOINTMENT_BOOK']},intake,input],
    [{...session,tenantId:id(9)},intake,input],
    [{...session,accessToken:'bad'},intake,input],
    [session,{...intake,status:'BOOKED'},input],
  ]) await deny(bookProductionOpd(s,i,r,fake),'ACCESS_DENIED');
  for(const invalid of [{...input,doctorMembershipId:'bad'},
     {...input,requestId:'bad'},{...input,slotStart:'11:03'}])
    await deny(bookProductionOpd(session,intake,invalid,fake),'INVALID_REQUEST');
  await deny(checkInProductionOpd({...session,
    permissions:['APPOINTMENT_BOOK','PATIENT_REGISTRATION_READ']},
    intake,booked,id(8),fake),'ACCESS_DENIED');
  assert.equal(calls,0);
});

test('rejects forged tenant/branch/patient/doctor and unverified stages',async()=>{
  for(const altered of [
    {...booked,tenantId:id(9)},{...booked,branchId:id(9)},
    {...booked,patientId:id(9)},{...booked,registrationId:id(9)},
    {...booked,doctorMembershipId:id(9)},
    {...booked,stage:'CONSULTED'},
  ])await deny(bookProductionOpd(session,intake,input,
    async()=>json(altered,201)),'UNVERIFIED');
  await deny(checkInProductionOpd(session,intake,booked,id(8),
    async()=>json({...checked,revision:3},200)),'UNVERIFIED');
});

test('deny errors, disabled writer, network ambiguity without response leakage',async()=>{
  for(const [status,code] of [[401,'SESSION_EXPIRED'],
    [403,'ACCESS_DENIED'],[409,'CONFLICT_OR_STALE'],
    [503,'WRITER_NOT_AVAILABLE'],[500,'UNVERIFIED']]){
    await deny(bookProductionOpd(session,intake,input,
      async()=>json({secret:'never surface'},status)),code);
  }
  await deny(bookProductionOpd(session,intake,input,
    async()=>{throw Error('sensitive server exception')}),'UNVERIFIED');
});

test('same stable booking request ID is caller-owned for retry',async()=>{
  const bodies=[];
  for(let n=0;n<2;n++){
    const receipt=await bookProductionOpd(session,intake,input,
      async(_path,init)=>{
        bodies.push(JSON.parse(init.body).requestId);
        return json({...booked,replayed:n>0},n>0?200:201);
      });
    assert.equal(receipt.appointmentId,id(7));
  }
  assert.deepEqual(bodies,[id(6),id(6)]);
});
