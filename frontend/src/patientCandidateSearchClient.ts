import type {StaffSession} from './staffSessionClient.ts';

/** RC02B: default-off candidate search. Synthetic records only until reviewed. */
export type PatientSearchMode='MRN'|'NAME_PREFIX';
export interface PatientCandidate {
  readonly tenantId:string;
  readonly branchId:string;
  readonly registrationId:string;
  readonly patientId:string;
  readonly displayName:string;
  readonly mrn:string;
}
export class PatientSearchError extends Error {
  constructor(){
    super('Patient search could not be verified. Check access and refine your search.');
    this.name='PatientSearchError';
  }
}
type Transport=(url:string,init:RequestInit)=>Promise<Response>;
const transportDefault:Transport=(url,init)=>fetch(url,init);
const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const isId=(v:unknown):v is string=>typeof v==='string'
  &&uuid.test(v)&&v.toLowerCase()!=='00000000-0000-0000-0000-000000000000';
const mrn=/^[A-Z0-9][A-Z0-9-]{2,47}$/;
const prefix=/^[A-Za-z][A-Za-z .'-]{2,59}$/;

export async function findPatientCandidates(
  session:StaffSession,branchId:string,mode:PatientSearchMode,
  query:string,transport:Transport=transportDefault,
):Promise<readonly PatientCandidate[]> {
  if(!session||!isId(session.tenantId)||!isId(branchId)
     ||!Array.isArray(session.permissions)
     ||!session.permissions.includes('PATIENT_CANDIDATE_SEARCH')
     ||!session.permissions.includes('PATIENT_REGISTRATION_READ')
     ||typeof session.accessToken!=='string'
     ||!/^[A-Za-z0-9_-]{43}$/.test(session.accessToken)
     ||typeof query!=='string')
    throw new PatientSearchError();
  const term=query.trim();
  if(!((mode==='MRN'&&mrn.test(term))
        ||(mode==='NAME_PREFIX'&&prefix.test(term))))
    throw new PatientSearchError();
  try {
    const url='/api/v1/branches/'+branchId+'/patient-search?mode='+mode+
        '&term='+encodeURIComponent(term);
    const response=await transport(url,{
      method:'GET',cache:'no-store',credentials:'omit',redirect:'error',
      signal:AbortSignal.timeout(10000),
      headers:{Accept:'application/json',Authorization:'Bearer '+session.accessToken},
    });
    if(response.status!==200)throw new PatientSearchError();
    const body:unknown=await response.json();
    if(!Array.isArray(body)||body.length>10)
      throw new PatientSearchError();
    const seen=new Set<string>();
    return Object.freeze(body.map((candidate:unknown)=>{
      if(!candidate||Array.isArray(candidate)||typeof candidate!=='object')
        throw new PatientSearchError();
      const v=candidate as Record<string,unknown>;
      if(!isId(v.tenantId)||v.tenantId!==session.tenantId
         ||!isId(v.branchId)||v.branchId!==branchId
         ||!isId(v.patientId)||!isId(v.registrationId)
         ||seen.has(v.registrationId)
         ||typeof v.displayName!=='string'
         ||v.displayName.length<3||v.displayName.length>180
         ||typeof v.mrn!=='string'||!mrn.test(v.mrn))
        throw new PatientSearchError();
      seen.add(v.registrationId);
      return Object.freeze({
        tenantId:v.tenantId,branchId:v.branchId,
        patientId:v.patientId,registrationId:v.registrationId,
        displayName:v.displayName,mrn:v.mrn,
      });
    }));
  }catch{
    // No raw server body, name, MRN, bearer or network exception exposed.
    throw new PatientSearchError();
  }
}
