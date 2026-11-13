import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createBillingCandidateFlow,loadDailyBillingCandidate,rupeesToPaise,BillingError,
} from '../src/billingCandidateClient.ts';

const id=n=>'11111111-1111-4111-8111-'+String(n).padStart(12,'0');
const tenant=id(1),branch=id(2),patient=id(3),appointment=id(4),
  doctor=id(5),invoice=id(6),token='a'.repeat(43);
const session={tenantId:tenant,membershipId:id(9),accessToken:token,
  roles:['BILLING'],permissions:['OPD_BILL_READ','OPD_BILL_CREATE',
    'OPD_PAYMENT_POST','OPD_DAILY_REPORT_READ']};
const context={tenantId:tenant,branchId:branch,patientId:patient,
  appointmentId:appointment,doctorMembershipId:doctor,
  serviceCode:'OPD-CONSULTATION',
  serviceLabel:'Fictional OPD Consultation',amountPaise:50000};
const bill={...context,invoiceId:invoice,invoiceNumber:1,
  totalPaise:50000,paidPaise:0,outstandingPaise:50000,
  status:'UNPAID',replayed:false};
const partial={...bill,paidPaise:20000,outstandingPaise:30000,status:'PARTIAL'};
const report={date:'2026-11-13',tenantId:tenant,branchId:branch,
  invoiceCount:1,billedPaise:50000,collectionsPaise:20000,
  outstandingPaise:30000};
const reply=(status,body)=>status===404?new Response(null,{status}):
  new Response(JSON.stringify(body),{status});
const isCode=code=>e=>e instanceof BillingError&&e.code===code;
function harness({contextValue=context,readBill=null,createBill=bill,
                  payment=partial,postStatus=200}={}){
  let requests=[];
  const transport=async(path,init)=>{
    requests.push({path,init});
    if(path.endsWith('/billing-context'))return reply(200,contextValue);
    if(path.endsWith('/bill')&&init.method==='GET')
      return readBill?reply(200,readBill):reply(404,null);
    if(path.endsWith('/bill')&&init.method==='POST')
      return reply(201,createBill);
    if(path.endsWith('/bill/cash')&&init.method==='POST')
      return reply(postStatus,payment);
    if(path.includes('/billing/daily?date='))return reply(200,report);
    throw Error('Unknown test path: '+path);
  };
  return {transport,requests};
}

test('server-priced bill open, create, cash partial and immutable references',async()=>{
  const backend=harness();
  const flow=createBillingCandidateFlow(session,branch,appointment,backend.transport);
  const opened=await flow.open();
  assert.deepEqual(opened.context,context);
  assert.equal(opened.bill,null);
  assert.equal(flow.snapshot().open,true);
  const created=await flow.create();
  assert.equal(created.invoiceId,invoice);
  assert.equal(created.totalPaise,50000);
  assert.equal(created.invoiceNumber,1);
  const paid=await flow.cash(20000);
  assert.equal(paid.status,'PARTIAL');
  assert.equal(paid.outstandingPaise,30000);
  assert.deepEqual(backend.requests.map(x=>x.path.split('/').at(-1)),
    ['billing-context','bill','bill','cash']);
  assert.equal(backend.requests[0].init.cache,'no-store');
  assert.equal(backend.requests[0].init.credentials,'omit');
  const create=JSON.parse(backend.requests[2].init.body);
  assert.deepEqual(Object.keys(create).sort(),['requestId','serviceCode']);
  assert.equal(create.serviceCode,'OPD-CONSULTATION');
  assert.match(create.requestId,/^[0-9a-f-]{36}$/i);
  const cash=JSON.parse(backend.requests[3].init.body);
  assert.equal(cash.invoiceId,invoice);
  assert.equal(cash.amountPaise,20000);
  assert.equal(JSON.stringify(flow.snapshot()).includes(token),false);
  assert.equal(JSON.stringify(flow.snapshot()).includes(patient),false);
});

test('reopening a previously recorded payment restores server balance',async()=>{
  const backend=harness({readBill:partial});
  const flow=createBillingCandidateFlow(session,branch,appointment,backend.transport);
  const result=await flow.open();
  assert.equal(result.bill.paidPaise,20000);
  assert.equal(result.bill.outstandingPaise,30000);
  assert.equal(flow.snapshot().outstandingPaise,30000);
});

test('valid forged patient UUID and altered server price are rejected',async()=>{
  for(const forged of [
    {...bill,patientId:id(77)},{...bill,appointmentId:id(77)},
    {...bill,doctorMembershipId:id(77)},{...bill,tenantId:id(77)},
    {...bill,totalPaise:49999},{...bill,invoiceNumber:0},
    {...bill,status:'PAID'}, {...bill,paidPaise:40000},
  ]){
    const b=harness({readBill:forged});
    const flow=createBillingCandidateFlow(session,branch,appointment,b.transport);
    await assert.rejects(flow.open(),isCode('UNVERIFIED_RESPONSE'));
    assert.equal(flow.snapshot().open,false);
  }
});

