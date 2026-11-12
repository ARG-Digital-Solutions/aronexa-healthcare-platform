import type {StaffSession} from './staffSessionClient.ts';

type Transport=(url:string,options:RequestInit)=>Promise<Response>;
const defaultTransport:Transport=(url,options)=>fetch(url,options);
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const token=/^[A-Za-z0-9_-]{43}$/;
const validId=(v:unknown):v is string=>typeof v==='string'&&uuid.test(v)
  &&v.toLowerCase()!=='00000000-0000-0000-0000-000000000000';

export interface DoctorConsultationDraft {
  readonly tenantId:string;
  readonly branchId:string;
  readonly patientId:string;
  readonly appointmentId:string;
  readonly doctorMembershipId:string;
  readonly stage:'CHECKED_IN'|'CONSULTATION_DRAFT';
  readonly revision:number;
  readonly consultationNote:string;
}
export type DoctorDraftErrorCode='NOT_AUTHORIZED'|'INVALID_REQUEST'|'SESSION_EXPIRED'|
  'STATE_CONFLICT'|'UNVERIFIED_RESPONSE'|'NETWORK_FAILURE';

const explanations:Record<DoctorDraftErrorCode,string>={
  NOT_AUTHORIZED:'You cannot access this consultation in the selected clinic branch.',
  INVALID_REQUEST:'Enter a checked-in appointment UUID and a note of 1–2,000 characters.',
  SESSION_EXPIRED:'Your session is no longer active. Sign in again.',
  STATE_CONFLICT:'The consultation state changed. Reopen it before saving again.',
  UNVERIFIED_RESPONSE:'The consultation response could not be verified. Do not continue.',
  NETWORK_FAILURE:'The server could not confirm the note. Reopen before another save.',
};

export class DoctorDraftError extends Error {
  readonly code:DoctorDraftErrorCode;
  constructor(code:DoctorDraftErrorCode){
    super(explanations[code]);this.name='DoctorDraftError';this.code=code;
  }
}

function verify(body:unknown,session:StaffSession,branch:string,
                appointment:string):DoctorConsultationDraft{
  if(!body||typeof body!=='object'||Array.isArray(body))
    throw new DoctorDraftError('UNVERIFIED_RESPONSE');
  const data=body as Record<string,unknown>;
  if(!validId(data.tenantId)||data.tenantId!==session.tenantId
    ||!validId(data.branchId)||data.branchId!==branch
    ||!validId(data.patientId)
    ||!validId(data.appointmentId)||data.appointmentId!==appointment
    ||!validId(data.doctorMembershipId)
    ||data.doctorMembershipId!==session.membershipId
    ||(data.stage!=='CHECKED_IN'&&data.stage!=='CONSULTATION_DRAFT')
    ||!Number.isSafeInteger(data.revision)
    ||typeof data.consultationNote!=='string'
    ||data.consultationNote.length>2000
    ||(data.stage==='CHECKED_IN'&&(data.revision!==0||data.consultationNote!==''))
    ||(data.stage==='CONSULTATION_DRAFT'&&(Number(data.revision)<1
        ||!data.consultationNote.trim())))
    throw new DoctorDraftError('UNVERIFIED_RESPONSE');
  return Object.freeze({
    tenantId:data.tenantId,branchId:data.branchId,patientId:data.patientId,
    appointmentId:data.appointmentId,doctorMembershipId:data.doctorMembershipId,
    stage:data.stage,revision:Number(data.revision),
    consultationNote:data.consultationNote,
  });
}

