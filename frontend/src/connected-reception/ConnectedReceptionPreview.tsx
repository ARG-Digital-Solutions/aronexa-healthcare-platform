import {useRef, useState, type FormEvent} from 'react';
import {blankIntake, localCalendarDate, type Intake} from '../receptionModel.ts';
import {signInStaff, revokeStaffToken, type StaffSession} from '../staffSessionClient.ts';
import {
  createConnectedRegistrationFlow,
  type ConnectedRegistrationFlow,
  type ReviewedRegistration,
} from '../connectedRegistrationFlow.ts';
import type {OpdJourney} from '../opdJourney.ts';
import type {RegistrationReference} from '../registrationReferenceClient.ts';
import {createRegistrationRecoveryFlow} from '../registrationRecoveryFlow.ts';
import {createAppointmentCandidateFlow, loadAppointmentCandidateQueue,
  type AppointmentCandidateReceipt} from '../appointmentCandidateClient.ts';
import {summarizeReceptionQueue} from '../receptionQueueSummary.ts';
import {prepareDoctorHandoff,type DoctorHandoff} from '../receptionDoctorHandoff.ts';
import {verifyProductionOpdIntake} from '../productionOpdIntakeClient.ts';
import {verifiedRegistrationFromQueue} from '../queueRegistrationContinuity.ts';
import {revokeStaffTokenAndConfirm} from '../staffSessionClient.ts';
import './connected-reception.css';

type Screen='SIGN_IN'|'PATIENT_DETAILS'|'REVIEW'|'UNRESOLVED'|'REGISTERED'|'RECOVERY_LOOKUP'|'REFERENCE_FOUND'|'APPOINTMENT'|'APPOINTMENT_BOOKED'|'APPOINTMENT_QUEUE';
type Credentials={tenantCode:string;username:string;password:string};
const emptyCredentials=():Credentials=>({tenantCode:'',username:'',password:''});

/**
 * Explicitly isolated, synthetic-only integration preview.
 *
 * Not imported by the production App.tsx or any normal Vite build entry.
 * Test-only backend registration profiles are required for writes; ordinary
 * production /dev registration POST remains denied by Spring Security.
 *
 * An approved future commercial Reception workflow must add verified branch
 * selection, user provisioning, clinical privacy/consent, migration readiness,
 * accessibility testing and a properly secured install process.
 */
