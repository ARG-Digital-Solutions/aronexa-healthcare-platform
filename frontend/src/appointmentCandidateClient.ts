import type {StaffSession} from './staffSessionClient.ts';
import type {RegistrationReference} from './registrationReferenceClient.ts';

type Transport=(url:string,options:RequestInit)=>Promise<Response>;
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const bearer=/^[A-Za-z0-9_-]{43}$/;
const valid=(v:unknown):v is string=>typeof v==='string' && uuid.test(v)
  && v.toLowerCase()!=='00000000-0000-0000-0000-000000000000';
const slot=/^(09|1[0-5]):(00|15|30|45)$|^16:(00|15|30|45)$/;
const date=/^\d{4}-\d{2}-\d{2}$/;
const defaultTransport:Transport=(url,init)=>fetch(url,init);

export interface AppointmentCandidateReceipt {
  readonly tenantId:string;
  readonly branchId:string;
  readonly patientId:string;
  readonly registrationId:string;
  readonly appointmentId:string;
  readonly doctorMembershipId:string;
  readonly stage:'BOOKED'|'CHECKED_IN'|'CANCELLED';
  readonly visitDate:string;
  readonly slotStart:string;
  readonly revision:number;
  readonly replayed:boolean;
}
export interface CandidateBooking {
  readonly doctorMembershipId:string;
  readonly visitDate:string;
  readonly slotStart:string;
}

export type CandidateAppointmentErrorCode=
  'INVALID_REQUEST'|'NOT_AUTHORIZED'|'SESSION_EXPIRED'|
  'APPOINTMENT_CONFLICT'|'NOT_FOUND'|'UNVERIFIED_RESPONSE'|'NETWORK_FAILURE';

const description:Record<CandidateAppointmentErrorCode,string>={
  INVALID_REQUEST:'Select an assigned doctor, valid date and 15-minute clinic slot.',
  NOT_AUTHORIZED:'Your account is not authorized to access this appointment.',
  SESSION_EXPIRED:'Your staff session has expired. Sign in again.',
  APPOINTMENT_CONFLICT:'This slot or appointment state has changed. Recheck before continuing.',
  NOT_FOUND:'This appointment cannot be found in the assigned branch.',
  UNVERIFIED_RESPONSE:'The appointment could not be verified. Do not continue without server confirmation.',
  NETWORK_FAILURE:'The clinic server could not confirm this appointment. Retry the same booking request.',
};

export class AppointmentCandidateError extends Error {
  readonly code:CandidateAppointmentErrorCode;
  constructor(code:CandidateAppointmentErrorCode) {
    super(description[code]);this.name='AppointmentCandidateError';this.code=code;
  }
}

function verify(receipt:unknown,context:RegistrationReference):AppointmentCandidateReceipt {
  if(receipt===null||typeof receipt!=='object'||Array.isArray(receipt))
    throw new AppointmentCandidateError('UNVERIFIED_RESPONSE');
  const r=receipt as Record<string,unknown>;
  if(!valid(r.tenantId)||r.tenantId!==context.tenantId
    ||!valid(r.branchId)||r.branchId!==context.branchId
    ||!valid(r.patientId)||r.patientId!==context.patientId
    ||!valid(r.registrationId)||r.registrationId!==context.registrationId
    ||!valid(r.appointmentId)||!valid(r.doctorMembershipId)
    ||(r.stage!=='BOOKED'&&r.stage!=='CHECKED_IN'&&r.stage!=='CANCELLED')
    ||typeof r.visitDate!=='string'||!date.test(r.visitDate)
    ||typeof r.slotStart!=='string'||!/^(09|1[0-5]|16):(00|15|30|45)(:00)?$/.test(r.slotStart)
    ||!Number.isSafeInteger(r.revision)||Number(r.revision)<1
    ||typeof r.replayed!=='boolean'
    ||((r.stage==='CHECKED_IN'||r.stage==='CANCELLED')&&Number(r.revision)<2))
    throw new AppointmentCandidateError('UNVERIFIED_RESPONSE');
  return Object.freeze({
    tenantId:r.tenantId,branchId:r.branchId,patientId:r.patientId,
    registrationId:r.registrationId,appointmentId:r.appointmentId,
    doctorMembershipId:r.doctorMembershipId,stage:r.stage,
    visitDate:r.visitDate,slotStart:r.slotStart,revision:Number(r.revision),
    replayed:r.replayed,
  });
}

