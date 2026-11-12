import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createConnectedRegistrationFlow, ConnectedFlowError,
} from '../src/connectedRegistrationFlow.ts';
import {RegistrationError} from '../src/registrationClient.ts';

const uuid = n => '11111111-1111-4111-8111-' + String(n).padStart(12,'0');
const branch=uuid(3);
const session=Object.freeze({
  tenantId:uuid(1),membershipId:uuid(2),userId:uuid(9),
  username:'test_reception',accessToken:'a'.repeat(43),
  permissions:['PATIENT_REGISTER','PATIENT_REGISTRATION_READ'],roles:['RECEPTION'],
});
const intake=()=>({name:' Fictional   Sample ',precision:'FULL',dob:'1990-02-03',phone:''});
const receipt={patientId:uuid(4),registrationId:uuid(5),mrn:'PAT-0123456789AB',replayed:false};
const json=(body,status=201)=>new Response(JSON.stringify(body),{status});
const denied=(code)=>e=>e instanceof ConnectedFlowError && e.code===code;

test('authorized staff review -> server receipt -> verified journey -> next patient',async()=>{
  const sent=[];
  const flow=createConnectedRegistrationFlow(session,branch,async(url,options)=>{
    sent.push({url,options});
    if(options.method==='GET')return json({
      tenantId:session.tenantId,branchId:branch,
      patientId:receipt.patientId,registrationId:receipt.registrationId,
    },200);
    return json({...receipt});
  });
  assert.equal(flow.snapshot().status,'IDLE');
  const review=flow.prepare(intake());
  assert.equal(review.displayName,'Fictional Sample');
  assert.equal(review.dateOfBirth,'1990-02-03');
  assert.equal(Object.isFrozen(review),true);
  assert.equal(flow.snapshot().status,'REVIEW');
  const saved=await flow.submitReviewed();
  assert.equal(saved.stage,'REGISTERED');
  assert.equal(saved.tenantId,session.tenantId);
  assert.equal(saved.branchId,branch);
  assert.equal(saved.patientId,receipt.patientId);
  assert.equal(saved.registrationId,receipt.registrationId);
  assert.equal(Object.isFrozen(saved),true);
  assert.equal(sent[0].options.method,'POST');
  assert.equal(sent[0].options.credentials,'omit');
  assert.equal(await flow.verifySavedRegistration(),true);
  assert.equal(sent[1].options.method,'GET');
  assert.equal(flow.snapshot().journey.mrn,receipt.mrn);
  assert.equal(JSON.stringify(flow.snapshot()).includes(session.accessToken),false);
  flow.nextPatient();
  assert.deepEqual(flow.snapshot(),{status:'IDLE',journey:null,lastError:null});
});

test('uncertain write keeps exact request and idempotency key across explicit retry',async()=>{
  const submissions=[];
  const flow=createConnectedRegistrationFlow(session,branch,async(url,options)=>{
    submissions.push({url,options});
    if(submissions.length===1)throw new Error('secret patient data');
    return json({...receipt,replayed:true},200);
  });
  flow.prepare(intake());
  await assert.rejects(flow.submitReviewed(),e=>
    e instanceof RegistrationError && e.code==='OUTCOME_UNKNOWN');
  assert.equal(flow.snapshot().status,'UNCERTAIN');
  assert.throws(()=>flow.prepare({...intake(),name:'Another Patient'}),
    denied('UNRESOLVED_SUBMISSION'));
  assert.throws(()=>flow.nextPatient(),denied('UNRESOLVED_SUBMISSION'));
  const saved=await flow.submitReviewed();
  assert.equal(saved.stage,'REGISTERED');
  assert.equal(submissions.length,2);
  assert.equal(submissions[0].options.headers['Idempotency-Key'],
    submissions[1].options.headers['Idempotency-Key']);
  assert.equal(submissions[0].options.body,submissions[1].options.body);
  assert.equal(JSON.stringify(flow.snapshot()).includes(session.accessToken),false);
});

test('double confirm sends at most one in-flight POST',async()=>{
  let resolve; let count=0;
  const flow=createConnectedRegistrationFlow(session,branch,async()=>{
    count++;
    return new Promise(done=>{resolve=done;});
  });
  flow.prepare(intake());
  const a=flow.submitReviewed(),b=flow.submitReviewed();
  assert.equal(a,b);
  resolve(json(receipt));
  assert.deepEqual(await a,await b);
  assert.equal(count,1);
});

test('permission and branch gates reject demo identities without network calls',()=>{
  const deniedStaff={...session,permissions:['PATIENT_REGISTRATION_READ']};
  assert.throws(()=>createConnectedRegistrationFlow(deniedStaff,branch,
    ()=>assert.fail('network call')),denied('NOT_AUTHORIZED'));
  assert.throws(()=>createConnectedRegistrationFlow(session,'DEMO-BRANCH',
    ()=>assert.fail('network call')),denied('NOT_AUTHORIZED'));
});

test('contact storage is not supported and changing review never posts',async()=>{
  let calls=0;
  const flow=createConnectedRegistrationFlow(session,branch,async()=>{calls++;return json(receipt);});
  assert.throws(()=>flow.prepare({...intake(),phone:'9000000000'}),
    e=>e instanceof RegistrationError && e.code==='CONTACT_NOT_SUPPORTED');
  flow.prepare(intake());
  flow.cancelReview();
  assert.equal(flow.snapshot().status,'IDLE');
  assert.equal(calls,0);
  await assert.rejects(flow.submitReviewed(),denied('REVIEW_REQUIRED'));
});

test('server conflict never creates a demo fallback identity',async()=>{
  const flow=createConnectedRegistrationFlow(session,branch,
    async()=>json({code:'DUPLICATE_REVIEW_REQUIRED'},409));
  flow.prepare(intake());
  await assert.rejects(flow.submitReviewed(),
    e=>e instanceof RegistrationError && e.code==='DUPLICATE_REVIEW_REQUIRED');
  assert.equal(flow.snapshot().status,'UNCERTAIN');
  assert.equal(flow.snapshot().journey,null);
  assert.equal(flow.snapshot().lastError,'DUPLICATE_REVIEW_REQUIRED');
});

test('read-back requires explicit read permission and matches patient identity',async()=>{
  const writeOnly={...session,permissions:['PATIENT_REGISTER']};
  const flow=createConnectedRegistrationFlow(writeOnly,branch,
    async()=>json(receipt));
  flow.prepare(intake());
  await flow.submitReviewed();
  await assert.rejects(flow.verifySavedRegistration(),denied('NOT_AUTHORIZED'));
  const separate=createConnectedRegistrationFlow(session,branch,async(_url,options)=>
    options.method==='GET'?
      json({tenantId:session.tenantId,branchId:branch,registrationId:receipt.registrationId,patientId:uuid(8)},200):
      json(receipt));
  separate.prepare(intake());
  await separate.submitReviewed();
  await assert.rejects(separate.verifySavedRegistration(),denied('READBACK_MISMATCH'));
  assert.equal(separate.snapshot().lastError,'READBACK_MISMATCH');
  assert.throws(()=>separate.nextPatient(),denied('READBACK_MISMATCH'));
});
