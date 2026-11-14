import type {StaffSession} from './staffSessionClient.ts';
import type {VerifiedOpdIntake} from './productionOpdIntakeClient.ts';

/**
 * PROD-INT-02 source client for default-disabled, production-source commands.
 * No browser storage, no patient details or local permission delegation.
 * The backend must independently revalidate every session, patient, doctor,
 * tenant, branch, entitlement, slot and revision in restricted PostgreSQL.
 * Callers keep a stable requestId for an uncertain retry.
 */
export interface ProductionBookingReceipt {
  readonly tenantId:string;
  readonly branchId:string;
  readonly registrationId:string;
  readonly patientId:string;
  readonly doctorMembershipId:string;
  readonly appointmentId:string;
  readonly stage:'BOOKED'|'CHECKED_IN'|'CANCELLED';
  readonly revision:number;
  readonly replayed:boolean;
}
export interface ProductionBookingInput {
  readonly doctorMembershipId:string;
  readonly visitDate:string;
  readonly slotStart:string;
  readonly requestId:string;
}
export type BookingErrorCode='SESSION_EXPIRED'|'ACCESS_DENIED'|
  'CONFLICT_OR_STALE'|'INVALID_REQUEST'|'WRITER_NOT_AVAILABLE'|'UNVERIFIED';
const messages:Record<BookingErrorCode,string>={
  SESSION_EXPIRED:'Sign in again before managing this appointment.',
  ACCESS_DENIED:'Your account cannot manage this appointment.',
  CONFLICT_OR_STALE:'The slot or appointment has changed. Recheck with the clinic.',
  INVALID_REQUEST:'Please correct the appointment date, time or identifiers.',
  WRITER_NOT_AVAILABLE:'Booking has not been enabled on this installation.',
  UNVERIFIED:'The appointment could not be confirmed. Do not proceed without checking the server.',
};
export class ProductionBookingError extends Error {
  readonly code:BookingErrorCode;
  constructor(code:BookingErrorCode){
    super(messages[code]);this.name='ProductionBookingError';this.code=code;
  }
}
type Transport=(url:string,init:RequestInit)=>Promise<Response>;
const transportDefault:Transport=(url,init)=>fetch(url,init);
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const valid=(x:unknown):x is string=>typeof x==='string'&&uuid.test(x)
  &&x.toLowerCase()!=='00000000-0000-0000-0000-000000000000';