function request(token:string,method:'GET'|'POST',body?:unknown):RequestInit {
  return {
    method,mode:'same-origin',credentials:'omit',cache:'no-store',redirect:'error',
    headers:{
      Accept:'application/json',Authorization:'Bearer '+token,
      ...(body===undefined?{}:{'Content-Type':'application/json'}),
    },
    ...(body===undefined?{}:{body:JSON.stringify(body)}),
    signal:AbortSignal.timeout(12_000),
  };
}
async function parseResponse(response:Response,context:RegistrationReference,
                             expected:200|201):Promise<AppointmentCandidateReceipt> {
  if(response.status===401)throw new AppointmentCandidateError('SESSION_EXPIRED');
  if(response.status===403)throw new AppointmentCandidateError('NOT_AUTHORIZED');
  if(response.status===404)throw new AppointmentCandidateError('NOT_FOUND');
  if(response.status===409)throw new AppointmentCandidateError('APPOINTMENT_CONFLICT');
  if(response.status===400)throw new AppointmentCandidateError('INVALID_REQUEST');
  if(response.status!==expected && !(expected===201&&response.status===200))
    throw new AppointmentCandidateError('UNVERIFIED_RESPONSE');
  try{return verify(await response.json(),context);}
  catch(error){
    if(error instanceof AppointmentCandidateError)throw error;
    throw new AppointmentCandidateError('UNVERIFIED_RESPONSE');
  }
}

/** Test-only connected Reception candidate. No browser persistence or demo IDs.
 * The server checks the bearer, session, branch, doctor, registration and
 * current permissions in a SQL transaction on EVERY operation.
 */
