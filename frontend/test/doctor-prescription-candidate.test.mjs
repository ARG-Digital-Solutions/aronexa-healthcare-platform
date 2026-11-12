import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createDoctorPrescriptionCandidateFlow,DoctorRxError,
} from '../src/doctorPrescriptionCandidateClient.ts';

const id=n=>'11111111-1111-4111-8111-'+String(n).padStart(12,'0');
const tenant=id(1),branch=id(2),doctor=id(3),patient=id(4),
  appointment=id(5),entry=id(6),token='a'.repeat(43);
const session={tenantId:tenant,membershipId:doctor,accessToken:token,
  permissions:['OPD_DRAFT_WRITE','OPD_RX_DRAFT_WRITE']};
const context={tenantId:tenant,branchId:branch,appointmentId:appointment,
  patientId:patient,doctorMembershipId:doctor,appointmentStage:'CHECKED_IN'};
const consultation={tenantId:tenant,branchId:branch,appointmentId:appointment,
  patientId:patient,doctorMembershipId:doctor,stage:'CONSULTATION_DRAFT',
  revision:1,consultationNote:'FICTIONAL consultation'};
const medicine={id:entry,medicine:'Fictional Medicine A',
  strength:'FICTIONAL 1',form:'tablet',dose:'doctor specified',
  route:'oral',frequency:'doctor specified',duration:'doctor specified',
  quantity:1,instructions:'NOT FOR CLINICAL USE'};
const rxLine=({id:entryId,...other})=>({entryId,...other});
const empty={tenantId:tenant,branchId:branch,appointmentId:appointment,
  patientId:patient,doctorMembershipId:doctor,
  stage:'EMPTY_DRAFT',revision:0,consultationRevision:1,medicines:[]};
const first={...empty,stage:'RX_DRAFT',revision:1,medicines:[rxLine(medicine)]};
const response=(status,json)=>new Response(JSON.stringify(json),{status});
const code=x=>e=>e instanceof DoctorRxError&&e.code===x;
function mockRx(handler,{person=patient,consultationRevision=1}={}){
  const requests=[];
  const transport=async(path,options)=>{
    requests.push({path,options});
    if(path.endsWith('/consultation-context'))
      return response(200,{...context,patientId:person});
    if(path.endsWith('/consultation-draft'))
      return response(200,{...consultation,patientId:person,
        revision:consultationRevision});
    return handler(path,options);
  };
  return {requests,transport};
}

test('doctor opens empty draft from independently authorized consultation then saves',async()=>{
  const testBackend=mockRx((path,options)=>
    response(200,options.method==='GET'?empty:first));
  const flow=createDoctorPrescriptionCandidateFlow(
    session,branch,appointment,testBackend.transport);
  const opened=await flow.open();
  assert.equal(opened.stage,'EMPTY_DRAFT');
  assert.equal(opened.revision,0);
  assert.deepEqual(opened.medicines,[]);
  const result=await flow.save([medicine]);
  assert.equal(result.stage,'RX_DRAFT');
  assert.equal(result.revision,1);
  assert.equal(result.medicines[0].medicine,'Fictional Medicine A');
  assert.deepEqual(testBackend.requests.map(x=>x.path.split('/').at(-1)),[
    'consultation-context','consultation-draft',
    'prescription-draft','prescription-draft']);
  assert.deepEqual(JSON.parse(testBackend.requests[3].options.body),{
    expectedRevision:0,consultationRevision:1,medicines:[rxLine(medicine)],
  });
  assert.equal(testBackend.requests[3].options.cache,'no-store');
  assert.equal(testBackend.requests[3].options.credentials,'omit');
  assert.equal(JSON.stringify(flow.snapshot()).includes(token),false);
  assert.equal(JSON.stringify(flow.snapshot()).includes('Fictional Medicine'),false);
});

test('saved Rx can be reloaded and revised with an immutable new snapshot',async()=>{
  let r=1;
  const backend=mockRx((path,options)=>response(200,
    options.method==='GET'?first:{...first,revision:++r}));
  const flow=createDoctorPrescriptionCandidateFlow(
    session,branch,appointment,backend.transport);
  const opened=await flow.open();
  assert.equal(opened.revision,1);
  const updated=await flow.save([medicine]);
  assert.equal(updated.revision,2);
  assert.equal(flow.snapshot().revision,2);
});

test('forged valid patient UUID in prescription never passes context validation',async()=>{
  const backend=mockRx(()=>response(200,{...first,patientId:id(99)}));
  const flow=createDoctorPrescriptionCandidateFlow(
    session,branch,appointment,backend.transport);
  await assert.rejects(flow.open(),code('UNVERIFIED_RESPONSE'));
  assert.equal(flow.snapshot().open,false);
});

