import type {StaffSession} from './staffSessionClient.ts';
import {createDoctorConsultationCandidateFlow} from './doctorConsultationCandidateClient.ts';
import {normalizeMedicine,type MedicineEntry} from './smart-rx/model.ts';

type Transport=(url:string,options:RequestInit)=>Promise<Response>;
const defaultTransport:Transport=(url,options)=>fetch(url,options);
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const token=/^[A-Za-z0-9_-]{43}$/;
const validId=(v:unknown):v is string=>typeof v==='string'&&uuid.test(v)
  &&v.toLowerCase()!=='00000000-0000-0000-0000-000000000000';

export type DoctorRxErrorCode='NOT_AUTHORIZED'|'INVALID_REQUEST'|'SESSION_EXPIRED'|
  'STATE_CONFLICT'|'UNVERIFIED_RESPONSE'|'NETWORK_FAILURE';
const messages:Record<DoctorRxErrorCode,string>={
  NOT_AUTHORIZED:'You are not authorized to edit this appointment prescription draft.',
  INVALID_REQUEST:'Enter complete fictional medicine details and quantity before saving.',
  SESSION_EXPIRED:'Your doctor session expired. Please sign in again.',
  STATE_CONFLICT:'The consultation or prescription changed. Reopen and review before saving.',
  UNVERIFIED_RESPONSE:'The prescription response could not be verified. Reopen and review.',
  NETWORK_FAILURE:'The save could not be confirmed. Reopen before another attempt.',
};
export class DoctorRxError extends Error{
  readonly code:DoctorRxErrorCode;
  constructor(code:DoctorRxErrorCode){
    super(messages[code]);this.name='DoctorRxError';this.code=code;
  }
}

export interface DoctorRxReceipt{
  readonly tenantId:string;readonly branchId:string;readonly patientId:string;
  readonly appointmentId:string;readonly doctorMembershipId:string;
  readonly stage:'EMPTY_DRAFT'|'RX_DRAFT';readonly revision:number;
  readonly consultationRevision:number;readonly medicines:readonly MedicineEntry[];
}

function validMedicine(raw:unknown):MedicineEntry{
  if(!raw||typeof raw!=='object'||Array.isArray(raw))
    throw new DoctorRxError('UNVERIFIED_RESPONSE');
  const m=raw as Record<string,unknown>;
  if(!validId(m.entryId))throw new DoctorRxError('UNVERIFIED_RESPONSE');
  try{
    const item=normalizeMedicine({
      id:m.entryId,medicine:m.medicine,strength:m.strength,form:m.form,
      dose:m.dose,route:m.route,frequency:m.frequency,
      duration:m.duration,quantity:m.quantity,
      instructions:m.instructions,
    } as MedicineEntry);
    // Do not silently accept a malformed, non-normalized server snapshot.
    if(item.medicine!==m.medicine||item.strength!==m.strength||item.form!==m.form
      ||item.dose!==m.dose||item.route!==m.route
      ||item.frequency!==m.frequency||item.duration!==m.duration
      ||item.instructions!==m.instructions)
      throw new DoctorRxError('UNVERIFIED_RESPONSE');
    return Object.freeze(item);
  }catch{throw new DoctorRxError('UNVERIFIED_RESPONSE');}
}

function verify(raw:unknown,session:StaffSession,branchId:string,appointmentId:string,
                patientId:string,consultationRevision:number):DoctorRxReceipt{
  if(!raw||typeof raw!=='object'||Array.isArray(raw))
    throw new DoctorRxError('UNVERIFIED_RESPONSE');
  const r=raw as Record<string,unknown>;
  if(r.tenantId!==session.tenantId||r.branchId!==branchId
    ||r.appointmentId!==appointmentId||r.patientId!==patientId
    ||r.doctorMembershipId!==session.membershipId
    ||(r.stage!=='EMPTY_DRAFT'&&r.stage!=='RX_DRAFT')
    ||!Number.isSafeInteger(r.revision)||Number(r.revision)<0
    ||!Number.isSafeInteger(r.consultationRevision)
    ||r.consultationRevision!==consultationRevision
    ||!Array.isArray(r.medicines)||r.medicines.length>30
    ||(r.stage==='EMPTY_DRAFT'&&(r.revision!==0||r.medicines.length!==0))
    ||(r.stage==='RX_DRAFT'&&(Number(r.revision)<1||r.medicines.length<1)))
    throw new DoctorRxError('UNVERIFIED_RESPONSE');
  const medicines=r.medicines.map(validMedicine);
  const ids=medicines.map(m=>m.id.toLowerCase());
  if(new Set(ids).size!==ids.length)throw new DoctorRxError('UNVERIFIED_RESPONSE');
  return Object.freeze({
    tenantId:session.tenantId,branchId,patientId,appointmentId,
    doctorMembershipId:session.membershipId,
    stage:r.stage,revision:Number(r.revision),
    consultationRevision:Number(r.consultationRevision),
    medicines:Object.freeze(medicines),
  });
}

/**
 * Test-only doctor prescription draft, never an issued medicine order.
 * Reuses the independent, authorized consultation-context + draft reads.
 */
