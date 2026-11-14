import type {StaffSession} from './staffSessionClient.ts';
import type {RegistrationReference} from './registrationReferenceClient.ts';

/**
 * PROD-INT-01: real production-source read-only OPD intake adapter.
 * This is NOT an appointment booking capability or persistent cache.
 * Server checks the live session, OPD + patient modules, both permissions,
 * exact registration, assigned branch and nonowner PostgreSQL RLS.
 */
export interface VerifiedOpdIntake extends RegistrationReference {
  readonly status:'REGISTRATION_VERIFIED';
}

export type OpdIntakeErrorCode='SESSION_EXPIRED'|'ACCESS_DENIED'|'UNVERIFIED_RESPONSE';
const messages:Record<OpdIntakeErrorCode,string>={
  SESSION_EXPIRED:'Your clinic session expired. Sign in again.',
  ACCESS_DENIED:'OPD intake is unavailable for this staff account or branch.',
  UNVERIFIED_RESPONSE:'OPD registration could not be verified. Do not book yet.',
};

export class OpdIntakeError extends Error {
  readonly code:OpdIntakeErrorCode;
  constructor(code:OpdIntakeErrorCode){
    super(messages[code]);this.code=code;this.name='OpdIntakeError';
  }
}

type Transport=(url:string,init:RequestInit)=>Promise<Response>;
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const valid=(v:unknown):v is string=>typeof v==='string'
  &&uuid.test(v)&&v.toLowerCase()!=='00000000-0000-0000-0000-000000000000';
const defaultTransport:Transport=(url,init)=>fetch(url,init);

export async function verifyProductionOpdIntake(
  session:StaffSession,
  reference:Readonly<Pick<RegistrationReference,
    'tenantId'|'branchId'|'registrationId'|'patientId'>>,
  transport:Transport=defaultTransport,
):Promise<VerifiedOpdIntake>{
  if(!session||!reference
    ||!valid(session.tenantId)||!valid(reference.tenantId)
    ||session.tenantId!==reference.tenantId
    ||!valid(reference.branchId)||!valid(reference.registrationId)
    ||!valid(reference.patientId)
    ||!Array.isArray(session.permissions)
    ||!session.permissions.includes('APPOINTMENT_BOOK')
    ||!session.permissions.includes('PATIENT_REGISTRATION_READ')
    ||typeof session.accessToken!=='string'
    ||!/^[A-Za-z0-9_-]{43}$/.test(session.accessToken))
    throw new OpdIntakeError('ACCESS_DENIED');
  try{
    const result=await transport('/api/v1/branches/'+reference.branchId+
      '/opd/intake/'+reference.registrationId,{
      method:'GET',mode:'same-origin',credentials:'omit',
      redirect:'error',cache:'no-store',signal:AbortSignal.timeout(10000),
      headers:{Accept:'application/json',
        Authorization:'Bearer '+session.accessToken},
    });
    if(result.status===401)throw new OpdIntakeError('SESSION_EXPIRED');
    if(result.status===403||result.status===404)
      throw new OpdIntakeError('ACCESS_DENIED');
    if(result.status!==200)throw new OpdIntakeError('UNVERIFIED_RESPONSE');
    const payload:unknown=await result.json();
    if(!payload||typeof payload!=='object'||Array.isArray(payload))
      throw new OpdIntakeError('UNVERIFIED_RESPONSE');
    const r=payload as Record<string,unknown>;
    if(!valid(r.tenantId)||r.tenantId!==reference.tenantId
      ||!valid(r.branchId)||r.branchId!==reference.branchId
      ||!valid(r.registrationId)||r.registrationId!==reference.registrationId
      ||!valid(r.patientId)||r.patientId!==reference.patientId
      ||r.status!=='REGISTRATION_VERIFIED')
      throw new OpdIntakeError('UNVERIFIED_RESPONSE');
    return Object.freeze({
      tenantId:r.tenantId,branchId:r.branchId,
      registrationId:r.registrationId,patientId:r.patientId,
      status:'REGISTRATION_VERIFIED',
    });
  }catch(failure){
    // Never echo a token, server error body or demographic data.
    throw failure instanceof OpdIntakeError?failure:
      new OpdIntakeError('UNVERIFIED_RESPONSE');
  }
}