test('wrong identity or consultation stage stops before prescription network read',async()=>{
  for(const [overrides,consultOverride] of [
    [{}, {patientId:id(99)}],
    [{}, {stage:'CHECKED_IN',revision:0,consultationNote:''}],
  ]){
    let rxCalls=0;
    const b=mockRx(()=>{rxCalls++;return response(200,empty);},overrides);
    const original=b.transport;
    const transport=async(path,options)=>path.endsWith('/consultation-draft')
      ?response(200,{...consultation,...consultOverride})
      :original(path,options);
    const flow=createDoctorPrescriptionCandidateFlow(session,branch,appointment,transport);
    if(consultOverride.stage)
      await assert.rejects(flow.open(),code('STATE_CONFLICT'));
    else
      await assert.rejects(flow.open(),code('UNVERIFIED_RESPONSE'));
    assert.equal(rxCalls,0);
  }
});

test('Rx response must match consultation revision and assigned doctor',async()=>{
  for(const forged of [
    {...empty,consultationRevision:2},{...empty,tenantId:id(99)},
    {...empty,doctorMembershipId:id(99)},
    {...empty,appointmentId:id(99)},
    {...empty,medicines:[rxLine(medicine)]},
    {...first,medicines:[{...rxLine(medicine),medicine:'   '}]},
    {...first,medicines:[{...rxLine(medicine),entryId:id(99)},
      {...rxLine(medicine),entryId:id(99)}]},
  ]){
    const flow=createDoctorPrescriptionCandidateFlow(
      session,branch,appointment,mockRx(()=>response(200,forged)).transport);
    await assert.rejects(flow.open(),code('UNVERIFIED_RESPONSE'));
    assert.equal(flow.snapshot().open,false);
  }
});

test('stale HTTP 409 or unverified save discards local Rx and requires reload',async()=>{
  for(const returned of [
    response(409,{code:'APPOINTMENT_CONFLICT'}),
    response(200,{...first,patientId:id(9)}),
  ]){
    const flow=createDoctorPrescriptionCandidateFlow(
      session,branch,appointment,mockRx((path,options)=>
        options.method==='GET'?response(200,empty):returned).transport);
    await flow.open();
    await assert.rejects(flow.save([medicine]));
    assert.equal(flow.snapshot().open,false);
    await assert.rejects(flow.save([medicine]),code('STATE_CONFLICT'));
  }
});

test('invalid and duplicate medicines rejected before network writes',async()=>{
  let count=0;
  const backend=mockRx((path,options)=>{
    if(options.method==='POST')count++;
    return response(200,empty);
  });
  const flow=createDoctorPrescriptionCandidateFlow(
    session,branch,appointment,backend.transport);
  await flow.open();
  for(const list of [[],[medicine,medicine],[{...medicine,dose:' '}],
    [{...medicine,quantity:0}],[{...medicine,id:'DEMO-MED'}],
    Array.from({length:31},(_,i)=>({...medicine,id:id(i+100)}))]){
    await assert.rejects(flow.save(list),code('INVALID_REQUEST'));
  }
  assert.equal(count,0);
});

test('doctor Rx permission cannot be borrowed from reception or consultation-only session',()=>{
  for(const permissions of [['OPD_DRAFT_WRITE'],['APPOINTMENT_READ'],
    ['OPD_RX_DRAFT_WRITE']]){
    assert.throws(()=>createDoctorPrescriptionCandidateFlow(
      {...session,permissions},branch,appointment),code('NOT_AUTHORIZED'));
  }
});

test('Rx denied HTTP statuses and disposed workflows never retain draft',async()=>{
  for(const [http,reason] of [
    [401,'SESSION_EXPIRED'],[403,'NOT_AUTHORIZED'],
    [409,'STATE_CONFLICT']]){
    const flow=createDoctorPrescriptionCandidateFlow(
      session,branch,appointment,mockRx(()=>response(http,{code:'DENIED'})).transport);
    await assert.rejects(flow.open(),code(reason));
    assert.equal(flow.snapshot().open,false);
  }
  const flow=createDoctorPrescriptionCandidateFlow(
    session,branch,appointment,mockRx(()=>response(200,empty)).transport);
  await flow.open();
  flow.dispose();
  assert.equal(flow.snapshot().open,false);
  await assert.rejects(flow.save([medicine]),code('SESSION_EXPIRED'));
});