export function createDoctorPrescriptionCandidateFlow(
  session:StaffSession,branchId:string,appointmentId:string,
  transport:Transport=defaultTransport,
){
  if(!session||!token.test(session.accessToken)
    ||!validId(session.tenantId)||!validId(session.membershipId)
    ||!validId(branchId)||!validId(appointmentId)
    ||!session.permissions.includes('OPD_DRAFT_WRITE')
    ||!session.permissions.includes('OPD_RX_DRAFT_WRITE'))
    throw new DoctorRxError('NOT_AUTHORIZED');
  const consultation=createDoctorConsultationCandidateFlow(
    session,branchId,appointmentId,transport);
  const endpoint='/api/v1/branches/'+branchId+'/appointments/'+
    appointmentId+'/prescription-draft';
  let latest:DoctorRxReceipt|null=null;
  let disposed=false,running=false;

  async function api(method:'GET'|'POST',body?:unknown):Promise<unknown>{
    let response:Response;
    try{
      response=await transport(endpoint,{
        method,mode:'same-origin',credentials:'omit',cache:'no-store',
        redirect:'error',
        headers:{
          Accept:'application/json',Authorization:'Bearer '+session.accessToken,
          ...(body===undefined?{}:{'Content-Type':'application/json'}),
        },
        ...(body===undefined?{}:{body:JSON.stringify(body)}),
        signal:AbortSignal.timeout(15_000),
      });
    }catch{throw new DoctorRxError('NETWORK_FAILURE');}
    if(disposed)throw new DoctorRxError('SESSION_EXPIRED');
    if(response.status===401)throw new DoctorRxError('SESSION_EXPIRED');
    if(response.status===403)throw new DoctorRxError('NOT_AUTHORIZED');
    if(response.status===409)throw new DoctorRxError('STATE_CONFLICT');
    if(response.status===400)throw new DoctorRxError('INVALID_REQUEST');
    if(response.status!==200)throw new DoctorRxError('UNVERIFIED_RESPONSE');
    try{return await response.json();}
    catch{throw new DoctorRxError('UNVERIFIED_RESPONSE');}
  }

  async function open():Promise<DoctorRxReceipt>{
    if(disposed)throw new DoctorRxError('SESSION_EXPIRED');
    if(running)throw new DoctorRxError('STATE_CONFLICT');
    running=true;latest=null;
    try{
      // Patient/doctor identity and consultation state originate independently
      // from authenticated reads, never from draft or form body values.
      const consult=await consultation.open();
      if(consult.stage!=='CONSULTATION_DRAFT'||consult.revision<1)
        throw new DoctorRxError('STATE_CONFLICT');
      const payload=await api('GET');
      if(disposed)throw new DoctorRxError('SESSION_EXPIRED');
      const received=verify(payload,session,branchId,appointmentId,
        consult.patientId,consult.revision);
      latest=received;
      return received;
    }catch(e){
      latest=null;
      if(e instanceof DoctorRxError)throw e;
      if(e instanceof Error){
        if(e.name==='DoctorDraftError'){
          const code=(e as Error & {code?:string}).code;
          if(code==='NOT_AUTHORIZED')throw new DoctorRxError('NOT_AUTHORIZED');
          if(code==='SESSION_EXPIRED')throw new DoctorRxError('SESSION_EXPIRED');
          if(code==='STATE_CONFLICT')throw new DoctorRxError('STATE_CONFLICT');
          if(code==='NETWORK_FAILURE')throw new DoctorRxError('NETWORK_FAILURE');
        }
      }
      throw new DoctorRxError('UNVERIFIED_RESPONSE');
    }finally{running=false;}
  }

  async function save(entries:readonly MedicineEntry[]):Promise<DoctorRxReceipt>{
    if(disposed)throw new DoctorRxError('SESSION_EXPIRED');
    if(running||!latest)throw new DoctorRxError('STATE_CONFLICT');
    if(!Array.isArray(entries)||entries.length===0||entries.length>30)
      throw new DoctorRxError('INVALID_REQUEST');
    let normalized:MedicineEntry[];
    try{
      normalized=entries.map(m=>{
        const valid=normalizeMedicine(m);
        if(!validId(valid.id))throw new Error();
        return valid;
      });
    }catch{throw new DoctorRxError('INVALID_REQUEST');}
    const ids=normalized.map(m=>m.id.toLowerCase());
    if(new Set(ids).size!==ids.length)throw new DoctorRxError('INVALID_REQUEST');
    const previous=latest;
    latest=null;running=true;
    try{
      const payload=await api('POST',{
        expectedRevision:previous.revision,
        consultationRevision:previous.consultationRevision,
        medicines:normalized.map(({id,...m})=>({entryId:id,...m})),
      });
      const updated=verify(payload,session,branchId,appointmentId,
        previous.patientId,previous.consultationRevision);
      if(updated.stage!=='RX_DRAFT'||updated.revision!==previous.revision+1
        ||JSON.stringify(updated.medicines)!==JSON.stringify(normalized))
        throw new DoctorRxError('UNVERIFIED_RESPONSE');
      if(disposed)throw new DoctorRxError('SESSION_EXPIRED');
      latest=updated;return updated;
    }finally{running=false;}
  }
  const snapshot=()=>Object.freeze({
    open:latest!==null,revision:latest?.revision??null,
    consultationRevision:latest?.consultationRevision??null,
  });
  function dispose(){disposed=true;latest=null;consultation.dispose();}
  return Object.freeze({open,save,snapshot,dispose});
}