export default function ConnectedReceptionPreview({onDoctorHandoff}:{onDoctorHandoff?:(handoff:DoctorHandoff)=>void}={}){
  const sessionRef=useRef<StaffSession|null>(null);
  const flowRef=useRef<ConnectedRegistrationFlow|null>(null);
  const recoveryRef=useRef<ReturnType<typeof createRegistrationRecoveryFlow>|null>(null);
  const appointmentRef=useRef<ReturnType<typeof createAppointmentCandidateFlow>|null>(null);
  const [screen,setScreen]=useState<Screen>('SIGN_IN');
  const [credentials,setCredentials]=useState<Credentials>(emptyCredentials);
  const [branchId,setBranchId]=useState('');
  const [testOnly,setTestOnly]=useState(false);
  const [staffLabel,setStaffLabel]=useState('');
  const [form,setForm]=useState<Intake>(blankIntake);
  const [reviewed,setReviewed]=useState<ReviewedRegistration|null>(null);
  const [saved,setSaved]=useState<OpdJourney|null>(null);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [readBack,setReadBack]=useState('');
  const [intakeStatus,setIntakeStatus]=useState('');
  const [lookupId,setLookupId]=useState('');
  const [foundReference,setFoundReference]=useState<RegistrationReference|null>(null);
  const [canRead,setCanRead]=useState(false);
  const [canBook,setCanBook]=useState(false);
  const [canReadAppointments,setCanReadAppointments]=useState(false);
  const [queueDate,setQueueDate]=useState(localCalendarDate());
  const [queueRows,setQueueRows]=useState<readonly AppointmentCandidateReceipt[]>([]);
  const [queueLoaded,setQueueLoaded]=useState(false);
  const [editAppointment,setEditAppointment]=useState(false);
  const [cancelConfirm,setCancelConfirm]=useState(false);
  const [newDoctor,setNewDoctor]=useState('');
  const [newDate,setNewDate]=useState(localCalendarDate());
  const [newSlot,setNewSlot]=useState('09:00');
  const [appointmentContext,setAppointmentContext]=useState<RegistrationReference|null>(null);
  const [appointmentReceipt,setAppointmentReceipt]=useState<AppointmentCandidateReceipt|null>(null);
  const [appointmentDoctor,setAppointmentDoctor]=useState('');
  const [appointmentDate,setAppointmentDate]=useState(localCalendarDate());
  const [appointmentSlot,setAppointmentSlot]=useState('09:00');
  const [appointmentLookupId,setAppointmentLookupId]=useState('');

  function explain(failure:unknown){
    // Never render error stack traces, network paths, tokens or server payloads.
    return failure instanceof Error && (
      ['StaffSessionError','RegistrationError','RegistrationReferenceError',
        'RegistrationRecoveryError','AppointmentCandidateError',
        'ConnectedFlowError','OpdLinkError'].includes(failure.name))
      ? failure.message
      : 'The operation could not be completed. Review its status before continuing.';
  }

  async function signIn(e:FormEvent<HTMLFormElement>){
    e.preventDefault();
    if(busy || !testOnly)return;
    setBusy(true);setError('');
    let session:StaffSession|null=null;
    try {
      session=await signInStaff(credentials);
      const write=session.permissions.includes('PATIENT_REGISTER');
      const read=session.permissions.includes('PATIENT_REGISTRATION_READ');
      const book=session.permissions.includes('APPOINTMENT_BOOK');
      const readAppointments=session.permissions.includes('APPOINTMENT_READ');
      if(!write&&!read&&!readAppointments)
        throw new Error('No patient or appointment permission');
      const flow=write?createConnectedRegistrationFlow(session,branchId):null;
      const recovery=read?createRegistrationRecoveryFlow(session,branchId):null;
      sessionRef.current=session;
      flowRef.current=flow;
      recoveryRef.current=recovery;
      setCanRead(read);
      setCanBook(book);
      setCanReadAppointments(readAppointments);
      setQueueRows([]);
      setQueueLoaded(false);
      setQueueDate(localCalendarDate());
      setStaffLabel(session.username);
      setCredentials(emptyCredentials());
      setScreen(write?'PATIENT_DETAILS':read?'RECOVERY_LOOKUP':'APPOINTMENT_QUEUE');
    }catch(failure){
      if(session)await revokeStaffToken(session.accessToken);
      setCredentials(old=>({...old,password:''}));
      setError(explain(failure));
    }finally{setBusy(false);}
  }

  function update<K extends keyof Intake>(field:K,value:Intake[K]){
    setForm(old=>({
      ...old,[field]:value,
      ...(field==='precision' && value==='UNKNOWN' ? {dob:''} : {}),
    }));
    setError('');
  }

  function review(e:FormEvent<HTMLFormElement>){
    e.preventDefault();
    try{
      if(!flowRef.current)throw new Error('Missing staff session');
      const next=flowRef.current.prepare(form);
      setReviewed(next);
      setError('');
      setScreen('REVIEW');
    }catch(failure){setError(explain(failure));}
  }

  async function submit(){
    if(busy || !flowRef.current)return;
    setBusy(true);setError('');
    try{
      const next=await flowRef.current.submitReviewed();
      setSaved(next);
      setReviewed(null);
      setForm(blankIntake());
      setReadBack('');
      setScreen('REGISTERED');
    }catch(failure){
      setError(explain(failure));
      // Even when an error says 409 or 403, never produce a demo identity.
      // Preserve the same attempt and idempotency key for explicit review.
      setScreen('UNRESOLVED');
    }finally{setBusy(false);}
  }

  function editReview(){
    if(!flowRef.current)return;
    try{
      flowRef.current.cancelReview();
      setReviewed(null);
      setScreen('PATIENT_DETAILS');
      setError('');
    }catch(failure){setError(explain(failure));}
  }

  function nextPatient(){
    if(!flowRef.current)return;
    try{
      flowRef.current.nextPatient();
      setSaved(null);
      setReadBack('');
      setIntakeStatus('');
      setForm(blankIntake());
      setScreen('PATIENT_DETAILS');
      setError('');
    }catch(failure){setError(explain(failure));}
  }

  async function verifySaved(){
    if(busy || !flowRef.current)return;
    setBusy(true);setError('');
    try{
      const matches=await flowRef.current.verifySavedRegistration();
      setReadBack(matches
        ? 'Authorized server read-back confirms this registration/patient reference.'
        : 'Read-back did not match. Stop and investigate before continuing.');
    }catch(failure){setError(explain(failure));}
    finally{setBusy(false);}
  }

  async function verifyIntake(reference:RegistrationReference){
    if(busy||!canRead||!canBook||!sessionRef.current)return;
    setBusy(true);setIntakeStatus('');setError('');
    try{
      await verifyProductionOpdIntake(sessionRef.current,reference);
      setIntakeStatus('OPD registration intake verified by the production-source read API. No appointment has been booked.');
    }catch{
      setIntakeStatus('');
      setError('OPD intake is unavailable. Do not book based on this check.');
    }finally{setBusy(false);}
  }

  function showRecovery(){
    if(busy||!recoveryRef.current)return;
    appointmentRef.current?.dispose();
    appointmentRef.current=null;
    setAppointmentReceipt(null);
    setAppointmentContext(null);
    setError('');
    setFoundReference(null);
    setLookupId('');
    setIntakeStatus('');
    setScreen('RECOVERY_LOOKUP');
  }

  async function lookupExisting(e:FormEvent<HTMLFormElement>){
    e.preventDefault();
    if(busy||!recoveryRef.current)return;
    setBusy(true);setError('');setFoundReference(null);
    setIntakeStatus('');
    try{
      const found=await recoveryRef.current.recover(lookupId.trim());
      setFoundReference(found);
      setScreen('REFERENCE_FOUND');
    }catch(failure){setError(explain(failure));}
    finally{setBusy(false);}
  }

  function returnToRegistration(){
    if(!flowRef.current)return;
    try{
      if(flowRef.current.snapshot().status==='REGISTERED')flowRef.current.nextPatient();
      setFoundReference(null);
      setLookupId('');
      setIntakeStatus('');
      setSaved(null);
      setForm(blankIntake());
      setError('');
      setScreen('PATIENT_DETAILS');
    }catch(failure){setError(explain(failure));}
  }

  function openBooking(reference:RegistrationReference){
    if(busy||!sessionRef.current||!canBook)return;
    try{
      appointmentRef.current?.dispose();
      appointmentRef.current=createAppointmentCandidateFlow(sessionRef.current,reference);
      setAppointmentContext(reference);
      setAppointmentReceipt(null);
      setAppointmentDoctor('');
      setAppointmentDate(localCalendarDate());
      setAppointmentSlot('09:00');
      setAppointmentLookupId('');
      setError('');
      setScreen('APPOINTMENT');
    }catch(failure){setError(explain(failure));}
  }

  function openSavedRegistrationBooking(){
    if(!saved)return;
    openBooking({tenantId:saved.tenantId,branchId:saved.branchId,
      registrationId:saved.registrationId,patientId:saved.patientId});
  }

  async function submitAppointment(e:FormEvent<HTMLFormElement>){
    e.preventDefault();
    if(busy||!appointmentRef.current)return;
    setBusy(true);setError('');
    try{
      const receipt=await appointmentRef.current.book({
        doctorMembershipId:appointmentDoctor.trim(),
        visitDate:appointmentDate,slotStart:appointmentSlot,
      });
      setAppointmentReceipt(receipt);
      setScreen('APPOINTMENT_BOOKED');
    }catch(failure){setAppointmentReceipt(null);setError(explain(failure));}
    finally{setBusy(false);}
  }

  async function reopenAppointment(e:FormEvent<HTMLFormElement>){
    e.preventDefault();
    if(busy||!appointmentRef.current)return;
    setBusy(true);setError('');setAppointmentReceipt(null);
    try{
      const receipt=await appointmentRef.current.reopen(appointmentLookupId.trim());
      setAppointmentReceipt(receipt);
      setScreen('APPOINTMENT_BOOKED');
    }catch(failure){setError(explain(failure));}
    finally{setBusy(false);}
  }

  async function checkInAppointment(){
    if(busy||!appointmentRef.current)return;
    setBusy(true);setError('');
    try{
      const receipt=await appointmentRef.current.checkIn();
      setAppointmentReceipt(receipt);
    }catch(failure){
      setAppointmentReceipt(null);
      setScreen('APPOINTMENT');
      setError(explain(failure));
    }finally{setBusy(false);}
  }

  function showQueue(){
    if(busy||!canReadAppointments)return;
    appointmentRef.current?.dispose();
    appointmentRef.current=null;
    setAppointmentReceipt(null);
    setAppointmentContext(null);
    setQueueRows([]);
    setQueueLoaded(false);
    setQueueDate(localCalendarDate());
    setEditAppointment(false);
    setCancelConfirm(false);
    setError('');
    setScreen('APPOINTMENT_QUEUE');
  }

  async function loadQueue(e:FormEvent<HTMLFormElement>){
    e.preventDefault();
    if(busy||!sessionRef.current||!canReadAppointments)return;
    setBusy(true);setError('');setQueueRows([]);setQueueLoaded(false);
    try{
      const rows=await loadAppointmentCandidateQueue(
        sessionRef.current,branchId,queueDate);
      setQueueRows(rows);
      setQueueLoaded(true);
    }catch(failure){setQueueRows([]);setError(explain(failure));}
    finally{setBusy(false);}
  }

  /**
   * The queue supplies a registration ID for navigation only. A separate
   * authenticated patient-role SQL read must confirm it still belongs to
   * this staff session/branch and maps to the same patient. Nothing is
   * cached in browser storage and no UUID is manually copied by staff.
   */
  async function openRegistrationFromQueue(item:AppointmentCandidateReceipt){
    if(busy||!sessionRef.current||!recoveryRef.current||!canRead)return;
    setBusy(true);setError('');setIntakeStatus('');setFoundReference(null);
    try{
      const session=sessionRef.current;
      if(item.branchId!==branchId||item.tenantId!==session.tenantId)
        throw new Error('Queue context changed');
      const fresh=await recoveryRef.current.recover(item.registrationId);
      const linked=verifiedRegistrationFromQueue(
        item,fresh,session.tenantId,branchId);
      if(sessionRef.current!==session)
        throw new Error('Session changed');
      appointmentRef.current?.dispose();
      appointmentRef.current=null;
      setAppointmentReceipt(null);
      setAppointmentContext(null);
      setFoundReference(linked);
      setLookupId('');
      setScreen('REFERENCE_FOUND');
    }catch{
      setFoundReference(null);
      setQueueRows([]);
      setQueueLoaded(false);
      setScreen('APPOINTMENT_QUEUE');
      setError('Registration could not be reverified. Refresh the queue and try again.');
    }finally{setBusy(false);}
  }

  async function openQueuedAppointment(item:AppointmentCandidateReceipt){
    if(busy||!sessionRef.current||!canBook)return;
    setBusy(true);setError('');setAppointmentReceipt(null);
    try{
      appointmentRef.current?.dispose();
      const next=createAppointmentCandidateFlow(sessionRef.current,{
        tenantId:item.tenantId,branchId:item.branchId,
        patientId:item.patientId,registrationId:item.registrationId,
      });
      appointmentRef.current=next;
      const fresh=await next.reopen(item.appointmentId);
      if(fresh.patientId!==item.patientId
         ||fresh.registrationId!==item.registrationId)
        throw new Error('Appointment record changed');
      setAppointmentContext({tenantId:fresh.tenantId,branchId:fresh.branchId,
        patientId:fresh.patientId,registrationId:fresh.registrationId});
      setAppointmentReceipt(fresh);
      setEditAppointment(false);
      setCancelConfirm(false);
      setScreen('APPOINTMENT_BOOKED');
    }catch(failure){
      appointmentRef.current?.dispose();
      appointmentRef.current=null;
      setAppointmentReceipt(null);
      setQueueRows([]);
      setQueueLoaded(false);
      setScreen('APPOINTMENT_QUEUE');
      setError(explain(failure));
    }finally{setBusy(false);}
  }

  function prepareReschedule(){
    if(busy||!appointmentReceipt||appointmentReceipt.stage!=='BOOKED')return;
    setNewDoctor(appointmentReceipt.doctorMembershipId);
    setNewDate(appointmentReceipt.visitDate);
    setNewSlot(appointmentReceipt.slotStart.slice(0,5));
    setCancelConfirm(false);
    setEditAppointment(true);
    setError('');
  }

  async function submitReschedule(e:FormEvent<HTMLFormElement>){
    e.preventDefault();
    if(busy||!appointmentRef.current)return;
    setBusy(true);setError('');
    try{
      const receipt=await appointmentRef.current.reschedule({
        doctorMembershipId:newDoctor.trim(),visitDate:newDate,slotStart:newSlot,
      });
      setAppointmentReceipt(receipt);
      setEditAppointment(false);
      setCancelConfirm(false);
    }catch(failure){
      setAppointmentReceipt(null);
      setScreen('APPOINTMENT_QUEUE');
      setQueueRows([]);
      setQueueLoaded(false);
      setError(explain(failure));
    }finally{setBusy(false);}
  }

  async function confirmCancelAppointment(){
    if(busy||!appointmentRef.current||!cancelConfirm)return;
    setBusy(true);setError('');
    try{
      const receipt=await appointmentRef.current.cancel();
      setAppointmentReceipt(receipt);
      setCancelConfirm(false);
      setEditAppointment(false);
    }catch(failure){
      setAppointmentReceipt(null);
      setScreen('APPOINTMENT_QUEUE');
      setQueueRows([]);
      setQueueLoaded(false);
      setError(explain(failure));
    }finally{setBusy(false);}
  }

  async function transferToDoctor(){
    if(busy||!onDoctorHandoff||!appointmentReceipt||!sessionRef.current)return;
    setBusy(true);setError('');
    try{
      const handoff=prepareDoctorHandoff(appointmentReceipt,sessionRef.current);
      // Confirm server-side revocation BEFORE switching staff role.
      await revokeStaffTokenAndConfirm(sessionRef.current.accessToken);
      clearSessionAndUi(false);
      onDoctorHandoff(handoff);
    }catch{
      // Do not transition on an unknown logout outcome; discard old session.
      clearSessionAndUi(true);
      setError('Handoff was not verified. Sign in again and reload the appointment.');
    }finally{setBusy(false);}
  }

  function clearSessionAndUi(revoke:boolean){
    appointmentRef.current?.dispose();
    appointmentRef.current=null;
    recoveryRef.current?.dispose();
    recoveryRef.current=null;
    const token=sessionRef.current?.accessToken;
    sessionRef.current=null;
    flowRef.current=null;
    setStaffLabel('');
    setCredentials(emptyCredentials());
    setForm(blankIntake());
    setReviewed(null);
    setSaved(null);
    setReadBack('');
    setIntakeStatus('');
    setFoundReference(null);
    setLookupId('');
    setCanRead(false);
    setCanBook(false);
    setCanReadAppointments(false);
    setQueueRows([]);
    setQueueLoaded(false);
    setQueueDate(localCalendarDate());
    setEditAppointment(false);
    setCancelConfirm(false);
    setAppointmentContext(null);
    setAppointmentReceipt(null);
    setAppointmentDoctor('');
    setAppointmentLookupId('');
    setError('');
    setScreen('SIGN_IN');
    // Clear all UI context before requesting server-side revocation.
    if(token&&revoke)void revokeStaffToken(token);
  }

  function signOut(){
    if(busy)return;
    clearSessionAndUi(true);
  }

  const queueSummary=queueLoaded?summarizeReceptionQueue(queueRows):null;
  const active=screen!=='SIGN_IN';
  return <div className="connected-shell">
    <header className="connected-header">
      <div className="connected-brand"><span className="brand-symbol">✚</span>
        <span>AroNexa <small>Reception integration test</small></span></div>
      <div className="connected-actions">
          {active&&canReadAppointments&&<button className="outline" disabled={busy}
            onClick={showQueue}>Daily appointments</button>}
          {active&&<button className="outline" disabled={busy} onClick={signOut}>
            Sign out{staffLabel?' · '+staffLabel:''}</button>}
        </div>
    </header>
    <div className="connected-warning" role="status">
      <strong>TEST ONLY — NOT FOR REAL PATIENTS.</strong>
      {' '}Use fictional names and disposable PostgreSQL test environments only.
      The actual commercial registration POST is disabled. No browser storage is used.
    </div>
    <main className="connected-main">
      <div className="connected-heading">
        <p className="eyebrow">SECURE REGISTRATION INTEGRATION</p>
        <h1>{screen==='SIGN_IN'?'Staff sign-in':screen==='REGISTERED'?'Registration acknowledged':
          screen==='REFERENCE_FOUND'?'Existing registration found':
          screen==='RECOVERY_LOOKUP'?'Find an existing registration':
          screen==='APPOINTMENT'?'Book or reopen a saved appointment':
          screen==='APPOINTMENT_BOOKED'?'Saved appointment':
          screen==='APPOINTMENT_QUEUE'?'Daily appointment queue':
          screen==='REVIEW'?'Review proposed registration':
          screen==='UNRESOLVED'?'Save needs attention':'Register synthetic patient'}</h1>
        <p>One staff session · One patient identity · One controlled registration attempt.</p>
      </div>

      {error&&<div className="connected-error" role="alert">{error}</div>}

      {screen==='SIGN_IN'&&
        <section className="connected-panel" aria-label="Staff sign-in">
          <h2>Connect to a disposable clinic test backend</h2>
          <form onSubmit={signIn}>
            <label htmlFor="tenant">Clinic code</label>
            <input id="tenant" required maxLength={48} autoComplete="off"
              value={credentials.tenantCode} onChange={e=>setCredentials(c=>({...c,tenantCode:e.target.value}))}/>
            <label htmlFor="username">Staff username</label>
            <input id="username" required autoComplete="username"
              value={credentials.username} onChange={e=>setCredentials(c=>({...c,username:e.target.value}))}/>
            <label htmlFor="password">Staff password</label>
            <input id="password" type="password" required autoComplete="current-password"
              value={credentials.password} onChange={e=>setCredentials(c=>({...c,password:e.target.value}))}/>
            <label htmlFor="branch">Assigned branch UUID</label>
            <input id="branch" required autoComplete="off" placeholder="UUID from synthetic test fixture"
              value={branchId} onChange={e=>setBranchId(e.target.value.trim())}/>
            <label className="connected-check">
              <input type="checkbox" checked={testOnly} onChange={e=>setTestOnly(e.target.checked)}/>
              I confirm this is a disposable test clinic using fictional records only.
            </label>
            <button className="solid" disabled={busy || !testOnly} type="submit">
              {busy?'Verifying session…':'Sign in and verify session'}
            </button>
          </form>
          <p className="connected-footnote">
            Server-issued roles and tenant membership are verified through /auth/me.
            The branch is rechecked by the database for every registration.
            No browser-based user administration or password recovery is provided.
          </p>
        </section>}

      {screen==='PATIENT_DETAILS'&&
        <section className="connected-panel" aria-label="Patient details">
          <h2>Minimal registration details</h2>
          {canRead&&<div className="connected-actions">
            <button type="button" className="outline" onClick={showRecovery}>
              Find existing registration
            </button>
          </div>}
          <form onSubmit={review}>
            <label htmlFor="full-name">Fictional patient full name</label>
            <input id="full-name" required maxLength={100} autoComplete="off"
              placeholder="e.g. Fictional Clinic Patient"
              value={form.name} onChange={e=>update('name',e.target.value)}/>
            <label htmlFor="precision">Date of birth</label>
            <select id="precision" value={form.precision}
              onChange={e=>update('precision',e.target.value as Intake['precision'])}>
              <option value="UNKNOWN">Unknown</option>
              <option value="FULL">Exact date known</option>
            </select>
            {form.precision==='FULL'&&<>
              <label htmlFor="birth-date">Exact synthetic date of birth</label>
              <input id="birth-date" type="date" required max={localCalendarDate()}
                value={form.dob} onChange={e=>update('dob',e.target.value)}/>
            </>}
            <p className="connected-footnote">
              Contact numbers, uploaded files, diagnoses, notes and prescriptions
              are intentionally not supported by this registration contract.
            </p>
            <button className="solid" type="submit">Review registration</button>
          </form>
        </section>}

      {screen==='REVIEW'&&reviewed&&
        <section className="connected-panel" aria-label="Confirm registration">
          <h2>Confirm before creating a new patient identity</h2>
          <dl className="connected-summary">
            <dt>Patient name</dt><dd>{reviewed.displayName}</dd>
            <dt>Date of birth</dt><dd>{reviewed.dateOfBirth??'Unknown'}</dd>
            <dt>Authorized branch candidate</dt><dd className="mono">{reviewed.branchId}</dd>
          </dl>
          <p className="connected-footnote">
            The backend will check staff permission, module activation, branch
            access, possible duplicates and idempotency. There is no automatic merge.
          </p>
          <div className="connected-actions">
            <button className="solid" disabled={busy} onClick={()=>void submit()}>
              {busy?'Saving…':'Confirm and save to test PostgreSQL'}
            </button>
            <button className="outline" disabled={busy} onClick={editReview}>Edit details</button>
          </div>
        </section>}

      {screen==='UNRESOLVED'&&
        <section className="connected-panel" aria-label="Uncertain registration">
          <h2>Do not create a second registration</h2>
          <p>
            The server did not confirm a registration through this workflow.
            Do not re-enter this patient with a new key. Use the explicit retry
            below to check the same immutable submission.
          </p>
          <div className="connected-actions">
            <button className="solid" disabled={busy} onClick={()=>void submit()}>
              {busy?'Checking…':'Retry same registration'}
            </button>
          </div>
          <p className="connected-footnote">
            A conflict or authorization denial may require administrator review.
            The normal demo patient generator is never used as a fallback.
          </p>
        </section>}

      {screen==='REGISTERED'&&saved&&
        <section className="connected-panel" aria-label="Registration acknowledged">
          <div className="connected-success">✓ Server registration acknowledged</div>
          <p>These identifiers were issued by the authorized registration endpoint.
            They are not synthetic Reception demo IDs.</p>
          <dl className="connected-summary">
            <dt>MRN</dt><dd className="mono">{saved.mrn}</dd>
            <dt>Registration ID</dt><dd className="mono">{saved.registrationId}</dd>
            <dt>Patient ID</dt><dd className="mono">{saved.patientId}</dd>
            <dt>Journey stage</dt><dd>{saved.stage}</dd>
          </dl>
          {readBack&&<p role="status">{readBack}</p>}
          {intakeStatus&&<p role="status">{intakeStatus}</p>}
          <div className="connected-actions">
            <button className="solid" onClick={nextPatient}>Register next fictional patient</button>
            {canRead&&<button className="outline" onClick={showRecovery}>
              Find existing registration
            </button>}
            {canBook&&canRead&&<button className="outline" disabled={busy}
              onClick={()=>void verifyIntake({tenantId:saved.tenantId,
                branchId:saved.branchId,registrationId:saved.registrationId,
                patientId:saved.patientId})}>
              Verify OPD intake (read-only)
            </button>}
            {canBook&&<button className="outline" onClick={openSavedRegistrationBooking}>
              Book a test appointment
            </button>}
            <button className="outline" disabled={busy} onClick={()=>void verifySaved()}>
              Verify authorized read-back
            </button>
          </div>
          <p className="connected-footnote">
            Appointments, consultation, prescriptions and billing are NOT
            connected in this test screen. They require separately authorized,
            persisted server endpoints before use.
          </p>
        </section>}

      {screen==='RECOVERY_LOOKUP'&&
        <section className="connected-panel" aria-label="Find existing registration">
          <h2>Look up a server-saved registration</h2>
          <p>After signing in again, enter the registration ID issued earlier.
            The server verifies this staff session, tenant, role and branch before returning a patient reference.</p>
          <form onSubmit={lookupExisting}>
            <label htmlFor="existing-registration-id">Registration ID (UUID)</label>
            <input id="existing-registration-id" required autoComplete="off"
              placeholder="00000000-0000-0000-0000-000000000000"
              value={lookupId} onChange={e=>setLookupId(e.target.value)}
              aria-describedby="existing-registration-help"/>
            <p id="existing-registration-help" className="connected-footnote">
              Enter an authorized registration UUID from this branch. No names,
              DOB, contact numbers, MRNs or clinical notes are returned by this read endpoint.
            </p>
            <button className="solid" type="submit" disabled={busy}>
              {busy?'Checking server…':'Find registration'}
            </button>
          </form>
          {flowRef.current&&<div className="connected-actions">
            <button className="outline" disabled={busy} onClick={returnToRegistration}>
              Return to new registration
            </button>
          </div>}
        </section>}

      {screen==='REFERENCE_FOUND'&&foundReference&&
        <section className="connected-panel" aria-label="Verified existing registration">
          <div className="connected-success">✓ Registration verified by the server</div>
          <p>The IDs below were read from PostgreSQL using the current staff authorization.
            This is not a new patient registration or permission to start a consultation.</p>
          <dl className="connected-summary">
            <dt>Registration ID</dt><dd className="mono">{foundReference.registrationId}</dd>
            <dt>Patient ID</dt><dd className="mono">{foundReference.patientId}</dd>
            <dt>Branch ID</dt><dd className="mono">{foundReference.branchId}</dd>
          </dl>
          {intakeStatus&&<p role="status">{intakeStatus}</p>}
          <div className="connected-actions">
            {canBook&&canRead&&<button className="outline" disabled={busy}
              onClick={()=>void verifyIntake(foundReference)}>
              Verify OPD intake (read-only)
            </button>}
            <button className="solid" disabled={busy} onClick={showRecovery}>
              Find another registration
            </button>
            {canBook&&<button className="solid" disabled={busy}
              onClick={()=>openBooking(foundReference)}>Book test appointment</button>}
            {flowRef.current&&<button className="outline" disabled={busy}
              onClick={returnToRegistration}>New registration</button>}
          </div>
          <p className="connected-footnote">
            Appointments require a separate authorized persistent backend. This
            reference cannot create an appointment or authorize clinical care.
          </p>
        </section>}
      {screen==='APPOINTMENT_QUEUE'&&
        <section className="connected-panel" aria-label="Daily appointment queue">
          <h2>Branch appointments</h2>
          <p className="connected-footnote">
            Each refresh reads active appointments from the authorized
            server and records an access audit. No patient names or contact
            details are included. Cancelled visits remain in history by UUID.
          </p>
          <form onSubmit={loadQueue}>
            <label htmlFor="queue-day">Clinic visit date</label>
            <input id="queue-day" type="date" required disabled={busy}
              value={queueDate} onChange={e=>{
                setQueueDate(e.target.value);
                setQueueRows([]);
                setQueueLoaded(false);
              }}/>
            <button className="solid" type="submit" disabled={busy}>
              {busy?'Loading daily queue…':'Load daily queue'}
            </button>
          </form>
          {queueSummary&&<div className="connected-queue-totals" role="status" aria-label="Verified daily queue totals">
            <strong>Total: {queueSummary.total}</strong>
            <span>Waiting: {queueSummary.booked}</span>
            <span>Checked in: {queueSummary.checkedIn}</span>
          </div>}
          {queueLoaded&&queueRows.length===0&&
            <p role="status">No active appointments for this date.</p>}
          {queueLoaded&&queueRows.length>0&&
            <ul aria-label="Appointments for selected day">
              {queueRows.map(item=>
                <li key={item.appointmentId} className="connected-queue-item">
                  <p><strong>{item.slotStart.slice(0,5)} — {item.stage}</strong></p>
                  <p className="mono">Appointment {item.appointmentId}</p>
                  <p className="connected-footnote">
                    Doctor membership: {item.doctorMembershipId}
                  </p>
                  {canRead&&<button className="outline" disabled={busy}
                    onClick={()=>void openRegistrationFromQueue(item)}>
                    Open verified registration
                  </button>}
                  {canBook&&<button className="outline" disabled={busy}
                    onClick={()=>void openQueuedAppointment(item)}>
                    Manage appointment
                  </button>}
                </li>)}
            </ul>}
        </section>}

      {screen==='APPOINTMENT'&&appointmentContext&&
        <section className="connected-panel" aria-label="Persistent appointment candidate">
          <h2>Book an appointment in disposable PostgreSQL</h2>
          <p className="connected-footnote">
            Patient and registration references were issued by the server.
            Only fictional appointments are permitted. An assigned doctor membership UUID
            is required until the reviewed clinician-directory API is implemented.
          </p>
          <dl className="connected-summary">
            <dt>Patient ID</dt><dd className="mono">{appointmentContext.patientId}</dd>
            <dt>Registration ID</dt><dd className="mono">{appointmentContext.registrationId}</dd>
          </dl>
          <form onSubmit={submitAppointment}>
            <label htmlFor="appointment-doctor">Assigned doctor membership UUID</label>
            <input id="appointment-doctor" required autoComplete="off"
              placeholder="Doctor membership UUID from disposable fixture"
              value={appointmentDoctor}
              onChange={e=>setAppointmentDoctor(e.target.value.trim())}/>
            <label htmlFor="appointment-date">Visit date</label>
            <input id="appointment-date" required type="date" min={localCalendarDate()}
              value={appointmentDate} onChange={e=>setAppointmentDate(e.target.value)}/>
            <label htmlFor="appointment-slot">Visit slot</label>
            <select id="appointment-slot" value={appointmentSlot}
              onChange={e=>setAppointmentSlot(e.target.value)}>
              {Array.from({length:32},(_,i)=>(
                <option key={i} value={String(9+Math.floor(i/4)).padStart(2,'0')+':'+String(i%4*15).padStart(2,'0')}>
                  {String(9+Math.floor(i/4)).padStart(2,'0')+':'+String(i%4*15).padStart(2,'0')}
                </option>))}
            </select>
            <button className="solid" type="submit" disabled={busy}>
              {busy?'Saving appointment…':'Book test appointment'}
            </button>
          </form>
          <p className="connected-footnote">
            If the network fails after booking, retry the unchanged details.
            The same request ID will be reused; changing patient/slot requires explicit review.
          </p>
          <form onSubmit={reopenAppointment}>
            <label htmlFor="existing-appointment">Reopen appointment UUID</label>
            <input id="existing-appointment" required autoComplete="off"
              value={appointmentLookupId}
              onChange={e=>setAppointmentLookupId(e.target.value.trim())}/>
            <button className="outline" disabled={busy} type="submit">Reopen server appointment</button>
          </form>
        </section>}

      {screen==='APPOINTMENT_BOOKED'&&appointmentReceipt&&
        <section className="connected-panel" aria-label="Saved appointment">
          <div className="connected-success">✓ Appointment verified by the server</div>
          <dl className="connected-summary">
            <dt>Appointment ID</dt><dd className="mono">{appointmentReceipt.appointmentId}</dd>
            <dt>Patient ID</dt><dd className="mono">{appointmentReceipt.patientId}</dd>
            <dt>Doctor membership ID</dt><dd className="mono">{appointmentReceipt.doctorMembershipId}</dd>
            <dt>Date and slot</dt><dd>{appointmentReceipt.visitDate} · {appointmentReceipt.slotStart}</dd>
            <dt>Status</dt><dd>{appointmentReceipt.stage}</dd>
            <dt>Revision</dt><dd>{appointmentReceipt.revision}</dd>
          </dl>
          <div className="connected-actions">
            {appointmentReceipt.stage==='BOOKED'&&
              <button className="solid" disabled={busy} onClick={()=>void checkInAppointment()}>
                {busy?'Confirming check-in…':'Check in this appointment'}
              </button>}
            {appointmentReceipt.stage==='BOOKED'&&
              sessionRef.current?.permissions.includes('APPOINTMENT_RESCHEDULE')&&
              <button className="outline" disabled={busy} onClick={prepareReschedule}>
                Reschedule appointment
              </button>}
            {appointmentReceipt.stage==='BOOKED'&&
              sessionRef.current?.permissions.includes('APPOINTMENT_CANCEL')&&
              <button className="outline" disabled={busy}
                onClick={()=>{setEditAppointment(false);setCancelConfirm(true);}}>
                Cancel appointment
              </button>}
            {onDoctorHandoff&&appointmentReceipt.stage==='CHECKED_IN'&&
              <button className="solid" disabled={busy} onClick={transferToDoctor}>
                Hand off to assigned doctor sign-in
              </button>}
            {canReadAppointments&&
              <button className="outline" disabled={busy} onClick={showQueue}>
                Return to daily queue
              </button>}
          </div>
          {editAppointment&&appointmentReceipt.stage==='BOOKED'&&
            <form onSubmit={submitReschedule} aria-label="Change appointment slot">
              <h2>Choose another doctor or slot</h2>
              <label htmlFor="reschedule-doctor">Doctor membership UUID</label>
              <input id="reschedule-doctor" required value={newDoctor}
                onChange={e=>setNewDoctor(e.target.value.trim())}/>
              <label htmlFor="reschedule-date">New visit date</label>
              <input id="reschedule-date" required type="date" min={localCalendarDate()}
                value={newDate} onChange={e=>setNewDate(e.target.value)}/>
              <label htmlFor="reschedule-slot">New visit slot</label>
              <select id="reschedule-slot" value={newSlot}
                onChange={e=>setNewSlot(e.target.value)}>
                {Array.from({length:32},(_,i)=>{
                  const time=String(9+Math.floor(i/4)).padStart(2,'0')+':'+
                    String(i%4*15).padStart(2,'0');
                  return <option key={time} value={time}>{time}</option>;
                })}
              </select>
              <button className="solid" disabled={busy} type="submit">
                Confirm reschedule
              </button>
              <button className="outline" disabled={busy} type="button"
                onClick={()=>setEditAppointment(false)}>Keep existing appointment</button>
            </form>}
          {cancelConfirm&&appointmentReceipt.stage==='BOOKED'&&
            <section aria-label="Confirm appointment cancellation">
              <p role="status">
                Cancellation releases the doctor slot. The audit history will
                remain and this appointment cannot be checked in.
              </p>
              <button className="solid" disabled={busy}
                onClick={()=>void confirmCancelAppointment()}>
                Confirm cancellation
              </button>
              <button className="outline" disabled={busy}
                onClick={()=>setCancelConfirm(false)}>
                Keep appointment
              </button>
            </section>}
          {appointmentReceipt.stage==='CANCELLED'&&
            <p role="status">Cancelled. This slot is now released;
              the appointment remains available in the audit history.</p>}
          <p className="connected-footnote">
            Check-in is allowed only on the scheduled clinic-local date.
            Consultation, prescription and billing remain unavailable in this candidate.
          </p>
        </section>}

    </main>
    <footer className="connected-footer">
      Internal QA preview only · No patient care · No browser persistence · ARG Digital Technologies
    </footer>
  </div>;
}
