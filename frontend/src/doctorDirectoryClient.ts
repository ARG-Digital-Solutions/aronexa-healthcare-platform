import type {StaffSession} from './staffSessionClient.ts';

/**
 * RC02A: read-only branch-scoped doctor choices. This endpoint is OFF by
 * default and only enabled inside fictional, disposable QA until independent
 * authorization/revocation review. No names or private patient data are used.
 */
export interface DoctorChoice {
  readonly membershipId:string;
  readonly label:string;
}
export class DoctorDirectoryError extends Error {
  constructor(){
    super('The doctor list is unavailable. Verify your clinic access and try again.');
    this.name='DoctorDirectoryError';
  }
}
type Transport=(url:string,init:RequestInit)=>Promise<Response>;
const request:Transport=(url,init)=>fetch(url,init);
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const good=(x:unknown):x is string=>typeof x==='string'
  &&uuid.test(x)&&x.toLowerCase()!=='00000000-0000-0000-0000-000000000000';

export async function loadDoctorChoices(
  session:StaffSession,branchId:string,transport:Transport=request,
):Promise<readonly DoctorChoice[]>{
  if(!session||!good(session.tenantId)||!good(branchId)
    ||!Array.isArray(session.permissions)
    ||!session.permissions.includes('APPOINTMENT_BOOK')
    ||typeof session.accessToken!=='string'
    ||!/^[A-Za-z0-9_-]{43}$/.test(session.accessToken))
    throw new DoctorDirectoryError();
  try{
    const res=await transport('/api/v1/branches/'+branchId+'/opd/doctors',{
      method:'GET',cache:'no-store',credentials:'omit',redirect:'error',
      signal:AbortSignal.timeout(10000),
      headers:{Accept:'application/json',
        Authorization:'Bearer '+session.accessToken},
    });
    if(res.status!==200)throw new DoctorDirectoryError();
    const data:unknown=await res.json();
    if(!Array.isArray(data)||data.length>25)
      throw new DoctorDirectoryError();
    const ids=new Set<string>();
    return Object.freeze(data.map((v:unknown)=>{
      if(!v||typeof v!=='object'||Array.isArray(v))
        throw new DoctorDirectoryError();
      const x=v as Record<string,unknown>;
      if(!good(x.membershipId)||ids.has(x.membershipId)
        ||typeof x.label!=='string'||x.label.length<3
        ||x.label.length>120||x.label.trim()!==x.label
        ||/[\u0000-\u001f\u007f]/.test(x.label))
        throw new DoctorDirectoryError();
      ids.add(x.membershipId);
      return Object.freeze({
        membershipId:x.membershipId,label:x.label,
      });
    }));
  }catch{
    throw new DoctorDirectoryError();
  }
}
