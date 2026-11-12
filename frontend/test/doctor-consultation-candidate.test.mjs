import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createDoctorConsultationCandidateFlow,DoctorDraftError,
} from '../src/doctorConsultationCandidateClient.ts';

const id=n=>'11111111-1111-4111-8111-'+String(n).padStart(12,'0');
const token='a'.repeat(43);
const tenant=id(1),branch=id(2),patient=id(3),appointment=id(4),doctor=id(5);
const session={tenantId:tenant,membershipId:doctor,accessToken:token,
  permissions:['OPD_DRAFT_WRITE']};
const initial={tenantId:tenant,branchId:branch,patientId:patient,
  appointmentId:appointment,doctorMembershipId:doctor,
  stage:'CHECKED_IN',revision:0,consultationNote:''};
const draft={...initial,stage:'CONSULTATION_DRAFT',revision:1,
  consultationNote:'FICTIONAL consultation'};
const respond=(status,body)=>new Response(JSON.stringify(body),{status});
const isCode=code=>e=>e instanceof DoctorDraftError&&e.code===code;

test('doctor reads checked-in appointment and saves first draft revision',async()=>{
  const requests=[];
  const flow=createDoctorConsultationCandidateFlow(session,branch,appointment,
    async(path,options)=>{
      requests.push({path,options});
      return respond(200,options.method==='GET'?initial:draft);
    });
  const opened=await flow.open();
  assert.deepEqual(opened,initial);
  const saved=await flow.save('FICTIONAL consultation');
  assert.deepEqual(saved,draft);
  assert.equal(flow.snapshot().revision,1);
  assert.equal(requests[0].path,'/api/v1/branches/'+branch+
    '/appointments/'+appointment+'/consultation-draft');
  assert.equal(requests[0].options.credentials,'omit');
  assert.equal(requests[0].options.cache,'no-store');
  assert.equal(requests[0].options.headers.Authorization,'Bearer '+token);
  assert.deepEqual(JSON.parse(requests[1].options.body),{
    expectedRevision:0,consultationNote:'FICTIONAL consultation',
  });
  assert.equal(JSON.stringify(flow.snapshot()).includes(token),false);
  assert.equal(JSON.stringify(flow.snapshot()).includes('FICTIONAL consultation'),false);
});

test('second revision verifies optimistic advance and stays in memory',async()=>{
  let rev=1;
  const flow=createDoctorConsultationCandidateFlow(session,branch,appointment,
    async(path,options)=>respond(200,options.method==='GET'?draft:
      {...draft,revision:++rev,consultationNote:'FICTIONAL followup'}));
  await flow.open();
  const current=await flow.save('FICTIONAL followup');
  assert.equal(current.revision,2);
  assert.equal(current.consultationNote,'FICTIONAL followup');
});

test('stale revision or uncertain network response demands fresh GET',async()=>{
  const flow=createDoctorConsultationCandidateFlow(session,branch,appointment,
    async(path,options)=>{
      if(options.method==='GET')return respond(200,initial);
      return respond(409,{code:'APPOINTMENT_CONFLICT'});
    });
  await flow.open();
  await assert.rejects(flow.save('FICTIONAL edit'),isCode('STATE_CONFLICT'));
  assert.equal(flow.snapshot().open,false);
  await assert.rejects(flow.save('FICTIONAL repeated'),isCode('STATE_CONFLICT'));
});

test('forged tenant, patient, appointment and assigned doctor never become draft',async()=>{
  for(const forged of [
    {...initial,tenantId:id(7)},{...initial,branchId:id(7)},
    {...initial,doctorMembershipId:id(7)},{...initial,patientId:'DEMO-P001'},
    {...initial,appointmentId:id(9)},
    {...initial,stage:'CONSULTATION_DRAFT',revision:0,consultationNote:'x'},
  ]){
    const flow=createDoctorConsultationCandidateFlow(session,branch,appointment,
      async()=>respond(200,forged));
    await assert.rejects(flow.open(),isCode('UNVERIFIED_RESPONSE'));
    assert.equal(flow.snapshot().open,false);
  }
});

test('reception permissions, malformed UUID and invalid notes fail before any write',async()=>{
  assert.throws(()=>createDoctorConsultationCandidateFlow(
    {...session,permissions:['APPOINTMENT_READ']},branch,appointment),
    isCode('NOT_AUTHORIZED'));
  assert.throws(()=>createDoctorConsultationCandidateFlow(
    session,branch,'DEMO-APPOINTMENT'),isCode('NOT_AUTHORIZED'));
  let calls=0;
  const flow=createDoctorConsultationCandidateFlow(session,branch,appointment,
    async()=>{calls++;return respond(200,initial);});
  await flow.open();
  for(const note of ['','  ','x'.repeat(2001)]){
    await assert.rejects(flow.save(note),isCode('INVALID_REQUEST'));
  }
  assert.equal(calls,1);
});

test('401,403 and 409 are fail-closed with no clinical note exposure',async()=>{
  for(const [status,code] of [[401,'SESSION_EXPIRED'],[403,'NOT_AUTHORIZED'],
      [409,'STATE_CONFLICT']]){
    const flow=createDoctorConsultationCandidateFlow(session,branch,appointment,
      async()=>respond(status,{secret:'FICTIONAL patient name'}));
    await assert.rejects(flow.open(),isCode(code));
    assert.equal(flow.snapshot().open,false);
  }
});

test('logout invalidates in-flight draft read and prevents its reuse',async()=>{
  let resolve;
  const flow=createDoctorConsultationCandidateFlow(session,branch,appointment,
    ()=>new Promise(done=>{resolve=done;}));
  const pending=flow.open();
  flow.dispose();
  resolve(respond(200,initial));
  await assert.rejects(pending,isCode('SESSION_EXPIRED'));
  assert.equal(flow.snapshot().open,false);
  await assert.rejects(flow.open(),isCode('SESSION_EXPIRED'));
});
