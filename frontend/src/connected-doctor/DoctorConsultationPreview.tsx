import {useRef,useState,type FormEvent} from 'react';
import {signInStaff,revokeStaffToken,type StaffSession} from '../staffSessionClient.ts';
import {
  createDoctorConsultationCandidateFlow,type DoctorConsultationDraft,
} from '../doctorConsultationCandidateClient.ts';
import DoctorPrescriptionPreview from './DoctorPrescriptionPreview.tsx';
import {doctorCanReceiveHandoff,type DoctorHandoff} from '../receptionDoctorHandoff.ts';
import '../connected-reception/connected-reception.css';
import './doctor-preview.css';

type Screen='SIGN_IN'|'LOOKUP'|'DRAFT'|'RX';
type Credentials={tenantCode:string;username:string;password:string};
const emptyCredentials=():Credentials=>({tenantCode:'',username:'',password:''});
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default function DoctorConsultationPreview({handoff}:{handoff?:DoctorHandoff}={}){
  const sessionRef=useRef<StaffSession|null>(null);
  const flowRef=useRef<ReturnType<typeof createDoctorConsultationCandidateFlow>|null>(null);
  const [screen,setScreen]=useState<Screen>('SIGN_IN');
  const [credentials,setCredentials]=useState<Credentials>(emptyCredentials);
  const [branchId,setBranchId]=useState(handoff?.branchId??'');
  const [appointmentId,setAppointmentId]=useState(handoff?.appointmentId??'');
  const [confirmedSynthetic,setConfirmedSynthetic]=useState(false);
  const [staffLabel,setStaffLabel]=useState('');
  const [draft,setDraft]=useState<DoctorConsultationDraft|null>(null);
  const [note,setNote]=useState('');
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [success,setSuccess]=useState('');

  function explain(failure:unknown):string{
    return failure instanceof Error&&
      ['StaffSessionError','DoctorDraftError'].includes(failure.name)
      ?failure.message
      :'The server could not confirm this clinical draft. Reopen before saving.';
  }

  async function signIn(e:FormEvent<HTMLFormElement>){
    e.preventDefault();
    if(busy||!confirmedSynthetic)return;
    setBusy(true);setError('');setSuccess('');
    let session:StaffSession|null=null;
    try{
      if(!UUID.test(branchId))throw new Error('Invalid assigned branch');
      session=await signInStaff(credentials);
      if(!session.permissions.includes('OPD_DRAFT_WRITE'))
        throw new Error('Doctor draft permission denied');
      if(handoff&&!doctorCanReceiveHandoff(handoff,session,branchId))
        throw new Error('Assigned doctor or clinic did not match handoff');
      sessionRef.current=session;
      setStaffLabel(session.username);
      setCredentials(emptyCredentials());
      setScreen('LOOKUP');
    }catch(failure){
      if(session)await revokeStaffToken(session.accessToken);
      setError(handoff?'Doctor sign-in or handoff verification failed. Confirm the assigned doctor and branch.':explain(failure));
      setCredentials(v=>({...v,password:''}));
    }finally{setBusy(false);}
  }

  async function openConsultation(e:FormEvent<HTMLFormElement>){
    e.preventDefault();
    if(busy||!sessionRef.current)return;
    setBusy(true);setError('');setSuccess('');setDraft(null);setNote('');
    try{
      flowRef.current?.dispose();
      const flow=createDoctorConsultationCandidateFlow(
        sessionRef.current,branchId,appointmentId.trim());
      flowRef.current=flow;
      const current=await flow.open();
      if(handoff&&(current.patientId!==handoff.patientId
        ||current.appointmentId!==handoff.appointmentId
        ||current.doctorMembershipId!==handoff.doctorMembershipId))
        throw new Error('Consultation context did not match Reception handoff');
      setDraft(current);
      setNote(current.consultationNote);
      setScreen('DRAFT');
    }catch(failure){
      flowRef.current?.dispose();
      flowRef.current=null;
      setScreen('LOOKUP');
      setError(explain(failure));
    }finally{setBusy(false);}
  }

  async function saveConsultation(e:FormEvent<HTMLFormElement>){
    e.preventDefault();
    if(busy||!flowRef.current||!draft)return;
    setBusy(true);setError('');setSuccess('');
    try{
      const updated=await flowRef.current.save(note);
      setDraft(updated);
      setNote(updated.consultationNote);
      setSuccess('Draft saved and verified on the server. Revision '+updated.revision+'.');
    }catch(failure){
      // An uncertain save might have committed; discard stale state and
      // require a fresh doctor-only GET before the next modification.
      flowRef.current?.dispose();
      flowRef.current=null;
      setDraft(null);
      setNote('');
      setScreen('LOOKUP');
      setError(explain(failure));
    }finally{setBusy(false);}
  }

  function backToLookup(){
    if(busy)return;
    flowRef.current?.dispose();
    flowRef.current=null;
    setDraft(null);
    setNote('');
    setSuccess('');
    setError('');
    setAppointmentId(handoff?.appointmentId??'');
    setScreen('LOOKUP');
  }

  function signOut(){
    if(busy)return;
    const token=sessionRef.current?.accessToken;
    sessionRef.current=null;
    flowRef.current?.dispose();
    flowRef.current=null;
    setDraft(null);
    setNote('');
    setSuccess('');
    setError('');
    setAppointmentId(handoff?.appointmentId??'');
    setBranchId(handoff?.branchId??'');
    setCredentials(emptyCredentials());
    setConfirmedSynthetic(false);
    setStaffLabel('');
    setScreen('SIGN_IN');
    if(token)void revokeStaffToken(token);
  }

  return <div className="connected-shell">
    <header className="connected-header">
      <div className="connected-brand">
        <span className="brand-symbol">✚</span>
        <span>AroNexa <small>Doctor draft — disposable QA</small></span>
      </div>
      {screen!=='SIGN_IN'&&
        <button className="outline" disabled={busy} onClick={signOut}>
          Sign out{staffLabel?' · '+staffLabel:''}
        </button>}
    </header>
    <div className="connected-warning" role="status">
      <strong>TEST ONLY — NOT FOR REAL PATIENTS.</strong>
      {' '}This is a fictional, non-finalized consultation draft.
      No prescription is issued, signed, printed or shared. Drafts only.
    </div>
    <main className="connected-main">
      <div className="connected-heading">
        <p className="eyebrow">OPD CONSULTATION INTEGRATION</p>
        <h1>{screen==='SIGN_IN'?'Doctor sign-in'
          :screen==='LOOKUP'?'Open checked-in appointment'
          :screen==='RX'?'Smart prescription draft'
          :'Consultation draft'}</h1>
        <p>Only the assigned doctor can reopen and revise this synthetic draft.</p>
      </div>
      {error&&<div className="connected-error" role="alert">{error}</div>}
      {success&&<p className="connected-success" role="status">{success}</p>}

      {screen==='SIGN_IN'&&
        <section className="connected-panel" aria-label="Doctor sign-in">
          <h2>Verify doctor and branch</h2>
          <form onSubmit={signIn}>
            <label htmlFor="doctor-tenant">Clinic code</label>
            <input id="doctor-tenant" required autoComplete="off"
              value={credentials.tenantCode}
              onChange={e=>setCredentials(v=>({...v,tenantCode:e.target.value}))}/>
            <label htmlFor="doctor-user">Doctor username</label>
            <input id="doctor-user" required autoComplete="username"
              value={credentials.username}
              onChange={e=>setCredentials(v=>({...v,username:e.target.value}))}/>
            <label htmlFor="doctor-password">Doctor password</label>
            <input id="doctor-password" type="password" required autoComplete="current-password"
              value={credentials.password}
              onChange={e=>setCredentials(v=>({...v,password:e.target.value}))}/>
            <label htmlFor="doctor-branch">Assigned branch UUID</label>
            <input id="doctor-branch" required autoComplete="off"
              value={branchId} readOnly={!!handoff} onChange={e=>setBranchId(e.target.value.trim())}/>
            <label className="connected-check">
              <input type="checkbox" checked={confirmedSynthetic}
                onChange={e=>setConfirmedSynthetic(e.target.checked)}/>
              This is a disposable test clinic using fictional notes only.
            </label>
            <button className="solid" type="submit" disabled={busy||!confirmedSynthetic}>
              {busy?'Verifying…':'Sign in as doctor'}
            </button>
          </form>
        </section>}
      {screen==='LOOKUP'&&
        <section className="connected-panel" aria-label="Open checked-in appointment">
          <h2>Read consultation from the server</h2>
          <p>{handoff?'Reception checked in this appointment. Doctor authorization and patient context will be verified again by the server.':'Enter a server-issued appointment UUID that Reception has checked in.'}</p>
          <form onSubmit={openConsultation}>
            <label htmlFor="consult-appointment">Checked-in appointment UUID</label>
            <input id="consult-appointment" required autoComplete="off"
              value={appointmentId} readOnly={!!handoff}
              onChange={e=>setAppointmentId(e.target.value.trim())}/>
            <button className="solid" type="submit" disabled={busy}>
              {busy?'Opening…':'Open consultation'}
            </button>
          </form>
        </section>}
      {screen==='DRAFT'&&draft&&
        <section className="connected-panel" aria-label="Doctor consultation draft">
          <h2>Consultation note — draft only</h2>
          <p className="doctor-ref">Appointment: {draft.appointmentId}</p>
          <p className="doctor-ref">Patient reference: {draft.patientId}</p>
          <p className="doctor-note-info">
            Stage: {draft.stage} · Revision: {draft.revision}
          </p>
          <form onSubmit={saveConsultation}>
            <label htmlFor="consult-note">Clinical observations (fictional)</label>
            <textarea id="consult-note" className="doctor-notes" required
              maxLength={2000} value={note} disabled={busy}
              onChange={e=>{setNote(e.target.value);setSuccess('');}}/>
            <p className="doctor-note-info">{note.length}/2000 characters.
              Saving creates a new immutable draft revision.</p>
            <div className="doctor-actions">
              <button className="solid" type="submit" disabled={busy||!note.trim()}>
                {busy?'Saving…':'Save consultation draft'}
              </button>
              {draft.stage==='CONSULTATION_DRAFT'&&
                sessionRef.current?.permissions.includes('OPD_RX_DRAFT_WRITE')&&
                <button className="outline" type="button" disabled={busy}
                  onClick={()=>{setError('');setSuccess('');setScreen('RX');}}>
                  Open prescription draft
                </button>}
              <button className="outline" type="button" disabled={busy}
                onClick={backToLookup}>Close draft</button>
            </div>
          </form>
        </section>}
      {screen==='RX'&&sessionRef.current&&
        <DoctorPrescriptionPreview session={sessionRef.current}
          branchId={branchId} appointmentId={appointmentId}
          onClose={backToLookup}/>}
    </main>
    <footer className="connected-footer">
      Synthetic QA only · No patient care · No local storage · ARG Digital Technologies
    </footer>
  </div>;
}
