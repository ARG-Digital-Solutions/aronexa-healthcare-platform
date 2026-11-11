import { useState } from 'react';
import {
  canViewSyntheticPatient, DEMO_BRANCH, DEMO_DOCTOR, DEMO_TENANT,
  EMPTY_DRAFT, saveSyntheticDraft, SYNTHETIC_PATIENTS,
  type ClinicalDraft, type ClinicalField, type PreviewRole, type SavedDraft,
} from './model';
import './consultation.css';

const inputFields: ReadonlyArray<{ key: ClinicalField; label: string; hint: string; rows: number }> = [
  { key: 'complaint', label: 'Chief complaint', hint: 'Why is the patient visiting today?', rows: 2 },
  { key: 'history', label: 'Relevant history', hint: 'Symptoms and previous clinical context', rows: 2 },
  { key: 'vitals', label: 'Vitals', hint: 'BP, pulse, temperature or other observations', rows: 2 },
  { key: 'assessment', label: 'Assessment', hint: 'Clinician-entered impression; no AI diagnosis', rows: 2 },
  { key: 'plan', label: 'Plan and advice', hint: 'Clinical next steps; not a prescription', rows: 2 },
  { key: 'followUp', label: 'Follow-up', hint: 'Return instructions or follow-up date', rows: 2 },
];

export default function ConsultationPreview() {
  const [role, setRole] = useState<PreviewRole>('doctor');
  const [selectedId, setSelectedId] = useState(SYNTHETIC_PATIENTS[0].id);
  const [fields, setFields] = useState<ClinicalDraft>({ ...EMPTY_DRAFT });
  const [saved, setSaved] = useState<SavedDraft | null>(null);
  const [message, setMessage] = useState('Not saved. This is a fictional, in-memory preview.');
  const [hasEdited, setHasEdited] = useState(false);
  const patient = SYNTHETIC_PATIENTS.find((p) => p.id === selectedId);
  const access = { role, doctorId: DEMO_DOCTOR, tenantId: DEMO_TENANT, branchId: DEMO_BRANCH };
  const allowed = canViewSyntheticPatient(access, patient);

  function selectPatient(id: string) {
    setSelectedId(id);
    setFields({ ...EMPTY_DRAFT });
    setSaved(null);
    setHasEdited(false);
    setMessage('Patient switched. Unsaved edits from the previous patient were discarded.');
  }
  function edit(field: ClinicalField, value: string) {
    setFields((current) => ({ ...current, [field]: value }));
    setHasEdited(true);
    setMessage('Unsaved changes · synthetic preview only.');
  }
  function saveDraft() {
    try {
      const next = saveSyntheticDraft(access, patient, fields, saved, new Date().toISOString());
      setSaved(next);
      setHasEdited(false);
      setMessage('Synthetic draft version ' + next.version + ' saved in browser memory. Not a clinical record.');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Unable to save.');
    }
  }

  return (
    <div className="consult-root">
      <header className="consult-header">
        <div className="consult-header-inner">
          <div><div className="consult-brand">AroNexa <span>Clinic Edition</span></div><div className="consult-subbrand">Doctor consultation · internal demo</div></div>
          <div className="consult-demochip">SYNTHETIC DATA ONLY</div>
        </div>
      </header>
      <main className="consult-container">
        <div className="consult-title">
          <div><p className="consult-eyebrow">OPD · Doctor workspace</p><h1>Consultation</h1><p>One patient, one workspace. No live registration or database connection.</p></div>
          <div className="consult-role"><label htmlFor="preview-role">Preview role</label><select id="preview-role" value={role} onChange={(e) => setRole(e.target.value as PreviewRole)}><option value="doctor">Doctor (demo)</option><option value="receptionist">Receptionist (deny demo)</option></select></div>
        </div>
        <div className="consult-layout">
          <aside className="consult-side" aria-label="Synthetic patient selector">
            <h2>Today's demo patients</h2><p className="consult-helper">Fictional records only</p>
            <div className="consult-patients">
              {SYNTHETIC_PATIENTS.map((p) => (
                <button key={p.id} type="button" className={selectedId === p.id ? 'consult-patient-choice is-selected' : 'consult-patient-choice'} aria-pressed={selectedId === p.id} onClick={() => selectPatient(p.id)}>
                  <strong>{p.displayName}</strong><span>{p.id} · {p.visitLabel}</span>
                </button>
              ))}
            </div>
            <div className="consult-side-note"><strong>Preview limitations</strong><p>No persistence, API, prescription, clinical finalization, or production authorization. Role switch demonstrates interface denial only.</p></div>
          </aside>
          <section className="consult-main" aria-label="Consultation workspace">
            {!allowed ? (
              <div className="consult-denied" role="alert"><h2>Access restricted</h2><p>Only the assigned doctor can view this synthetic consultation preview. Real access must be enforced by the backend and database.</p></div>
            ) : (
              <>
                <div className="consult-patient-summary"><div><p className="consult-eyebrow">Current patient</p><h2>{patient?.displayName}</h2><p>{patient?.id} · {patient?.ageLabel} · {patient?.visitLabel}</p></div><div className="consult-allergy" role="note"><strong>Allergy reminder</strong><span>{patient?.allergyLabel}</span></div></div>
                <div className="consult-history"><strong>Previous clinical context</strong><span>{patient?.lastVisit}</span></div>
                <form onSubmit={(e) => { e.preventDefault(); saveDraft(); }}>
                  <div className="consult-form-header"><div><h3>Clinical notes</h3><p>Draft only. Clinician must validate before any future finalization.</p></div><span className="consult-draft-status">{saved && !hasEdited ? 'Draft v' + saved.version : 'Unsaved draft'}</span></div>
                  <div className="consult-form-grid">
                    {inputFields.map((item) => <div className="consult-field" key={item.key}><label htmlFor={'consult-' + item.key}>{item.label}</label><textarea id={'consult-' + item.key} value={fields[item.key]} maxLength={2000} rows={item.rows} placeholder={item.hint} onChange={(e) => edit(item.key, e.target.value)} /></div>)}
                  </div>
                  <div className="consult-actions"><button type="submit">Save synthetic draft</button><span>Drafts disappear on refresh. Do not enter real patient information.</span></div>
                </form>
              </>
            )}
            <div className="consult-live" aria-live="polite" role="status">{message}</div>
          </section>
        </div>
      </main>
    </div>
  );
}
