import type {StaffSession} from './staffSessionClient.ts';

type Transport=(url:string,options:RequestInit)=>Promise<Response>;
const network:Transport=(url,options)=>fetch(url,options);
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const date=/^\d{4}-\d{2}-\d{2}$/;
const bearer=/^[A-Za-z0-9_-]{43}$/;
const validId=(x:unknown):x is string=>typeof x==='string'&&uuid.test(x)
  &&x.toLowerCase()!=='00000000-0000-0000-0000-000000000000';
const money=(x:unknown):x is number=>Number.isSafeInteger(x)
  &&typeof x==='number'&&x>=0&&x<=10_000_000_000;

export type BillingErrorCode='NOT_AUTHORIZED'|'SESSION_EXPIRED'|
  'STATE_CONFLICT'|'INVALID_REQUEST'|'NOT_FOUND'|
  'UNVERIFIED_RESPONSE'|'NETWORK_FAILURE';
const descriptions:Record<BillingErrorCode,string>={
  NOT_AUTHORIZED:'This clinic session is not authorized for OPD billing.',
  SESSION_EXPIRED:'Your billing session is inactive. Sign in again.',
  STATE_CONFLICT:'Billing information changed. Reopen and verify before retrying.',
  INVALID_REQUEST:'Enter a valid clinic appointment and positive amount.',
  NOT_FOUND:'No OPD bill exists yet for this appointment.',
  UNVERIFIED_RESPONSE:'The clinic bill could not be verified. Do not proceed.',
  NETWORK_FAILURE:'The server did not confirm this entry. Reopen the ledger before another attempt.',
};
export class BillingError extends Error{
  readonly code:BillingErrorCode;
  constructor(code:BillingErrorCode){
    super(descriptions[code]);this.name='BillingError';this.code=code;
  }
}
export interface BillingContext{
  readonly tenantId:string;readonly branchId:string;readonly patientId:string;
  readonly appointmentId:string;readonly doctorMembershipId:string;
  readonly serviceCode:'OPD-CONSULTATION';
  readonly serviceLabel:string;readonly amountPaise:number;
}
export interface BillReceipt{
  readonly tenantId:string;readonly branchId:string;readonly patientId:string;
  readonly appointmentId:string;readonly doctorMembershipId:string;
  readonly invoiceId:string;readonly invoiceNumber:number;
  readonly serviceCode:'OPD-CONSULTATION';readonly serviceLabel:string;
  readonly totalPaise:number;readonly paidPaise:number;
  readonly outstandingPaise:number;readonly status:'UNPAID'|'PARTIAL'|'PAID';
  readonly replayed:boolean;
}
export interface DailyCollections{
  readonly date:string;readonly tenantId:string;readonly branchId:string;
  readonly invoiceCount:number;readonly billedPaise:number;
  readonly collectionsPaise:number;readonly outstandingPaise:number;
}
export function rupeesToPaise(value:string):number{
  if(typeof value!=='string'||! /^\d{1,8}(\.\d{1,2})?$/.test(value.trim()))
    throw new BillingError('INVALID_REQUEST');
  const [whole,frac='']=value.trim().split('.');
  const total=Number(whole)*100+Number(frac.padEnd(2,'0'));
  if(!Number.isSafeInteger(total)||total<1||total>10_000_000_000)
    throw new BillingError('INVALID_REQUEST');
  return total;
}
function contextFrom(raw:unknown,session:StaffSession,
                     branchId:string,appointmentId:string):BillingContext{
  if(!raw||typeof raw!=='object'||Array.isArray(raw))
    throw new BillingError('UNVERIFIED_RESPONSE');
  const r=raw as Record<string,unknown>;
  if(r.tenantId!==session.tenantId||r.branchId!==branchId
    ||r.appointmentId!==appointmentId||!validId(r.patientId)
    ||!validId(r.doctorMembershipId)||r.serviceCode!=='OPD-CONSULTATION'
    ||typeof r.serviceLabel!=='string'||!r.serviceLabel.trim()
    ||r.serviceLabel.length>100
    ||!money(r.amountPaise)||r.amountPaise<1)
    throw new BillingError('UNVERIFIED_RESPONSE');
  return Object.freeze({
    tenantId:session.tenantId,branchId,appointmentId,
    patientId:r.patientId,doctorMembershipId:r.doctorMembershipId,
    serviceCode:'OPD-CONSULTATION',serviceLabel:r.serviceLabel,
    amountPaise:r.amountPaise,
  });
}
function receiptFrom(raw:unknown,context:BillingContext):BillReceipt{
  if(!raw||typeof raw!=='object'||Array.isArray(raw))
    throw new BillingError('UNVERIFIED_RESPONSE');
  const r=raw as Record<string,unknown>;
  if(r.tenantId!==context.tenantId||r.branchId!==context.branchId
    ||r.appointmentId!==context.appointmentId
    ||r.patientId!==context.patientId
    ||r.doctorMembershipId!==context.doctorMembershipId
    ||!validId(r.invoiceId)
    ||!Number.isSafeInteger(r.invoiceNumber)||Number(r.invoiceNumber)<1
    ||r.serviceCode!==context.serviceCode
    ||r.serviceLabel!==context.serviceLabel
    ||r.totalPaise!==context.amountPaise
    ||!money(r.paidPaise)||!money(r.outstandingPaise)
    ||Number(r.paidPaise)+Number(r.outstandingPaise)!==context.amountPaise
    ||(r.status!=='UNPAID'&&r.status!=='PARTIAL'&&r.status!=='PAID')
    ||r.status!==(r.paidPaise===0?'UNPAID':
         r.outstandingPaise===0?'PAID':'PARTIAL')
    ||typeof r.replayed!=='boolean')
    throw new BillingError('UNVERIFIED_RESPONSE');
  return Object.freeze({
    ...context,invoiceId:r.invoiceId,invoiceNumber:Number(r.invoiceNumber),
    totalPaise:context.amountPaise,paidPaise:Number(r.paidPaise),
    outstandingPaise:Number(r.outstandingPaise),
    status:r.status,replayed:r.replayed,
  });
}
async function request(transport:Transport,session:StaffSession,
                       path:string,method:'GET'|'POST',body?:unknown):Promise<
                         {status:number;payload:unknown}>{
  let res:Response;
  try{
    res=await transport(path,{
      method,mode:'same-origin',credentials:'omit',cache:'no-store',
      redirect:'error',
      headers:{Accept:'application/json',
        Authorization:'Bearer '+session.accessToken,
        ...(body===undefined?{}:{'Content-Type':'application/json'})},
      ...(body===undefined?{}:{body:JSON.stringify(body)}),
      signal:AbortSignal.timeout(15_000),
    });
  }catch{throw new BillingError('NETWORK_FAILURE');}
  if(res.status===401)throw new BillingError('SESSION_EXPIRED');
  if(res.status===403)throw new BillingError('NOT_AUTHORIZED');
  if(res.status===409)throw new BillingError('STATE_CONFLICT');
  if(res.status===400)throw new BillingError('INVALID_REQUEST');
  if(res.status===404)return {status:404,payload:null};
  if(res.status!==200&&res.status!==201)
    throw new BillingError('UNVERIFIED_RESPONSE');
  let payload:unknown;
  try{payload=await res.json();}
  catch{throw new BillingError('UNVERIFIED_RESPONSE');}
  return {status:res.status,payload};
}