const date=/^\d{4}-\d{2}-\d{2}$/;
const slot=/^(09|1[0-5]|16):(00|15|30|45)$/;
const token=/^[A-Za-z0-9_-]{43}$/;
function authorized(session:StaffSession,permission:string):void{
  if(!session||!valid(session.tenantId)||!token.test(session.accessToken)
    ||!session.permissions.includes(permission))
    throw new ProductionBookingError('ACCESS_DENIED');
}
function validateContext(session:StaffSession,intake:VerifiedOpdIntake):void{
  authorized(session,'APPOINTMENT_BOOK');
  if(!intake||intake.status!=='REGISTRATION_VERIFIED'
    ||!valid(intake.tenantId)||session.tenantId!==intake.tenantId
    ||!valid(intake.branchId)||!valid(intake.patientId)
    ||!valid(intake.registrationId)
    ||!session.permissions.includes('PATIENT_REGISTRATION_READ'))
    throw new ProductionBookingError('ACCESS_DENIED');
}
function decode(raw:unknown,intake:VerifiedOpdIntake):ProductionBookingReceipt{
  if(!raw||typeof raw!=='object'||Array.isArray(raw))
    throw new ProductionBookingError('UNVERIFIED');
  const x=raw as Record<string,unknown>;
  if(!valid(x.tenantId)||x.tenantId!==intake.tenantId
    ||!valid(x.branchId)||x.branchId!==intake.branchId
    ||!valid(x.patientId)||x.patientId!==intake.patientId
    ||!valid(x.registrationId)||x.registrationId!==intake.registrationId
    ||!valid(x.appointmentId)||!valid(x.doctorMembershipId)
    ||(x.stage!=='BOOKED'&&x.stage!=='CHECKED_IN'&&x.stage!=='CANCELLED')
    ||!Number.isSafeInteger(x.revision)||Number(x.revision)<1
    ||typeof x.replayed!=='boolean')
    throw new ProductionBookingError('UNVERIFIED');
  return Object.freeze({
    tenantId:x.tenantId,branchId:x.branchId,
    patientId:x.patientId,registrationId:x.registrationId,
    appointmentId:x.appointmentId,doctorMembershipId:x.doctorMembershipId,
    stage:x.stage,revision:Number(x.revision),replayed:x.replayed,
  });
}
async function response(result:Response,intake:VerifiedOpdIntake,
                         success:readonly number[]):Promise<ProductionBookingReceipt>{
  if(result.status===401)throw new ProductionBookingError('SESSION_EXPIRED');
  if(result.status===403)throw new ProductionBookingError('ACCESS_DENIED');
  if(result.status===409)throw new ProductionBookingError('CONFLICT_OR_STALE');
  if(result.status===400)throw new ProductionBookingError('INVALID_REQUEST');
  if(result.status===503)throw new ProductionBookingError('WRITER_NOT_AVAILABLE');
  if(!success.includes(result.status))throw new ProductionBookingError('UNVERIFIED');
  return decode(await result.json(),intake);
}
async function send(path:string,session:StaffSession,input:object,
                    intake:VerifiedOpdIntake,transport:Transport,
                    success:readonly number[]):Promise<ProductionBookingReceipt>{
  try{
    const result=await transport(path,{
      method:'POST',mode:'same-origin',credentials:'omit',cache:'no-store',
      redirect:'error',signal:AbortSignal.timeout(12000),
      headers:{Accept:'application/json','Content-Type':'application/json',
        Authorization:'Bearer '+session.accessToken},
      body:JSON.stringify(input),
    });
    return await response(result,intake,success);
  }catch(err){
    throw err instanceof ProductionBookingError?err:
      new ProductionBookingError('UNVERIFIED');
  }
}
export async function bookProductionOpd(
  session:StaffSession,intake:VerifiedOpdIntake,input:ProductionBookingInput,
  transport:Transport=transportDefault,
):Promise<ProductionBookingReceipt>{
  validateContext(session,intake);
  if(!input||!valid(input.doctorMembershipId)||!valid(input.requestId)
    ||typeof input.visitDate!=='string'||!date.test(input.visitDate)
    ||typeof input.slotStart!=='string'||!slot.test(input.slotStart))
    throw new ProductionBookingError('INVALID_REQUEST');
  const path='/api/v1/branches/'+intake.branchId+'/opd/appointments';
  const found=await send(path,session,{
    registrationId:intake.registrationId,
    doctorMembershipId:input.doctorMembershipId,
    visitDate:input.visitDate,slotStart:input.slotStart,
    requestId:input.requestId,
  },intake,transport,[200,201]);
  if(found.doctorMembershipId!==input.doctorMembershipId
    ||(found.stage!=='BOOKED'&&!(found.replayed&&
      (found.stage==='CHECKED_IN'||found.stage==='CANCELLED'))))
    throw new ProductionBookingError('UNVERIFIED');
  return found;
}
export async function checkInProductionOpd(
  session:StaffSession,intake:VerifiedOpdIntake,
  before:ProductionBookingReceipt,requestId:string,
  transport:Transport=transportDefault,
):Promise<ProductionBookingReceipt>{
  validateContext(session,intake);
  authorized(session,'APPOINTMENT_CHECK_IN');
  if(!before||before.stage!=='BOOKED'||!valid(before.appointmentId)
    ||!valid(before.doctorMembershipId)||!valid(requestId)
    ||!Number.isSafeInteger(before.revision)||before.revision<1)
    throw new ProductionBookingError('INVALID_REQUEST');
  const prior=decode(before,intake);
  const path='/api/v1/branches/'+intake.branchId+'/opd/appointments/'
    +prior.appointmentId+'/check-in';
  const found=await send(path,session,{
    expectedRevision:prior.revision,requestId,
  },intake,transport,[200]);
  if(found.stage!=='CHECKED_IN'||found.appointmentId!==prior.appointmentId
    ||found.doctorMembershipId!==prior.doctorMembershipId
    ||found.revision!==prior.revision+1)
    throw new ProductionBookingError('UNVERIFIED');
  return found;
}
