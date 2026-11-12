import {loadAuthorizedRegistrationReference, RegistrationReferenceError} from './registrationReferenceClient.ts';
import type {RegistrationReference} from './registrationReferenceClient.ts';
import type {StaffSession} from './staffSessionClient.ts';

type Transport=(url:string, options:RequestInit)=>Promise<Response>;
type RecoveryStatus='IDLE'|'LOOKING_UP'|'FOUND'|'FAILED';
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const bearer=/^[A-Za-z0-9_-]{43}$/;
const validId=(id:unknown):id is string=>typeof id==='string'
    && uuid.test(id) && id.toLowerCase()!=='00000000-0000-0000-0000-000000000000';

/** Does not expose any bearer, name, DOB, contact, diagnosis or stored session. */
export interface RegistrationRecoverySnapshot {
  readonly status:RecoveryStatus;
  readonly reference:RegistrationReference|null;
  readonly errorCode:string|null;
}

export class RegistrationRecoveryError extends Error {
  readonly code:'NOT_AUTHORIZED'|'INVALID_REFERENCE'|'BUSY'|'SESSION_CLOSED';
  constructor(code:RegistrationRecoveryError['code']){
    super(code==='INVALID_REFERENCE'?'Enter a valid registration UUID.'
      :code==='BUSY'?'Complete the current lookup before searching another registration.'
      :code==='SESSION_CLOSED'?'Sign in again to reopen the registration.'
      :'This staff account cannot view registrations in the selected branch.');
    this.name='RegistrationRecoveryError';
    this.code=code;
  }
}

/**
 * A minimal *read-only* server-backed registration recovery operation.
 * The server checks current session, tenant, branch, role, module and
 * PostgreSQL RLS; a frontend permission check is never an authorization grant.
 * No browser storage, offline cache, demo fallback or patient demographics.
 */
export function createRegistrationRecoveryFlow(
  session:StaffSession, branchId:string,
  transport?:Transport,
){
  if(!session || !validId(session.tenantId) || !validId(branchId)
    || !bearer.test(session.accessToken)
    || !session.permissions.includes('PATIENT_REGISTRATION_READ')){
    throw new RegistrationRecoveryError('NOT_AUTHORIZED');
  }
  let status:RecoveryStatus='IDLE';
  let reference:RegistrationReference|null=null;
  let errorCode:string|null=null;
  let disposed=false;
  let inFlight:Promise<RegistrationReference>|null=null;
  let inFlightId:string|null=null;
  const snapshot=():RegistrationRecoverySnapshot=>
    Object.freeze({status,reference,errorCode});

  function recover(registrationId:string):Promise<RegistrationReference>{
    if(disposed)return Promise.reject(new RegistrationRecoveryError('SESSION_CLOSED'));
    if(!validId(registrationId))return Promise.reject(new RegistrationRecoveryError('INVALID_REFERENCE'));
    if(inFlight){
      if(inFlightId===registrationId.toLowerCase())return inFlight;
      return Promise.reject(new RegistrationRecoveryError('BUSY'));
    }
    reference=null;
    errorCode=null;
    status='LOOKING_UP';
    inFlightId=registrationId.toLowerCase();
    const lookup=loadAuthorizedRegistrationReference({
      tenantId:session.tenantId,branchId,registrationId,
    },session.accessToken,transport).then(found=>{
      if(disposed)throw new RegistrationRecoveryError('SESSION_CLOSED');
      reference=found;
      status='FOUND';
      return found;
    }).catch(failure=>{
      reference=null;
      if(disposed)throw new RegistrationRecoveryError('SESSION_CLOSED');
      status='FAILED';
      errorCode=failure instanceof RegistrationReferenceError
        ?failure.code:'OUTCOME_UNKNOWN';
      throw failure;
    }).finally(()=>{
      inFlight=null;
      inFlightId=null;
    });
    inFlight=lookup;
    return lookup;
  }
  /** Immediately invalidate pending results when the user signs out. */
  function dispose():void{
    disposed=true;
    reference=null;
    errorCode=null;
    status='IDLE';
  }
  return Object.freeze({recover,snapshot,dispose});
}
