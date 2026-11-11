import { useState } from 'react';
import { ACTORS, FICTIONAL_CONTEXT, LABELS, ORDER, RULES, createJourney, nextAction, transition, type DemoRole, type JourneyState } from './model';
import './journey.css';

const ROLE_NAMES: Record<DemoRole, string> = { receptionist: 'Receptionist', doctor: 'Doctor', billing: 'Billing staff' };
const ROLE_HINTS: Record<DemoRole, string> = {
  receptionist: 'Intake, appointment and check-in',
  doctor: 'Consultation and prescription drafts',
  billing: 'Bill and daily-report previews',
};
const STAGE_HELP: Record<keyof typeof LABELS, string> = {
  INTAKE_REVIEW: 'Review possible matches before confirming fictional intake. No patient records are merged.',
  REGISTERED: 'Fictional identity review confirmed. Appointment can be booked.',
  BOOKED: 'Example appointment reserved; ready for receptionist check-in.',
  CHECKED_IN: 'Example visit checked in. Doctor can begin a consultation draft.',
  CONSULT_DRAFT: 'Consultation draft simulated. Nothing has been signed or stored.',
  RX_DRAFT: 'Prescription draft preview only; no medicine has been issued or finalized.',
  BILL_DRAFT: 'Example billing draft only. No invoice, payment or financial posting.',
  REPORT_PREVIEW: 'Fictional daily report view complete. No real receipts or clinical records.',
};

