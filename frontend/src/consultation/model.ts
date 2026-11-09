/**
 * P02 isolated consultation model. Every record is fictional. No network, storage,
 * production authorization, or clinical decision-making occurs in this module.
 * The guard below is demonstrative only; any real backend MUST independently
 * enforce tenant, branch, doctor, role, module and patient-object permissions.
 */
export type PreviewRole = 'doctor' | 'receptionist';
export type ClinicalField = 'complaint' | 'history' | 'vitals' | 'assessment' | 'plan' | 'followUp';

export interface SyntheticPatient {
  id: string;
  displayName: string;
  ageLabel: string;
  visitLabel: string;
  allergyLabel: string;
  assignedDoctorId: string;
  lastVisit: string;
}
export interface ClinicalDraft {
  complaint: string;
  history: string;
  vitals: string;
  assessment: string;
  plan: string;
  followUp: string;
}
export interface PreviewAccess {
  role: PreviewRole;
  doctorId: string;
  tenantId: string;
  branchId: string;
}
export interface SavedDraft {
  patientId: string;
  authorId: string;
  version: number;
  savedAt: string;
  fields: ClinicalDraft;
  status: 'SYNTHETIC_DRAFT_ONLY';
}

export const DEMO_TENANT = 'SYNTHETIC-TENANT';
export const DEMO_BRANCH = 'SYNTHETIC-BRANCH';
export const DEMO_DOCTOR = 'DR-SYNTH-01';
export const SYNTHETIC_PATIENTS: readonly SyntheticPatient[] = [
  { id: 'SYN-P001', displayName: 'Sample Patient One', ageLabel: 'Adult · fictional', visitLabel: 'Walk-in OPD', allergyLabel: 'Penicillin (synthetic)', assignedDoctorId: DEMO_DOCTOR, lastVisit: 'No prior visit in this preview' },
  { id: 'SYN-P002', displayName: 'Sample Patient Two', ageLabel: 'Adult · fictional', visitLabel: 'Follow-up OPD', allergyLabel: 'None recorded (synthetic)', assignedDoctorId: DEMO_DOCTOR, lastVisit: 'Previous visit is not loaded' },
];

export const EMPTY_DRAFT: ClinicalDraft = {
  complaint: '', history: '', vitals: '', assessment: '', plan: '', followUp: '',
};

export function canViewSyntheticPatient(access: PreviewAccess, patient: SyntheticPatient | undefined): boolean {
  return Boolean(patient && access.role === 'doctor' && access.doctorId === patient.assignedDoctorId
    && access.tenantId === DEMO_TENANT && access.branchId === DEMO_BRANCH);
}

/** Pure, fail-closed save adapter; does not persist beyond caller's React memory. */
export function saveSyntheticDraft(
  access: PreviewAccess,
  patient: SyntheticPatient | undefined,
  fields: ClinicalDraft,
  previous: SavedDraft | null,
  savedAt: string,
): SavedDraft {
  if (!canViewSyntheticPatient(access, patient) || !patient) throw new Error('Access denied for this patient.');
  if (previous && (previous.patientId !== patient.id || previous.authorId !== access.doctorId)) {
    throw new Error('Draft context mismatch.');
  }
  const cleaned = {} as ClinicalDraft;
  for (const field of Object.keys(EMPTY_DRAFT) as ClinicalField[]) {
    const value = fields[field];
    if (typeof value !== 'string' || value.length > 2000) throw new Error('Invalid ' + field + ' length.');
    cleaned[field] = value.trim();
  }
  if (!Object.values(cleaned).some(Boolean)) throw new Error('Enter at least one clinical note before saving a draft.');
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/.test(savedAt) || Number.isNaN(Date.parse(savedAt))) {
    throw new Error('Invalid save timestamp.');
  }
  return {
    patientId: patient.id, authorId: access.doctorId, version: (previous?.version ?? 0) + 1,
    savedAt, fields: { ...cleaned }, status: 'SYNTHETIC_DRAFT_ONLY',
  };
}
