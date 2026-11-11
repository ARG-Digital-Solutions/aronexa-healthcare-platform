/** Phase 02 synthetic draft: no persistence, real authorization or medical advice. */
export type PreviewRole = 'doctor' | 'receptionist';
export interface PreviewAccess { role: PreviewRole; tenantId: string; branchId: string; doctorId: string }
export interface PatientContext { id: string; name: string; tenantId: string; branchId: string; assignedDoctorId: string; allergyReminder: string }
export interface MedicineEntry { id: string; medicine: string; strength: string; form: string; dose: string; route: string; frequency: string; duration: string; quantity: number; instructions: string }
export interface InkPoint { x: number; y: number; pressure: number }
export interface InkStroke { id: string; points: InkPoint[] }
export interface RxDraft { patientId: string; doctorId: string; tenantId: string; branchId: string; medicines: MedicineEntry[]; note: string; ink: InkStroke[]; revision: number; status: 'SYNTHETIC_DRAFT' }
export const FIXTURE_ACCESS: PreviewAccess = { role:'doctor', tenantId:'DEMO-TENANT', branchId:'DEMO-BRANCH', doctorId:'DEMO-DOCTOR' };
export const FIXTURE_PATIENT: PatientContext = { id:'DEMO-PATIENT-001', name:'Fictional Patient Alpha', tenantId:'DEMO-TENANT', branchId:'DEMO-BRANCH', assignedDoctorId:'DEMO-DOCTOR', allergyReminder:'Allergies unverified — demonstration only' };
export const SAMPLE_CATALOGUE = ['Example Tablet A','Example Capsule B','Example Syrup C'];
function fail(message: string): never { throw new Error(message); }
function trimmed(value: unknown, label: string, max: number, required: boolean): string {
 if (typeof value !== 'string') fail(label+' must be text');
 const result=value.trim();
 if (result.length>max || (required && !result)) fail('Invalid '+label);
 return result;
}
export function canEdit(access: PreviewAccess, patient: PatientContext): boolean {
 return access.role==='doctor' && access.tenantId===patient.tenantId && access.branchId===patient.branchId && access.doctorId===patient.assignedDoctorId;
}
function owner(access: PreviewAccess, patient: PatientContext, draft?: RxDraft) {
 if (!canEdit(access,patient)) fail('Access denied for this patient');
 if (draft && (draft.patientId!==patient.id || draft.doctorId!==access.doctorId || draft.tenantId!==access.tenantId || draft.branchId!==access.branchId || draft.status!=='SYNTHETIC_DRAFT')) fail('Draft context mismatch');
}
const copyMedicines=(xs: MedicineEntry[])=>xs.map(x=>({...x}));
const copyInk=(xs: InkStroke[])=>xs.map(x=>({id:x.id,points:x.points.map(p=>({...p}))}));
function next(d: RxDraft): RxDraft {return {...d,revision:d.revision+1,medicines:copyMedicines(d.medicines),ink:copyInk(d.ink)};}
export function newDraft(access: PreviewAccess,patient: PatientContext): RxDraft {
 owner(access,patient);
 return {patientId:patient.id,doctorId:access.doctorId,tenantId:access.tenantId,branchId:access.branchId,medicines:[],note:'',ink:[],revision:0,status:'SYNTHETIC_DRAFT'};
}
export function normalizeMedicine(m: MedicineEntry): MedicineEntry {
 if (!m || typeof m!=='object') fail('Missing medicine entry');
 if (!Number.isSafeInteger(m.quantity) || m.quantity<1 || m.quantity>10000) fail('Quantity must be between 1 and 10000');
 return {id:trimmed(m.id,'entry ID',80,true),medicine:trimmed(m.medicine,'medicine',120,true),strength:trimmed(m.strength,'strength',80,true),
  form:trimmed(m.form,'form',50,true),dose:trimmed(m.dose,'dose',120,true),route:trimmed(m.route,'route',60,true),
  frequency:trimmed(m.frequency,'frequency',100,true),duration:trimmed(m.duration,'duration',100,true),quantity:m.quantity,
  instructions:trimmed(m.instructions,'instructions',500,false)};
}
export function addMedicine(access:PreviewAccess,patient:PatientContext,draft:RxDraft,entry:MedicineEntry):RxDraft {
 owner(access,patient,draft); const item=normalizeMedicine(entry);
 if(draft.medicines.length>=30)fail('Maximum number of items reached');
 if(draft.medicines.some(m=>m.id===item.id))fail('Duplicate item ID');
 const result=next(draft);result.medicines.push(item);return result;
}
export function removeMedicine(access:PreviewAccess,patient:PatientContext,draft:RxDraft,id:string):RxDraft {
 owner(access,patient,draft);if(!draft.medicines.some(m=>m.id===id))fail('Item not found');
 const result=next(draft);result.medicines=result.medicines.filter(m=>m.id!==id);return result;
}
export function setNote(access:PreviewAccess,patient:PatientContext,draft:RxDraft,note:string):RxDraft {
 owner(access,patient,draft);const result=next(draft);result.note=trimmed(note,'clinical ink note',2000,false);return result;
}
export function setInk(access:PreviewAccess,patient:PatientContext,draft:RxDraft,strokes:InkStroke[]):RxDraft {
 owner(access,patient,draft);
 if(!Array.isArray(strokes)||strokes.length>100)fail('Too many ink strokes');
 const normalized=strokes.map(s=>{
  const id=trimmed(s.id,'stroke ID',80,true);
  if(!Array.isArray(s.points)||!s.points.length||s.points.length>3000)fail('Invalid stroke size');
  return {id,points:s.points.map(p=>{
   if(![p.x,p.y,p.pressure].every(v=>Number.isFinite(v)&&v>=0&&v<=1))fail('Invalid stroke point');
   return {x:p.x,y:p.y,pressure:p.pressure};
  })};
 });
 if(new Set(normalized.map(s=>s.id)).size!==normalized.length)fail('Duplicate stroke IDs');
 const result=next(draft);result.ink=normalized;return result;
}
export function previewDraft(access:PreviewAccess,patient:PatientContext,draft:RxDraft) {
 owner(access,patient,draft);if(!draft.medicines.length)fail('Add at least one structured medicine for preview');
 return {patient:patient.name,...draft,medicines:copyMedicines(draft.medicines),ink:copyInk(draft.ink),label:'DEMO ONLY — INVALID AS PRESCRIPTION',status:'SYNTHETIC_DRAFT' as const};
}