export function createBillingCandidateFlow(
  session:StaffSession,branchId:string,appointmentId:string,
  transport:Transport=network,
){
  if(!session||!bearer.test(session.accessToken)
    ||!validId(session.tenantId)||!validId(session.membershipId)
    ||!validId(branchId)||!validId(appointmentId)
    ||!session.roles.includes('BILLING')
    ||!session.permissions.includes('OPD_BILL_READ'))
    throw new BillingError('NOT_AUTHORIZED');
  const prefix='/api/v1/branches/'+branchId+'/appointments/'+appointmentId;
  let identity:BillingContext|null=null,bill:BillReceipt|null=null;
  let running=false,disposed=false;
  let requestId:string|null=null;

  async function open():Promise<{context:BillingContext;bill:BillReceipt|null}>{
    if(disposed)throw new BillingError('SESSION_EXPIRED');
    if(running)throw new BillingError('STATE_CONFLICT');
    running=true;identity=null;bill=null;requestId=null;
    try{
      const contextResult=await request(transport,session,
        prefix+'/billing-context','GET');
      if(contextResult.status!==200)
        throw new BillingError('UNVERIFIED_RESPONSE');
      const verified=contextFrom(contextResult.payload,session,branchId,appointmentId);
      const billResult=await request(transport,session,prefix+'/bill','GET');
      if(billResult.status!==200&&billResult.status!==404)
        throw new BillingError('UNVERIFIED_RESPONSE');
      const found=billResult.status===404?null:
        receiptFrom(billResult.payload,verified);
      if(disposed)throw new BillingError('SESSION_EXPIRED');
      identity=verified;bill=found;
      return {context:verified,bill:found};
    }catch(err){identity=null;bill=null;throw err;}
    finally{running=false;}
  }

  async function create():Promise<BillReceipt>{
    if(disposed)throw new BillingError('SESSION_EXPIRED');
    if(running||!identity||bill)throw new BillingError('STATE_CONFLICT');
    if(!session.permissions.includes('OPD_BILL_CREATE'))
      throw new BillingError('NOT_AUTHORIZED');
    requestId??=crypto.randomUUID();
    running=true;
    const fee=identity;
    try{
      const response=await request(transport,session,prefix+'/bill','POST',{
        requestId,serviceCode:fee.serviceCode,
      });
      if(response.status!==201&&response.status!==200)
        throw new BillingError('UNVERIFIED_RESPONSE');
      const verified=receiptFrom(response.payload,fee);
      if(disposed)throw new BillingError('SESSION_EXPIRED');
      if(verified.paidPaise!==0||verified.status!=='UNPAID')
        throw new BillingError('UNVERIFIED_RESPONSE');
      bill=verified;requestId=null;return verified;
    }catch(err){
      // Idempotency UUID survives an uncertain result ONLY in this in-memory
      // flow. UI must reopen the ledger before retrying a failed operation.
      identity=null;bill=null;throw err;
    }finally{running=false;}
  }

  async function cash(amountPaise:number):Promise<BillReceipt>{
    if(disposed)throw new BillingError('SESSION_EXPIRED');
    if(running||!identity||!bill)throw new BillingError('STATE_CONFLICT');
    if(!session.permissions.includes('OPD_PAYMENT_POST'))
      throw new BillingError('NOT_AUTHORIZED');
    if(!money(amountPaise)||amountPaise<1||amountPaise>bill.outstandingPaise)
      throw new BillingError('INVALID_REQUEST');
    running=true;
    const before=bill,fee=identity;
    bill=null;
    try{
      const response=await request(transport,session,
        prefix+'/bill/cash','POST',{
          requestId:crypto.randomUUID(),invoiceId:before.invoiceId,
          amountPaise,
        });
      if(response.status!==200)throw new BillingError('UNVERIFIED_RESPONSE');
      const verified=receiptFrom(response.payload,fee);
      if(disposed)throw new BillingError('SESSION_EXPIRED');
      if(verified.invoiceId!==before.invoiceId
        ||verified.invoiceNumber!==before.invoiceNumber
        ||verified.paidPaise!==before.paidPaise+amountPaise
        ||verified.outstandingPaise!==before.outstandingPaise-amountPaise)
        throw new BillingError('UNVERIFIED_RESPONSE');
      bill=verified;return verified;
    }catch(err){
      identity=null;bill=null;throw err;
    }finally{running=false;}
  }

  function snapshot(){return Object.freeze({
    open:identity!==null,invoiceNumber:bill?.invoiceNumber??null,
    outstandingPaise:bill?.outstandingPaise??null,
  });}
  function dispose(){disposed=true;identity=null;bill=null;requestId=null;}
  return Object.freeze({open,create,cash,snapshot,dispose});
}