export default function OpdJourneyPreview() {
  const [role, setRole] = useState<DemoRole>('receptionist');
  const [journey, setJourney] = useState<JourneyState>(() => createJourney());
  const [message, setMessage] = useState('Ready to review a fictional patient. No real data or network calls.');
  const action = nextAction(journey.stage);
  const requiredRole = action ? RULES[action].role : null;
  const permitted = requiredRole === role;
  const nextStage = action ? RULES[action].to : null;
  const currentIndex = ORDER.indexOf(journey.stage);

  function advance() {
    if (!action) return;
    try {
      const eventId = 'SYN-EVT-' + String(journey.revision + 1).padStart(3, '0');
      const updated = transition(journey, ACTORS[role], action, eventId, journey.revision);
      setJourney(updated);
      setMessage(LABELS[updated.stage] + ' — fictional step recorded only in this browser session.');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'The requested action was refused.');
    }
  }
  function reset() {
    setJourney(createJourney());
    setRole('receptionist');
    setMessage('Fictional journey reset. There are no stored clinical or billing records.');
  }

  return (
    <div className="journey-root">
      <header className="journey-header">
        <div className="journey-header-inner">
          <div>
            <div className="journey-brand">AroNexa <span>Clinic Edition</span></div>
            <div className="journey-caption">OPD workflow integration · Synthetic preview</div>
          </div>
          <span className="journey-flag">SYNTHETIC ONLY · NOT FOR PATIENT CARE</span>
        </div>
      </header>
      <main className="journey-main">
        <div className="journey-intro">
          <div>
            <p className="journey-eyebrow">Windows-first · Shared clinical workflow</p>
            <h1>One OPD visit, start to finish</h1>
            <p>Explore how reception, doctor and billing roles hand off the same fictional visit—without re-entering a patient or encounter.</p>
          </div>
          <div className="journey-role">
            <label htmlFor="journey-role">Preview persona</label>
            <select id="journey-role" value={role} onChange={(e) => { setRole(e.target.value as DemoRole); setMessage('Persona changed for this demo only. Production switching requires a new authorized session.'); }}>
              <option value="receptionist">Receptionist (demo)</option>
              <option value="doctor">Doctor (demo)</option>
              <option value="billing">Billing staff (demo)</option>
            </select>
            <small>{ROLE_HINTS[role]}</small>
          </div>
        </div>
        <section className="journey-patient" aria-label="Fictional visit context">
          <div>
            <p className="journey-eyebrow">Single visit context</p>
            <h2>Example Patient A</h2>
            <p>Adult · fictional OPD · no medical history or real identifiers</p>
          </div>
          <dl>
            <div><dt>Patient</dt><dd>{FICTIONAL_CONTEXT.patientId}</dd></div>
            <div><dt>Encounter</dt><dd>{FICTIONAL_CONTEXT.encounterId}</dd></div>
            <div><dt>Branch</dt><dd>{FICTIONAL_CONTEXT.branchId}</dd></div>
            <div><dt>Doctor</dt><dd>{FICTIONAL_CONTEXT.assignedDoctorId}</dd></div>
          </dl>
        </section>
        <div className="journey-layout">
          <section className="journey-card" aria-label="OPD journey progress">
            <div className="journey-card-title"><h2>Visit progress</h2><span>{currentIndex + 1} of {ORDER.length}</span></div>
            <ol className="journey-steps">
              {ORDER.map((stage, index) => {
                const done = index < currentIndex;
                const active = index === currentIndex;
                return (
                  <li key={stage} className={active ? 'journey-step active' : done ? 'journey-step done' : 'journey-step'} aria-current={active ? 'step' : undefined}>
                    <span className="journey-step-number" aria-hidden="true">{done ? '✓' : index + 1}</span>
                    <span><strong>{LABELS[stage]}</strong><small>{active ? 'Current stage' : done ? 'Synthetic step complete' : 'Not started'}</small></span>
                  </li>
                );
              })}
            </ol>
          </section>
          <section className="journey-card journey-workspace" aria-label="Role-specific next action">
            <div className="journey-card-title"><h2>{ROLE_NAMES[role]} workspace</h2><span className="journey-demo-label">DEMO · NO PERSISTENCE</span></div>
            <p className="journey-eyebrow">Current visit status</p>
            <h3>{LABELS[journey.stage]}</h3>
            <p className="journey-description">{STAGE_HELP[journey.stage]}</p>
            <div className="journey-state-details">
              <div><span>Current stage</span><strong>{journey.stage.replaceAll('_', ' ')}</strong></div>
              <div><span>Model revision</span><strong>{journey.revision}</strong></div>
              <div><span>Patient context</span><strong>Unchanged</strong></div>
            </div>
            {action && nextStage ? (
              <>
                <div className="journey-next">
                  <p className="journey-eyebrow">Next handoff</p>
                  <strong>{LABELS[nextStage]}</strong>
                  <span>Required role: {ROLE_NAMES[requiredRole!]}</span>
                </div>
                {!permitted && <p className="journey-restricted" role="note">The {ROLE_NAMES[requiredRole!]} must perform this step. Change the demo persona above to explore that handoff. This does not grant real access.</p>}
                <button type="button" disabled={!permitted} onClick={advance}>{RULES[action].buttonLabel}</button>
              </>
            ) : (
              <div className="journey-complete"><strong>Fictional workflow preview finished</strong><p>No prescription was issued and no financial transaction occurred.</p></div>
            )}
            <div className="journey-actions"><button className="journey-secondary" type="button" onClick={reset}>Reset fictional visit</button></div>
            <p className="journey-live" role="status" aria-live="polite">{message}</p>
          </section>
        </div>
        <section className="journey-card journey-audit" aria-label="Fictional event history">
          <div className="journey-card-title"><h2>Simulated handoff history</h2><span>{journey.history.length} events</span></div>
          {journey.history.length === 0 ? <p>No fictional actions completed yet.</p> : (
            <ol>
              {journey.history.map((event) => (
                <li key={event.id}><strong>{LABELS[event.to]}</strong><span>{event.actorId} · revision {event.revision}</span></li>
              ))}
            </ol>
          )}
        </section>
        <p className="journey-disclaimer">Preview only. All transitions are in memory. This screen has no login, APIs, prescriptions, billing transactions, file storage, database access or production authorization. Actual access control must be enforced by the backend and PostgreSQL.</p>
      </main>
    </div>
  );
}