export function createAppointmentCandidateFlow(
  session:StaffSession,reference:RegistrationReference,
  transport:Transport=defaultTransport,
){
  if(!session||!bearer.test(session.accessToken)||!valid(session.tenantId)
    ||!reference||!valid(reference.tenantId)||!valid(reference.branchId)
    ||!valid(reference.patientId)||!valid(reference.registrationId)
    ||session.tenantId!==reference.tenantId
    ||!session.permissions.includes('APPOINTMENT_BOOK'))
    throw new AppointmentCandidateError('NOT_AUTHORIZED');
  let active:AppointmentCandidateReceipt|null=null;
  let requestId:string|null=null;
  let attempted:CandidateBooking|null=null;
  let disposed=false;
  let running=false;
  const path='/api/v1/branches/'+reference.branchId+'/appointments';

  async function api(url:string,method:'GET'|'POST',body?:unknown,
                     expected:200|201=200):Promise<AppointmentCandidateReceipt> {
    try{
      const response=await transport(url,request(session.accessToken,method,body));
      const verified=await parseResponse(response,reference,expected);
      if(disposed)throw new AppointmentCandidateError('SESSION_EXPIRED');
      return verified;
    }catch(failure){
      throw failure instanceof AppointmentCandidateError?failure:
        new AppointmentCandidateError('NETWORK_FAILURE');
    }
  }
  const snapshot=()=>Object.freeze({receipt:active,requestPending:requestId!==null});
  async function book(booking:CandidateBooking):Promise<AppointmentCandidateReceipt>{
    if(disposed)throw new AppointmentCandidateError('SESSION_EXPIRED');
    if(running)throw new AppointmentCandidateError('APPOINTMENT_CONFLICT');
    if(!booking||!valid(booking.doctorMembershipId)
      ||typeof booking.visitDate!=='string'||!date.test(booking.visitDate)
      ||typeof booking.slotStart!=='string'||!slot.test(booking.slotStart))
      throw new AppointmentCandidateError('INVALID_REQUEST');
    if(attempted && (attempted.doctorMembershipId!==booking.doctorMembershipId
       ||attempted.visitDate!==booking.visitDate
       ||attempted.slotStart!==booking.slotStart))
       throw new AppointmentCandidateError('APPOINTMENT_CONFLICT');
    if(active)throw new AppointmentCandidateError('APPOINTMENT_CONFLICT');
    attempted=Object.freeze({...booking});
    requestId??=crypto.randomUUID();
    running=true;
    try{
      const received=await api(path,'POST',{...booking,
        registrationId:reference.registrationId,requestId},201);
      if(received.stage!=='BOOKED'||received.doctorMembershipId!==booking.doctorMembershipId
        ||received.visitDate!==booking.visitDate
        ||!received.slotStart.startsWith(booking.slotStart))
        throw new AppointmentCandidateError('UNVERIFIED_RESPONSE');
      active=received;
      return received;
    }finally{running=false;}
  }
  async function reopen(appointmentId:string):Promise<AppointmentCandidateReceipt>{
    if(disposed)throw new AppointmentCandidateError('SESSION_EXPIRED');
    if(running||!valid(appointmentId))throw new AppointmentCandidateError('INVALID_REQUEST');
    if(!session.permissions.includes('APPOINTMENT_READ'))
      throw new AppointmentCandidateError('NOT_AUTHORIZED');
    running=true;
    active=null;
    try{
      const received=await api(path+'/'+appointmentId,'GET');
      if(received.appointmentId!==appointmentId)
        throw new AppointmentCandidateError('UNVERIFIED_RESPONSE');
      active=received;
      return received;
    }finally{running=false;}
  }
  async function checkIn():Promise<AppointmentCandidateReceipt>{
    if(disposed)throw new AppointmentCandidateError('SESSION_EXPIRED');
    if(running||!active||active.stage!=='BOOKED')
      throw new AppointmentCandidateError('INVALID_REQUEST');
    if(!session.permissions.includes('APPOINTMENT_CHECK_IN'))
      throw new AppointmentCandidateError('NOT_AUTHORIZED');
    running=true;
    const expected=active;
    try{
      const result=await api(path+'/'+expected.appointmentId+'/check-in',
        'POST',{expectedRevision:expected.revision});
      if(result.stage!=='CHECKED_IN'||result.appointmentId!==expected.appointmentId
        ||result.revision!==expected.revision+1
        ||result.doctorMembershipId!==expected.doctorMembershipId
        ||result.visitDate!==expected.visitDate
        ||result.slotStart!==expected.slotStart)
        throw new AppointmentCandidateError('UNVERIFIED_RESPONSE');
      active=result;
      return result;
    }catch(e){active=null;throw e;}
    finally{running=false;}
  }
  async function reschedule(booking:CandidateBooking):Promise<AppointmentCandidateReceipt>{
    if(disposed)throw new AppointmentCandidateError('SESSION_EXPIRED');
    if(running||!active||active.stage!=='BOOKED')
      throw new AppointmentCandidateError('INVALID_REQUEST');
    if(!session.permissions.includes('APPOINTMENT_RESCHEDULE'))
      throw new AppointmentCandidateError('NOT_AUTHORIZED');
    if(!booking||!valid(booking.doctorMembershipId)
      ||typeof booking.visitDate!=='string'||!date.test(booking.visitDate)
      ||typeof booking.slotStart!=='string'||!slot.test(booking.slotStart))
      throw new AppointmentCandidateError('INVALID_REQUEST');
    const before=active;
    if(before.doctorMembershipId===booking.doctorMembershipId
      &&before.visitDate===booking.visitDate
      &&before.slotStart.startsWith(booking.slotStart))
      throw new AppointmentCandidateError('APPOINTMENT_CONFLICT');
    running=true;
    try{
      const updated=await api(path+'/'+before.appointmentId+'/reschedule',
        'POST',{...booking,expectedRevision:before.revision});
      if(updated.stage!=='BOOKED'||updated.appointmentId!==before.appointmentId
        ||updated.revision!==before.revision+1
        ||updated.doctorMembershipId!==booking.doctorMembershipId
        ||updated.visitDate!==booking.visitDate
        ||!updated.slotStart.startsWith(booking.slotStart))
        throw new AppointmentCandidateError('UNVERIFIED_RESPONSE');
      active=updated;return updated;
    }catch(e){active=null;throw e;}
    finally{running=false;}
  }

  async function cancel():Promise<AppointmentCandidateReceipt>{
    if(disposed)throw new AppointmentCandidateError('SESSION_EXPIRED');
    if(running||!active||active.stage!=='BOOKED')
      throw new AppointmentCandidateError('INVALID_REQUEST');
    if(!session.permissions.includes('APPOINTMENT_CANCEL'))
      throw new AppointmentCandidateError('NOT_AUTHORIZED');
    const before=active;
    running=true;
    try {
      const updated=await api(path+'/'+before.appointmentId+'/cancel',
        'POST',{expectedRevision:before.revision});
      if(updated.stage!=='CANCELLED'||updated.appointmentId!==before.appointmentId
        ||updated.revision!==before.revision+1
        ||updated.doctorMembershipId!==before.doctorMembershipId
        ||updated.visitDate!==before.visitDate
        ||updated.slotStart!==before.slotStart)
        throw new AppointmentCandidateError('UNVERIFIED_RESPONSE');
      active=updated;return updated;
    }catch(e){active=null;throw e;}
    finally{running=false;}
  }

  function dispose(){
    disposed=true;active=null;requestId=null;attempted=null;
  }
  return Object.freeze({book,reopen,checkIn,reschedule,cancel,snapshot,dispose});
}