export function createDoctorConsultationCandidateFlow(
  session:StaffSession,branchId:string,appointmentId:string,
  transport:Transport=defaultTransport,
){
  if(!session||!token.test(session.accessToken)
    ||!validId(session.tenantId)||!validId(session.membershipId)
    ||!validId(branchId)||!validId(appointmentId)
    ||!session.permissions.includes('OPD_DRAFT_WRITE'))
    throw new DoctorDraftError('NOT_AUTHORIZED');
  const base='/api/v1/branches/'+branchId+'/appointments/'+appointmentId;
  const endpoint=base+'/consultation-draft';
  const identityEndpoint=base+'/consultation-context';
  let expectedPatient:string|null=null;
  let current:DoctorConsultationDraft|null=null;
  let disposed=false,running=false;

  /**
   * Independent, doctor-authorized checked-in appointment identity from
   * the canonical appointment row. A valid but unrelated patient UUID in
   * the draft response must never be sufficient to display clinical notes.
   */
  async function readContext():Promise<string>{
    let response:Response;
    try{
      response=await transport(identityEndpoint,{
        method:'GET',mode:'same-origin',credentials:'omit',cache:'no-store',
        redirect:'error',
        headers:{Accept:'application/json',
          Authorization:'Bearer '+session.accessToken},
        signal:AbortSignal.timeout(15_000),
      });
    }catch{throw new DoctorDraftError('NETWORK_FAILURE');}
    if(disposed)throw new DoctorDraftError('SESSION_EXPIRED');
    if(response.status===401)throw new DoctorDraftError('SESSION_EXPIRED');
    if(response.status===403)throw new DoctorDraftError('NOT_AUTHORIZED');
    if(response.status===409)throw new DoctorDraftError('STATE_CONFLICT');
    if(response.status!==200)throw new DoctorDraftError('UNVERIFIED_RESPONSE');
    let data:unknown;
    try{data=await response.json();}
    catch{throw new DoctorDraftError('UNVERIFIED_RESPONSE');}
    if(disposed)throw new DoctorDraftError('SESSION_EXPIRED');
    if(!data||typeof data!=='object'||Array.isArray(data))
      throw new DoctorDraftError('UNVERIFIED_RESPONSE');
    const identity=data as Record<string,unknown>;
    if(identity.tenantId!==session.tenantId
      ||identity.branchId!==branchId
      ||identity.appointmentId!==appointmentId
      ||identity.doctorMembershipId!==session.membershipId
      ||identity.appointmentStage!=='CHECKED_IN'
      ||!validId(identity.patientId))
      throw new DoctorDraftError('UNVERIFIED_RESPONSE');
    return identity.patientId;
  }

  async function request(method:'GET'|'POST',body?:unknown):Promise<DoctorConsultationDraft>{
    let response:Response;
    try{
      response=await transport(endpoint,{
        method,mode:'same-origin',credentials:'omit',cache:'no-store',
        redirect:'error',
        headers:{
          Accept:'application/json',
          Authorization:'Bearer '+session.accessToken,
          ...(body===undefined?{}:{'Content-Type':'application/json'}),
        },
        ...(body===undefined?{}:{body:JSON.stringify(body)}),
        signal:AbortSignal.timeout(15_000),
      });
    }catch{throw new DoctorDraftError('NETWORK_FAILURE');}
    if(disposed)throw new DoctorDraftError('SESSION_EXPIRED');
    if(response.status===401)throw new DoctorDraftError('SESSION_EXPIRED');
    if(response.status===403)throw new DoctorDraftError('NOT_AUTHORIZED');
    if(response.status===409)throw new DoctorDraftError('STATE_CONFLICT');
    if(response.status===400)throw new DoctorDraftError('INVALID_REQUEST');
    if(response.status!==200)throw new DoctorDraftError('UNVERIFIED_RESPONSE');
    let payload:unknown;
    try{payload=await response.json();}
    catch{throw new DoctorDraftError('UNVERIFIED_RESPONSE');}
    if(disposed)throw new DoctorDraftError('SESSION_EXPIRED');
    const receipt=verify(payload,session,branchId,appointmentId);
    if(expectedPatient===null||receipt.patientId!==expectedPatient)
      throw new DoctorDraftError('UNVERIFIED_RESPONSE');
    return receipt;
  }

  async function open():Promise<DoctorConsultationDraft>{
    if(disposed)throw new DoctorDraftError('SESSION_EXPIRED');
    if(running)throw new DoctorDraftError('STATE_CONFLICT');
    running=true;current=null;expectedPatient=null;
    try{
      // Both reads are authenticated and test-profile restricted; the
      // context read supplies the patient reference, not a form field.
      expectedPatient=await readContext();
      const state=await request('GET');
      if(disposed)throw new DoctorDraftError('SESSION_EXPIRED');
      current=state;return state;
    }catch(failure){
      expectedPatient=null;
      current=null;
      throw failure;
    }finally{running=false;}
  }

  async function save(note:string):Promise<DoctorConsultationDraft>{
    if(disposed)throw new DoctorDraftError('SESSION_EXPIRED');
    if(running||!current)throw new DoctorDraftError('STATE_CONFLICT');
    if(typeof note!=='string'||!note.trim()||note.length>2000)
      throw new DoctorDraftError('INVALID_REQUEST');
    const previous=current;
    running=true;
    // On any uncertain response discard old revision and require a fresh read.
    current=null;
    try{
      const updated=await request('POST',{
        expectedRevision:previous.revision,consultationNote:note,
      });
      if(updated.stage!=='CONSULTATION_DRAFT'
        ||updated.revision!==previous.revision+1
        ||updated.patientId!==previous.patientId)
        throw new DoctorDraftError('UNVERIFIED_RESPONSE');
      if(disposed)throw new DoctorDraftError('SESSION_EXPIRED');
      current=updated;return updated;
    }finally{running=false;}
  }

  function dispose(){disposed=true;current=null;expectedPatient=null;}
  const snapshot=()=>Object.freeze({
    stage:current?.stage??null,revision:current?.revision??null,
    open:current!==null,
  });
  return Object.freeze({open,save,dispose,snapshot});
}
