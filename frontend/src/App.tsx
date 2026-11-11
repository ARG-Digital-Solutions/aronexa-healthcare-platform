import {useEffect, useMemo, useRef, useState, type FormEvent} from "react";
import {localCalendarDate,normalizeIntake,blankIntake,candidateMatches,demoRegistration,maskPhone,seedPatients,validateIntake,DEMO_BRANCH,type DemoPatient,type Intake} from "./receptionModel";
import {act,book,doctors,slots,filterAppointments,type Appointment,type Booking,type Result} from "./appointmentModel";
type Page="dashboard"|"register"|"patients"|"appointments";
export default function App(){
 const [appointments,setAppointments]=useState<Appointment[]>([]);
 const [page,setPage]=useState<Page>("dashboard");
 const [patients,setPatients]=useState<DemoPatient[]>(()=>[...seedPatients]);
 const [form,setForm]=useState<Intake>(blankIntake);
 const [errors,setErrors]=useState<Record<string,string>>({});
 const [review,setReview]=useState(false);
 const [created,setCreated]=useState<DemoPatient|null>(null);
 const [query,setQuery]=useState("");
 const dialogRef=useRef<HTMLDialogElement>(null);
 const reviewTitleRef=useRef<HTMLHeadingElement>(null);
 const reviewButtonRef=useRef<HTMLButtonElement>(null);
 const successTitleRef=useRef<HTMLHeadingElement>(null);
 const confirming=useRef(false);
 const invalidFocus=useRef<string|null>(null);
 const [announcement,setAnnouncement]=useState({count:0,text:""});
 const proposed=normalizeIntake(form);
 useEffect(()=>{
   const dialog=dialogRef.current;
   if(!review || !dialog)return;
   confirming.current=false;
   const opener=reviewButtonRef.current;
   dialog.showModal();
   reviewTitleRef.current?.focus();
   return ()=>{
     if(dialog.open)dialog.close();
     if(opener instanceof HTMLElement && opener.isConnected)opener.focus();
     if(invalidFocus.current){document.getElementById(invalidFocus.current)?.focus();invalidFocus.current=null;}
   };
 },[review]);
 useEffect(()=>{if(created)successTitleRef.current?.focus();},[created]);
 function showErrors(errs:Record<string,string>){
   setErrors(errs);
   const keys=Object.keys(errs);
   if(keys.length){
     setAnnouncement(old=>({count:old.count+1,text:`${keys.length} errors. ${Object.values(errs).join(" ")}`}));
     const id=keys[0]==="name"?"fullName":keys[0];
     document.getElementById(id)?.focus();
   }
 }
 const matches=useMemo(()=>candidateMatches(form,patients),[form,patients]);
 const filtered=useMemo(()=>patients.filter(p=>[p.name,p.id].some(x=>x.toLowerCase().includes(query.toLowerCase().trim()))),[patients,query]);
 const waiting=patients.filter(p=>p.status==="Waiting").length;
 function change<K extends keyof Intake>(key:K,value:Intake[K]){
   setForm(old=>({...old,[key]:value,...(key==="precision"&&value==="UNKNOWN"?{dob:""}:{})}));
   setErrors(old=>{
     const next={...old};
     const updated={...form,[key]:value,...(key==="precision"&&value==="UNKNOWN"?{dob:""}:{})};
     const field=key==="precision"?"dob":key;
     const message=validateIntake(updated)[field];
     if(next[field] && message)next[field]=message;else delete next[field];
     return next;
   });setReview(false);
 }
 function begin(){setForm(blankIntake());setErrors({});setReview(false);setCreated(null);setPage("register");}
 function submit(e:FormEvent){e.preventDefault();setCreated(null);const errs=validateIntake(form);showErrors(errs);if(Object.keys(errs).length)return;setReview(true);}
 function confirm(){
   if(!review || confirming.current)return;
   const errs=validateIntake(form);
   if(Object.keys(errs).length){invalidFocus.current=Object.keys(errs)[0]==="name"?"fullName":Object.keys(errs)[0];setReview(false);showErrors(errs);return;}
   confirming.current=true;
   const next=demoRegistration(form,Math.max(...patients.map(p=>Number(p.id.split("-")[1])||0))+1,new Date().toLocaleTimeString("en-IN",{hour:"2-digit",minute:"2-digit",hour12:false}));
   setPatients(old=>[next,...old]);setCreated(next);setReview(false);setForm(blankIntake());setPage("register");
 }
 function nav(target:Page){setPage(target);setReview(false);setCreated(null);}
 return <div className="app">
  <aside className="sidebar"><div className="logo"><span className="logo-mark">✚</span><span>AroNexa<small>Healthcare Platform</small></span></div><div className="menu-label">WORKSPACE</div>
   <nav aria-label="Reception navigation">
    <button aria-current={page==="dashboard"?"page":undefined} className={page==="dashboard"?"active":""} onClick={()=>nav("dashboard")}>▦ <span>Overview</span></button>
    <button aria-current={page==="register"?"page":undefined} className={page==="register"?"active":""} onClick={begin}>⊕ <span>Register patient</span></button>
    <button aria-current={page==="patients"?"page":undefined} className={page==="patients"?"active":""} onClick={()=>nav("patients")}>♙ <span>Patient list</span></button>
    <button aria-current={page==="appointments"?"page":undefined} className={page==="appointments"?"active":""} onClick={()=>nav("appointments")}>? <span>Appointments / check-in</span></button>
   </nav>
   <div className="sidebar-bottom"><span className="tiny-dot"/> Receptionist workspace <small>Prototype • No live connection</small></div>
  </aside>
  <div className="main">
   <header className="topbar"><div><span className="location">● {DEMO_BRANCH}</span><span className="separator">/</span><span className="location">Reception</span></div><div className="avatar"><span className="avatar-dot">R</span> Demo Receptionist</div></header>
   <div className="banner" role="status"><strong>DEMO MODE</strong> Synthetic records only. Data stays in temporary browser memory and resets on refresh. Do not enter real patient information.</div>
   <main className="content"><div className="sr-only" aria-live="assertive" aria-atomic="true">{announcement.count>0&&`Attempt ${announcement.count}: ${announcement.text}`}</div>
    {page==="dashboard"&&<><div className="page-head"><div><p className="kicker">RECEPTION DESK</p><h1>Good morning, Reception</h1><p className="subtext">A simple view of patient arrivals and today's front-desk activity.</p></div><button className="primary" onClick={begin}>+ Register new patient</button></div>
     <div className="stats"><article><p>Registered in demo</p><b>{patients.length}</b><span>Sample records in this session</span></article><article><p>Waiting</p><b>{waiting}</b><span>Awaiting consultation</span></article><article><p>In consultation</p><b>{patients.filter(p=>p.status==="In consultation").length}</b><span>Currently with clinician</span></article><article><p>New registrations</p><b>{Math.max(0,patients.length-seedPatients.length)}</b><span>Created this session</span></article></div>
     <div className="split"><section className="surface"><div className="surface-title"><div><h2>Reception queue</h2><p>Current sample patient arrivals</p></div><button className="link-button" onClick={()=>nav("patients")}>View all</button></div><PatientTable patients={patients.slice(0,6)}/></section>
     <section className="surface quick"><h2>Quick actions</h2><p>Common tasks for your reception team</p><button onClick={begin}><span className="action-icon">+</span><span><b>New patient registration</b><small>Capture patient details</small></span><span>›</span></button><button onClick={()=>nav("patients")}><span className="action-icon muted">⌕</span><span><b>Find a patient</b><small>Search synthetic records</small></span><span>›</span></button><div className="next-note"><strong>Coming in later increments</strong><p>Billing and clinical records are not implemented here. Synthetic appointments and check-in are available in navigation.</p></div></section></div></>}
    {page==="patients"&&<><div className="page-head"><div><p className="kicker">PATIENT DIRECTORY</p><h1>Patient list</h1><p className="subtext">Browse synthetic records created in this session.</p></div><button className="primary" onClick={begin}>+ Register new patient</button></div><section className="surface"><div className="searchbar"><label htmlFor="search">Search sample patients</label><input id="search" placeholder="Search by name or demo ID" value={query} onChange={e=>setQuery(e.target.value)}/></div><PatientTable patients={filtered}/></section></>}
    {page==="register"&&<><div className="page-head"><div><p className="kicker">PATIENT ONBOARDING / STEP 1 OF 1</p><h1>New patient registration</h1><p className="subtext">Record essential details, review potential matches, and confirm.</p></div><button className="secondary" onClick={()=>nav("dashboard")}>Back to reception</button></div>
     {created?<section className="surface confirmation" aria-live="polite"><div className="success-mark">✓</div><p className="kicker">REGISTRATION COMPLETE — DEMO ONLY</p><h2 ref={successTitleRef} tabIndex={-1}>{created.name} added to reception</h2><p>This record exists only in temporary browser memory. It has not been saved to PostgreSQL or a clinical system.</p><div className="receipt"><div><small>Demo registration ID / not an actual MRN</small><strong>{created.id}</strong></div><div><small>Branch</small><strong>{created.branch}</strong></div></div><div className="button-line"><button className="primary" onClick={begin}>Register another patient</button><button className="secondary" onClick={()=>nav("patients")}>View patient list</button></div></section>:
     <div className="form-grid"><section className="surface"><div className="surface-title"><div><h2>Patient details</h2><p>Only minimal fields are requested for this demo.</p></div><span className="step-badge">01 / 01</span></div>
      <form onSubmit={submit} noValidate><div className="field"><label htmlFor="fullName">Patient full name <em>*</em></label><input id="fullName" required aria-required="true" autoComplete="off" placeholder="e.g. Synthetic Patient" value={form.name} maxLength={100} aria-invalid={!!errors.name} aria-describedby={errors.name?"nameError":undefined} onChange={e=>change("name",e.target.value)}/>{errors.name&&<span className="error" id="nameError">{errors.name}</span>}</div>
       <div className="fields-two"><div className="field"><label htmlFor="dobPrecision">Date of birth</label><select id="dobPrecision" value={form.precision} onChange={e=>change("precision",e.target.value as Intake["precision"])}><option value="UNKNOWN">Unknown</option><option value="FULL">Exact date known</option></select></div><div className="field"><label htmlFor="dob">Date <span className="optional">(when known)</span></label><input id="dob" type="date" disabled={form.precision==="UNKNOWN"} value={form.dob} max={localCalendarDate()} required={form.precision==="FULL"} aria-required={form.precision==="FULL"} aria-describedby={errors.dob?"dobError":undefined} aria-invalid={!!errors.dob} onChange={e=>change("dob",e.target.value)}/>{errors.dob&&<span className="error" id="dobError">{errors.dob}</span>}</div></div>
       <div className="field"><label htmlFor="phone">Mobile number <span className="optional">(optional, demo only)</span></label><input id="phone" type="tel" inputMode="numeric" placeholder="10 digit synthetic number" value={form.phone} maxLength={10} autoComplete="off" aria-describedby={errors.phone?"phoneError phoneHint":"phoneHint"} aria-invalid={!!errors.phone} onChange={e=>change("phone",e.target.value)}/>{errors.phone&&<span className="error" id="phoneError">{errors.phone}</span>}<span className="hint" id="phoneHint">Mobile numbers may be shared by family members; they are not unique identifiers.</span></div>
       <div className="notice"><strong>Privacy by design</strong><p>Do not enter real names, numbers or health details. Verification, consent and identity linking are future protected workflows.</p></div>
       <div className="button-line"><button ref={reviewButtonRef} type="submit" className="primary">Review &amp; continue</button><button type="button" className="secondary" onClick={()=>nav("dashboard")}>Cancel</button></div>
      </form></section>
      <aside className="surface side-help"><h2>Simple workflow</h2><ol><li><b>Capture details</b><span>Enter the patient's essential information.</span></li><li><b>Check suggestions</b><span>Review matching demo records; never auto-merge.</span></li><li><b>Confirm</b><span>Receive a temporary demo registration ID.</span></li></ol><div className="next-note"><b>Scope reminder</b><p>This demo has no backend write, actual MRN, login or real appointment booking.</p></div></aside></div>}
     <dialog ref={dialogRef} className="modal" aria-labelledby="reviewTitle" onCancel={e=>{e.preventDefault();setReview(false);}}><p className="kicker">IDENTITY REVIEW · SYNTHETIC ONLY</p><h2 id="reviewTitle" ref={reviewTitleRef} tabIndex={-1}>Review before registration</h2><section className="proposed-summary" aria-labelledby="proposedTitle"><h3 id="proposedTitle">Proposed new demo registration</h3><dl><dt>Patient name</dt><dd>{proposed.name}</dd><dt>Date of birth</dt><dd>{proposed.precision==="FULL"?proposed.dob:"Unknown"}</dd><dt>Mobile number</dt><dd>{maskPhone(proposed.phone)}</dd><dt>Branch</dt><dd>{DEMO_BRANCH}</dd></dl></section><h3>Possible existing demo records</h3><p>{matches.length? "Possible same-name patient records were found. Review carefully; no records will be merged.": "No exact-name demo match was found. This does not prove the patient is unique."}</p>{matches.length>0&&<div className="match-list">{matches.map(p=><div className="match" key={p.id}><strong>{p.name}</strong><span>{p.id} · {p.dob||"DOB not known"} · {maskPhone(p.phone)}</span></div>)}</div>}<div className="notice">Creating a new demo identity must be an explicit receptionist decision. Do not use actual patient information.</div><div className="button-line"><button className="primary" onClick={confirm}>Confirm new demo registration</button><button className="secondary" onClick={()=>setReview(false)}>Review / edit details</button></div></dialog>
    </>}
    {page==="appointments"&&<Appointments patients={patients} appointments={appointments} setAppointments={setAppointments}/>}
   </main>
   <footer>ARG Digital Technologies · AroNexa • Internal synthetic workflow preview • Not for patient care</footer>
  </div>
 </div>;
}
function PatientTable({patients}:{patients:DemoPatient[]}){return <div className="table-scroll" role="region" aria-label="Synthetic patient table, scroll horizontally for more columns" tabIndex={0}><table><thead><tr><th>Patient</th><th>Demo ID</th><th>Arrival</th><th>Status</th></tr></thead><tbody>{patients.map(p=><tr key={p.id}><td><div className="patient-name"><span className="patient-avatar">{p.name[0]}</span>{p.name}</div></td><td>{p.id}</td><td>{p.time}</td><td><span className={"status "+p.status.toLowerCase().replace(/ /g,"-")}>{p.status}</span></td></tr>)}{!patients.length&&<tr><td colSpan={4}>No synthetic patient matches your search.</td></tr>}</tbody></table></div>}

function Appointments({patients,appointments,setAppointments}:{patients:DemoPatient[];appointments:Appointment[];setAppointments:(a:Appointment[])=>void}){
 const today=localCalendarDate();
 const [draft,setDraft]=useState<Booking>({patientId:"",doctorId:"",branch:DEMO_BRANCH,date:today,slot:""});
 const [editing,setEditing]=useState("");
 const [feedback,setFeedback]=useState({count:0,text:"",error:false});
 const [filters,setFilters]=useState({date:"",doctorId:"",status:"",query:""});
 const titleRef=useRef<HTMLHeadingElement>(null);
 const feedbackRef=useRef<HTMLDivElement>(null);
 useEffect(()=>{titleRef.current?.focus();},[]);
 function finish(result:Result){
  if(result.ok){setAppointments(result.appointments);setEditing("");}
  setFeedback(old=>({count:old.count+1,text:result.ok?`${result.id}: ${result.appointments.find(a=>a.id===result.id)?.history.at(-1)}. DEMO only; not an MRN.`:result.error,error:!result.ok}));
  feedbackRef.current?.focus();
 }
 function submit(e:FormEvent){e.preventDefault();finish(editing?act(appointments,editing,"reschedule",draft,patients,today):book(appointments,draft,patients,today));}
 function edit(a:Appointment){setEditing(a.id);setDraft({patientId:a.patientId,doctorId:a.doctorId,branch:a.branch,date:a.date,slot:a.slot});document.getElementById("apt-doctor")?.focus();}
 const shown=filterAppointments(appointments,filters,patients);
 const doctorName=(id:string)=>doctors.find(d=>d.id===id)?.name??id;
 return <><div className="page-head"><div><p className="kicker">SYNTHETIC RECEPTION</p><h1 tabIndex={-1} ref={titleRef}>Appointments and check-in</h1><p className="subtext">Simulated slots only. Single-session checks provide no backend concurrency guarantee. Refresh resets all data.</p></div></div>
 <div ref={feedbackRef} tabIndex={-1} role="status" aria-live="polite" aria-atomic="true" className={feedback.error?"notice appointment-error":"notice"}>{feedback.count?`Action ${feedback.count}: ${feedback.text}`:"Select a session patient to book. Reception check-in does not change clinical status."}</div>
 <section className="surface appointment-section" aria-labelledby="booking-title"><h2 id="booking-title">{editing?`Reschedule ${editing}`:"Book a DEMO appointment"}</h2><p className="hint">Branch: {DEMO_BRANCH}. Synthetic doctors; simulated 15-minute slots, 09:00?16:45. Check-in is available on the appointment date only.</p>
 <form onSubmit={submit} noValidate aria-describedby={feedback.error?"apt-error":undefined}>
 {feedback.error&&<p id="apt-error" className="error">{feedback.text}</p>}
 <div className="appointment-fields"><div className="field"><label htmlFor="apt-patient">Demo patient (required)</label><select id="apt-patient" required disabled={!!editing} value={draft.patientId} onChange={e=>setDraft({...draft,patientId:e.target.value})}><option value="">Select session patient</option>{patients.map(p=><option key={p.id} value={p.id}>{p.name} ? {p.id}</option>)}</select></div>
 <div className="field"><label htmlFor="apt-doctor">Synthetic doctor (required)</label><select id="apt-doctor" required value={draft.doctorId} onChange={e=>setDraft({...draft,doctorId:e.target.value})}><option value="">Select synthetic doctor</option>{doctors.map(d=><option key={d.id} value={d.id}>{d.name}</option>)}</select></div>
 <div className="field"><label htmlFor="apt-date">Local-calendar date (required)</label><input id="apt-date" type="date" required min={today} value={draft.date} onChange={e=>setDraft({...draft,date:e.target.value})}/></div>
 <div className="field"><label htmlFor="apt-slot">Simulated 15-minute slot (required)</label><select id="apt-slot" required value={draft.slot} onChange={e=>setDraft({...draft,slot:e.target.value})}><option value="">Select slot</option>{slots.map(s=><option key={s}>{s}</option>)}</select></div></div>
 <div className="button-line"><button className="primary" type="submit">{editing?"Save reschedule":"Book DEMO appointment"}</button>{editing&&<button className="secondary" type="button" onClick={()=>{setEditing("");document.getElementById("apt-patient")?.focus();}}>Stop rescheduling</button>}</div></form></section>
 <section className="surface appointment-section" aria-labelledby="appointments-title"><h2 id="appointments-title">Session appointments</h2><div className="appointment-fields">
 <div className="field"><label htmlFor="apt-search">Search patient or DEMO token</label><input id="apt-search" value={filters.query} onChange={e=>setFilters({...filters,query:e.target.value})}/></div>
 <div className="field"><label htmlFor="filter-date">Filter local date</label><input id="filter-date" type="date" value={filters.date} onChange={e=>setFilters({...filters,date:e.target.value})}/></div>
 <div className="field"><label htmlFor="filter-doctor">Filter synthetic doctor</label><select id="filter-doctor" value={filters.doctorId} onChange={e=>setFilters({...filters,doctorId:e.target.value})}><option value="">All doctors</option>{doctors.map(d=><option key={d.id} value={d.id}>{d.name}</option>)}</select></div>
 <div className="field"><label htmlFor="filter-status">Filter appointment status</label><select id="filter-status" value={filters.status} onChange={e=>setFilters({...filters,status:e.target.value})}><option value="">All statuses</option>{["Booked","Checked in","Cancelled"].map(s=><option key={s}>{s}</option>)}</select></div></div>
 <p>{shown.length} matching appointments</p><div className="appointment-list">{shown.map(a=><article className="appointment-card" key={a.id}><h3>{a.id} ? DEMO token, not an MRN</h3><p>{patients.find(p=>p.id===a.patientId)?.name} ? {a.patientId}</p><p>{doctorName(a.doctorId)} ? {a.date} ? {a.slot} ? {a.branch}</p><p><strong>Appointment status: {a.status}</strong></p><details><summary>In-memory appointment history</summary><ul>{a.history.map((h,i)=><li key={i}>{h}</li>)}</ul></details>{a.status==="Booked"&&<div className="button-line"><button className="secondary" onClick={()=>edit(a)} aria-label={`Reschedule ${a.id}`}>Reschedule</button><button className="secondary" onClick={()=>finish(act(appointments,a.id,"cancel",a,patients,today))} aria-label={`Cancel ${a.id}`}>Cancel appointment</button><button className="primary" disabled={a.date!==today} onClick={()=>finish(act(appointments,a.id,"checkIn",a,patients,today))} aria-label={`Check in ${a.id}`}>Reception check-in</button></div>}</article>)}{!shown.length&&<p>No matching demo appointments.</p>}</div></section>
 <section className="surface appointment-section" aria-labelledby="checkin-title"><h2 id="checkin-title">Receptionist check-in queue</h2><p className="hint">Reception arrivals only; clinical patient status is unchanged. Includes session arrivals across dates.</p><ol>{appointments.filter(a=>a.status==="Checked in").map(a=><li key={a.id}>{a.id} ? {patients.find(p=>p.id===a.patientId)?.name} ? {doctorName(a.doctorId)} ? {a.date} {a.slot}</li>)}</ol>{!appointments.some(a=>a.status==="Checked in")&&<p>No appointments checked in this session.</p>}</section></>;
}