test('mismatched independent billing context denies before invoice read',async()=>{
  for(const forged of [
    {...context,patientId:'DEMO-PATIENT'},
    {...context,tenantId:id(77)},
    {...context,branchId:id(77)},
    {...context,appointmentId:id(77)},
    {...context,serviceCode:'OTHER'},
    {...context,amountPaise:0},
  ]){
    const b=harness({contextValue:forged});
    const flow=createBillingCandidateFlow(session,branch,appointment,b.transport);
    await assert.rejects(flow.open(),isCode('UNVERIFIED_RESPONSE'));
    assert.equal(b.requests.length,1);
  }
});

test('client never allows overpay, fractional paise or zero amount',async()=>{
  for(const [value,want] of [
    ['500','50000'],['12.50','1250'],['0.01','1']]){
    assert.equal(String(rupeesToPaise(value)),want);
  }
  for(const v of ['0','-1','1.234','NaN','1e5','  ','.5'])
    assert.throws(()=>rupeesToPaise(v),isCode('INVALID_REQUEST'));
  const b=harness({readBill:partial});
  const flow=createBillingCandidateFlow(session,branch,appointment,b.transport);
  await flow.open();
  for(const amount of [0,30001,-1,0.5,Infinity])
    await assert.rejects(flow.cash(amount),isCode('INVALID_REQUEST'));
  assert.equal(b.requests.some(x=>x.path.endsWith('/cash')),false);
});

test('conflicting or forged payment response discards local bill before retry',async()=>{
  for(const [status,payment] of [
    [409,partial],[200,{...partial,patientId:id(77)}],
    [200,{...partial,paidPaise:10000,outstandingPaise:40000}],
  ]){
    const b=harness({readBill:bill,postStatus:status,payment});
    const flow=createBillingCandidateFlow(session,branch,appointment,b.transport);
    await flow.open();
    await assert.rejects(flow.cash(20000));
    assert.equal(flow.snapshot().open,false);
    await assert.rejects(flow.cash(20000),isCode('STATE_CONFLICT'));
  }
});

test('authorization boundary blocks non-billing and missing report permissions',async()=>{
  for(const modified of [
    {...session,roles:['RECEPTION']},
    {...session,roles:['DOCTOR']},
    {...session,permissions:['OPD_DAILY_REPORT_READ']},
  ]){
    assert.throws(()=>createBillingCandidateFlow(
      modified,branch,appointment),isCode('NOT_AUTHORIZED'));
  }
  await assert.rejects(loadDailyBillingCandidate(
    {...session,permissions:['OPD_BILL_READ']},branch,'2026-11-13'),
    isCode('NOT_AUTHORIZED'));
});

test('daily report is server-derived, validated and exact branch scoped',async()=>{
  const b=harness();
  const result=await loadDailyBillingCandidate(
    session,branch,'2026-11-13',b.transport);
  assert.deepEqual(result,report);
  assert.equal(b.requests[0].path,
    '/api/v1/branches/'+branch+'/billing/daily?date=2026-11-13');
  for(const value of ['2026-02-30','2026-13-01','abc','2026/11/13'])
    await assert.rejects(loadDailyBillingCandidate(
      session,branch,value,b.transport),isCode('INVALID_REQUEST'));
  const tampered=async()=>reply(200,{...report,branchId:id(77)});
  await assert.rejects(loadDailyBillingCandidate(
    session,branch,'2026-11-13',tampered),isCode('UNVERIFIED_RESPONSE'));
});

test('HTTP denial or revoked session does not expose invoice information',async()=>{
  for(const [status,expected] of [
    [401,'SESSION_EXPIRED'],[403,'NOT_AUTHORIZED'],
    [409,'STATE_CONFLICT'],
  ]){
    const flow=createBillingCandidateFlow(session,branch,appointment,
      async()=>reply(status,{secret:'FICTIONAL patient record'}));
    await assert.rejects(flow.open(),isCode(expected));
    assert.equal(flow.snapshot().open,false);
  }
});

test('dispose clears verified bill and denies further operations',async()=>{
  const b=harness({readBill:bill});
  const flow=createBillingCandidateFlow(session,branch,appointment,b.transport);
  await flow.open();
  flow.dispose();
  assert.equal(flow.snapshot().open,false);
  await assert.rejects(flow.open(),isCode('SESSION_EXPIRED'));
  await assert.rejects(flow.cash(10),isCode('SESSION_EXPIRED'));
});