export async function loadDailyBillingCandidate(
  session:StaffSession,branchId:string,day:string,
  transport:Transport=network,
):Promise<DailyCollections>{
  if(!session||!bearer.test(session.accessToken)
    ||!validId(session.tenantId)||!validId(branchId)
    ||!session.roles.includes('BILLING')
    ||!session.permissions.includes('OPD_DAILY_REPORT_READ'))
    throw new BillingError('NOT_AUTHORIZED');
  if(!date.test(day)||Number.isNaN(Date.parse(day+'T00:00:00Z'))
    ||new Date(day+'T00:00:00Z').toISOString().slice(0,10)!==day)
    throw new BillingError('INVALID_REQUEST');
  const reply=await request(transport,session,
    '/api/v1/branches/'+branchId+'/billing/daily?date='+day,'GET');
  if(reply.status!==200||!reply.payload||
    typeof reply.payload!=='object'||Array.isArray(reply.payload))
    throw new BillingError('UNVERIFIED_RESPONSE');
  const r=reply.payload as Record<string,unknown>;
  if(r.date!==day||r.tenantId!==session.tenantId
    ||r.branchId!==branchId
    ||!Number.isSafeInteger(r.invoiceCount)||Number(r.invoiceCount)<0
    ||!money(r.billedPaise)||!money(r.collectionsPaise)
    ||!money(r.outstandingPaise))
    throw new BillingError('UNVERIFIED_RESPONSE');
  return Object.freeze({
    date:day,tenantId:session.tenantId,branchId,
    invoiceCount:Number(r.invoiceCount),
    billedPaise:Number(r.billedPaise),
    collectionsPaise:Number(r.collectionsPaise),
    outstandingPaise:Number(r.outstandingPaise),
  });
}
