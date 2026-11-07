import {useMemo, useState, type FormEvent} from "react";
import {blankIntake,candidateMatches,demoRegistration,maskPhone,seedPatients,validateIntake,DEMO_BRANCH,type DemoPatient,type Intake} from "./receptionModel";
type Page="dashboard"|"register"|"patients";
export default function App(){
 const [page,setPage]=useState<Page>("dashboard");
 const [patients,setPatients]=useState<DemoPatient[]>(()=>[...seedPatients]);
 const [form,setForm]=useState<Intake>(blankIntake);
 const [errors,setErrors]=useState<Record<string,string>>({});
 const [review,setReview]=useState(false);
 const [created,setCreated]=useState<DemoPatient|null>(null);
 const [query,setQuery]=useState("");
 const matches=useMemo(()=>candidateMatches(form,patients),[form,patients]);
 const filtered=useMemo(()=>patients.filter(p=>[p.name,p.id].some(x=>x.toLowerCase().includes(query.toLowerCase().trim()))),[patients,query]);
 const waiting=patients.filter(p=>p.status==="Waiting").length;
 function change<K extends keyof Intake>(key:K,value:Intake[K]){
   setForm(old=>({...old,[key]:value,...(key==="precision"&&value==="UNKNOWN"?{dob:""}:{})}));
   setErrors({});setReview(false);
 }
 function begin(){setForm(blankIntake());setErrors({});setReview(false);setCreated(null);setPage("register");}
 function submit(e:FormEvent){e.preventDefault();setCreated(null);const errs=validateIntake(form);setErrors(errs);if(Object.keys(errs).length)return;setReview(true);}
 function confirm(){
   if(!review || Object.keys(validateIntake(form)).length)return;
   const next=demoRegistration(form,Math.max(...patients.map(p=>Number(p.id.split("-")[1])||0))+1,new Date().toLocaleTimeString("en-IN",{hour:"2-digit",minute:"2-digit",hour12:false}));
   setPatients(old=>[next,...old]);setCreated(next);setReview(false);setForm(blankIntake());setPage("register");
 }
 function nav(target:Page){setPage(target);setReview(false);setCreated(null);}
 return <div className="app">
  <aside className="sidebar"><div className="logo"><span className="logo-mark">✚</span><span>AroNexa<small>Healthcare Platform</small></span></div><div className="menu-label">WORKSPACE</div>
   <nav aria-label="Reception navigation">
    <button className={page==="dashboard"?"active":""} onClick={()=>nav("dashboard")}>▦ <span>Overview</span></button>
    <button className={page==="register"?"active":""} onClick={begin}>⊕ <span>Register patient</span></button>
    <button className={page==="patients"?"active":""} onClick={()=>nav("patients")}>♙ <span>Patient list</span></button>
   </nav>
   <div className="sidebar-bottom"><span className="tiny-dot"/> Receptionist workspace <small>Prototype • No live connection</small></div>
  </aside>
  <div className="main">
   <header className="topbar"><div><span className="location">● {DEMO_BRANCH}</span><span className="separator">/</span><span className="location">Reception</span></div><div className="avatar"><span className="avatar-dot">R</span> Demo Receptionist</div></header>
   <div className="banner" role="status"><strong>DEMO MODE</strong> Synthetic records only. Data stays in temporary browser memory and resets on refresh. Do not enter real patient information.</div>
   <main className="content">
    {page==="dashboard"&&<><div className="page-head"><div><p className="kicker">RECEPTION DESK</p><h1>Good morning, Reception</h1><p className="subtext">A simple view of patient arrivals and today's front-desk activity.</p></div><button className="primary" onClick={begin}>+ Register new patient</button></div>
     <div className="stats"><article><p>Registered in demo</p><b>{patients.length}</b><span>Sample records in this session</span></article><article><p>Waiting</p><b>{waiting}</b><span>Awaiting consultation</span></article><article><p>In consultation</p><b>{patients.filter(p=>p.status==="In consultation").length}</b><span>Currently with clinician</span></article><article><p>New registrations</p><b>{Math.max(0,patients.length-seedPatients.length)}</b><span>Created this session</span></article></div>
     <div className="split"><section className="surface"><div className="surface-title"><div><h2>Reception queue</h2><p>Current sample patient arrivals</p></div><button className="link-button" onClick={()=>nav("patients")}>View all</button></div><PatientTable patients={patients.slice(0,6)}/></section>
     <section className="surface quick"><h2>Quick actions</h2><p>Common tasks for your reception team</p><button onClick={begin}><span className="action-icon">+</span><span><b>New patient registration</b><small>Capture patient details</small></span><span>›</span></button><button onClick={()=>nav("patients")}><span className="action-icon muted">⌕</span><span><b>Find a patient</b><small>Search synthetic records</small></span><span>›</span></button><div className="next-note"><strong>Coming in later increments</strong><p>Appointments, check-in, billing and clinical records are not implemented here.</p></div></section></div></>}
    {page==="patients"&&<><div className="page-head"><div><p className="kicker">PATIENT DIRECTORY</p><h1>Patient list</h1><p className="subtext">Browse synthetic records created in this session.</p></div><button className="primary" onClick={begin}>+ Register new patient</button></div><section className="surface"><div className="searchbar"><label htmlFor="search">Search sample patients</label><input id="search" placeholder="Search by name or demo ID" value={query} onChange={e=>setQuery(e.target.value)}/></div><PatientTable patients={filtered}/></section></>}
    {page==="register"&&<><div className="page-head"><div><p className="kicker">PATIENT ONBOARDING / STEP 1 OF 1</p><h1>New patient registration</h1><p className="subtext">Record essential details, review potential matches, and confirm.</p></div><button className="secondary" onClick={()=>nav("dashboard")}>Back to reception</button></div>
     {created?<section className="surface confirmation" aria-live="polite"><div className="success-mark">✓</div><p className="kicker">REGISTRATION COMPLETE — DEMO ONLY</p><h2>{created.name} added to reception</h2><p>This record exists only in temporary browser memory. It has not been saved to PostgreSQL or a clinical system.</p><div className="receipt"><div><small>Demo registration ID / not an actual MRN</small><strong>{created.id}</strong></div><div><small>Branch</small><strong>{created.branch}</strong></div></div><div className="button-line"><button className="primary" onClick={begin}>Register another patient</button><button className="secondary" onClick={()=>nav("patients")}>View patient list</button></div></section>:
     <div className="form-grid"><section className="surface"><div className="surface-title"><div><h2>Patient details</h2><p>Only minimal fields are requested for this demo.</p></div><span className="step-badge">01 / 01</span></div>
      <form onSubmit={submit} noValidate><div className="field"><label htmlFor="fullName">Patient full name <em>*</em></label><input id="fullName" autoComplete="off" placeholder="e.g. Synthetic Patient" value={form.name} maxLength={100} aria-invalid={!!errors.name} aria-describedby={errors.name?"nameError":undefined} onChange={e=>change("name",e.target.value)}/>{errors.name&&<span className="error" id="nameError">{errors.name}</span>}</div>
       <div className="fields-two"><div className="field"><label htmlFor="dobPrecision">Date of birth</label><select id="dobPrecision" value={form.precision} onChange={e=>change("precision",e.target.value as Intake["precision"])}><option value="UNKNOWN">Unknown</option><option value="FULL">Exact date known</option></select></div><div className="field"><label htmlFor="dob">Date <span className="optional">(when known)</span></label><input id="dob" type="date" disabled={form.precision==="UNKNOWN"} value={form.dob} max={new Date().toISOString().slice(0,10)} aria-invalid={!!errors.dob} onChange={e=>change("dob",e.target.value)}/>{errors.dob&&<span className="error">{errors.dob}</span>}</div></div>
       <div className="field"><label htmlFor="phone">Mobile number <span className="optional">(optional, demo only)</span></label><input id="phone" type="tel" inputMode="numeric" placeholder="10 digit synthetic number" value={form.phone} maxLength={10} autoComplete="off" aria-invalid={!!errors.phone} onChange={e=>change("phone",e.target.value)}/>{errors.phone&&<span className="error">{errors.phone}</span>}<span className="hint">Mobile numbers may be shared by family members; they are not unique identifiers.</span></div>
       <div className="notice"><strong>Privacy by design</strong><p>Do not enter real names, numbers or health details. Verification, consent and identity linking are future protected workflows.</p></div>
       <div className="button-line"><button type="submit" className="primary">Review & continue</button><button type="button" className="secondary" onClick={()=>nav("dashboard")}>Cancel</button></div>
      </form></section>
      <aside className="surface side-help"><h2>Simple workflow</h2><ol><li><b>Capture details</b><span>Enter the patient's essential information.</span></li><li><b>Check suggestions</b><span>Review matching demo records; never auto-merge.</span></li><li><b>Confirm</b><span>Receive a temporary demo registration ID.</span></li></ol><div className="next-note"><b>Scope reminder</b><p>This demo has no backend write, actual MRN, login or appointment booking.</p></div></aside></div>}
     {review&&<div className="modal-wrap" role="presentation"><section className="modal" role="dialog" aria-modal="true" aria-labelledby="reviewTitle"><p className="kicker">IDENTITY REVIEW · SYNTHETIC ONLY</p><h2 id="reviewTitle">Review before registration</h2><p>{matches.length? "Possible same-name patient records were found. Review carefully; no records will be merged.": "No exact-name demo match was found. This does not prove the patient is unique."}</p>{matches.length>0&&<div className="match-list">{matches.map(p=><div className="match" key={p.id}><strong>{p.name}</strong><span>{p.id} · {p.dob||"DOB not known"} · {maskPhone(p.phone)}</span></div>)}</div>}<div className="notice">Creating a new demo identity must be an explicit receptionist decision. Do not use actual patient information.</div><div className="button-line"><button className="primary" onClick={confirm}>Confirm new demo registration</button><button className="secondary" onClick={()=>setReview(false)}>Review / edit details</button></div></section></div>}
    </>}
   </main>
   <footer>ARG Digital Technologies · AroNexa • Internal synthetic workflow preview • Not for patient care</footer>
  </div>
 </div>;
}
function PatientTable({patients}:{patients:DemoPatient[]}){return <div className="table-scroll"><table><thead><tr><th>Patient</th><th>Demo ID</th><th>Arrival</th><th>Status</th></tr></thead><tbody>{patients.map(p=><tr key={p.id}><td><div className="patient-name"><span className="patient-avatar">{p.name[0]}</span>{p.name}</div></td><td>{p.id}</td><td>{p.time}</td><td><span className={"status "+p.status.toLowerCase().replace(/ /g,"-")}>{p.status}</span></td></tr>)}{!patients.length&&<tr><td colSpan={4}>No synthetic patient matches your search.</td></tr>}</tbody></table></div>}