/**
 * Fresh authorized daily Reception queue. Results are server-issued and
 * include no demographic or clinical information. All items are checked for
 * tenant/branch/date, uniqueness and a maximum of 100; partial queues fail.
 * Do not cache or persist session, tokens, patient IDs or query responses.
 */
export async function loadAppointmentCandidateQueue(
  session:StaffSession,branchId:string,visitDate:string,
  transport:Transport=defaultTransport,
):Promise<readonly AppointmentCandidateReceipt[]>{
  if(!session||!bearer.test(session.accessToken)||!valid(session.tenantId)
    ||!valid(branchId)||!session.permissions.includes('APPOINTMENT_READ'))
    throw new AppointmentCandidateError('NOT_AUTHORIZED');
  if(!date.test(visitDate))
    throw new AppointmentCandidateError('INVALID_REQUEST');
  let response:Response;
  try{
    response=await transport('/api/v1/branches/'+branchId+
      '/appointments?visitDate='+encodeURIComponent(visitDate),
      request(session.accessToken,'GET'));
  }catch{throw new AppointmentCandidateError('NETWORK_FAILURE');}
  if(response.status===401)throw new AppointmentCandidateError('SESSION_EXPIRED');
  if(response.status===403)throw new AppointmentCandidateError('NOT_AUTHORIZED');
  if(response.status===409)throw new AppointmentCandidateError('APPOINTMENT_CONFLICT');
  if(response.status===400)throw new AppointmentCandidateError('INVALID_REQUEST');
  if(response.status!==200)throw new AppointmentCandidateError('UNVERIFIED_RESPONSE');
  let payload:unknown;
  try{payload=await response.json();}
  catch{throw new AppointmentCandidateError('UNVERIFIED_RESPONSE');}
  if(!Array.isArray(payload)||payload.length>100)
    throw new AppointmentCandidateError('UNVERIFIED_RESPONSE');
  const seen=new Set<string>();
  const records:AppointmentCandidateReceipt[]=[];
  for(const item of payload){
    if(!item||typeof item!=='object'||Array.isArray(item))
      throw new AppointmentCandidateError('UNVERIFIED_RESPONSE');
    const data=item as Record<string,unknown>;
    const received=verify(data,{
      tenantId:session.tenantId,branchId,
      patientId:String(data.patientId||''),
      registrationId:String(data.registrationId||''),
    });
    if(received.stage==='CANCELLED'||received.visitDate!==visitDate
      ||seen.has(received.appointmentId.toLowerCase()))
      throw new AppointmentCandidateError('UNVERIFIED_RESPONSE');
    seen.add(received.appointmentId.toLowerCase());
    records.push(received);
  }
  return Object.freeze(records);
}
