import {useEffect,useRef,useState,type PointerEvent as RPointer} from 'react';
import {FIXTURE_ACCESS,FIXTURE_PATIENT,SAMPLE_CATALOGUE,canEdit,newDraft,addMedicine,removeMedicine,setInk,setNote,previewDraft,
  type PreviewRole,type MedicineEntry,type InkStroke,type InkPoint} from './model';
import './preview.css';
const BLANK:Omit<MedicineEntry,'id'>={medicine:'',strength:'',form:'',dose:'',route:'',frequency:'',duration:'',quantity:1,instructions:''};
type Field=Exclude<keyof MedicineEntry,'id'|'quantity'>;
const FIELDS:ReadonlyArray<{key:Field,label:string,hint:string}>=[
 {key:'medicine',label:'Medicine name',hint:'Clinician-selected name'},
 {key:'strength',label:'Strength',hint:'Required'},
 {key:'form',label:'Form',hint:'Tablet, liquid, etc.'},
 {key:'dose',label:'Dose',hint:'Enter amount and unit'},
 {key:'route',label:'Route',hint:'Enter route'},
 {key:'frequency',label:'Frequency',hint:'Enter schedule'},
 {key:'duration',label:'Duration',hint:'Enter duration'},
 {key:'instructions',label:'Additional instructions',hint:'Optional'}
];
function clamp(n:number){return Math.max(0,Math.min(1,n));}
function pt(e:RPointer<HTMLCanvasElement>):InkPoint{const r=e.currentTarget.getBoundingClientRect();return {x:clamp((e.clientX-r.left)/r.width),y:clamp((e.clientY-r.top)/r.height),pressure:clamp(e.pressure||0.5)};}
function paint(canvas:HTMLCanvasElement|null,strokes:readonly InkStroke[]){
 const ctx=canvas?.getContext('2d');if(!ctx)return;
 ctx.clearRect(0,0,860,220);ctx.strokeStyle='#143a5b';ctx.fillStyle='#143a5b';ctx.lineWidth=2.5;ctx.lineCap='round';ctx.lineJoin='round';
 strokes.forEach(s=>{if(!s.points.length)return;ctx.beginPath();ctx.moveTo(s.points[0].x*860,s.points[0].y*220);
  if(s.points.length===1){ctx.arc(s.points[0].x*860,s.points[0].y*220,1.4,0,Math.PI*2);ctx.fill();return;}
  s.points.slice(1).forEach(p=>ctx.lineTo(p.x*860,p.y*220));ctx.stroke();});
}
export default function SmartRxPreview(){
 const [role,setRole]=useState<PreviewRole>('doctor');
 const [draft,setDraft]=useState(()=>newDraft(FIXTURE_ACCESS,FIXTURE_PATIENT));
 const [medicine,setMedicine]=useState<Omit<MedicineEntry,'id'>>({...BLANK});
 const [note,setDraftNote]=useState('');
 const [live,setLive]=useState<InkStroke|null>(null);
 const [redo,setRedo]=useState<InkStroke[]>([]);
 const [preview,setPreview]=useState(false);
 const [notice,setNotice]=useState('Synthetic demo only. No patient record is stored.');
 const canvas=useRef<HTMLCanvasElement>(null),active=useRef<InkStroke|null>(null),seq=useRef(1);
 const access={...FIXTURE_ACCESS,role},allowed=canEdit(access,FIXTURE_PATIENT);
 useEffect(()=>{paint(canvas.current,live?[...draft.ink,live]:draft.ink);},[draft.ink,live,allowed]);
 function guarded(action:()=>void){try{action();}catch(err){setNotice(err instanceof Error?err.message:'Action refused.');}}
 function add(){guarded(()=>{setDraft(addMedicine(access,FIXTURE_PATIENT,draft,{...medicine,id:'entry-'+String(seq.current++)}));setMedicine({...BLANK});setPreview(false);setNotice('Medicine saved to in-memory draft only. Not prescribed.');});}
 function remove(id:string){guarded(()=>{setDraft(removeMedicine(access,FIXTURE_PATIENT,draft,id));setPreview(false);setNotice('Draft item removed.');});}
 function down(e:RPointer<HTMLCanvasElement>){if(!allowed||active.current)return;e.preventDefault();e.currentTarget.setPointerCapture(e.pointerId);const s={id:'stroke-'+String(seq.current++),points:[pt(e)]};active.current=s;setLive({...s});}
 function move(e:RPointer<HTMLCanvasElement>){const s=active.current;if(!s||!allowed||s.points.length>=3000)return;s.points=[...s.points,pt(e)];setLive({id:s.id,points:[...s.points]});}
 function up(e:RPointer<HTMLCanvasElement>){const s=active.current;if(!s)return;if(e.currentTarget.hasPointerCapture(e.pointerId))e.currentTarget.releasePointerCapture(e.pointerId);active.current=null;setLive(null);if(!allowed)return;
  guarded(()=>{setDraft(setInk(access,FIXTURE_PATIENT,draft,[...draft.ink,s]));setRedo([]);setNotice('Ink stroke saved in demo memory. Not a signature.');});}
 function undo(){if(!allowed||!draft.ink.length)return;guarded(()=>{const last=draft.ink[draft.ink.length-1];setDraft(setInk(access,FIXTURE_PATIENT,draft,draft.ink.slice(0,-1)));setRedo(s=>[...s,last]);setNotice('Ink stroke undone.');});}
 function redoInk(){if(!allowed||!redo.length)return;guarded(()=>{setDraft(setInk(access,FIXTURE_PATIENT,draft,[...draft.ink,redo[redo.length-1]]));setRedo(s=>s.slice(0,-1));setNotice('Ink stroke restored.');});}
 function clear(){guarded(()=>{setDraft(setInk(access,FIXTURE_PATIENT,draft,[]));setRedo([]);setNotice('Ink draft cleared.');});}
 function saveNote(){guarded(()=>{setDraft(setNote(access,FIXTURE_PATIENT,draft,note));setNotice('Free text saved in demo memory.');});}
 function inspect(){guarded(()=>{previewDraft(access,FIXTURE_PATIENT,draft);setPreview(true);setNotice('Watermarked preview only. No clinical finalization.');});}
 return <div className="rx-app">
 <header className="rx-header"><div className="rx-header-inner"><div><strong>AroNexa <small>Clinic Edition</small></strong><p>Smart Rx + Clinical Ink · Internal Prototype</p></div><span className="rx-demo">SYNTHETIC DATA ONLY</span></div></header>
 <main className="rx-container">
 <div className="rx-title"><div><p className="rx-overline">OPD · Doctor workflow</p><h1>Prescription workspace</h1><p>Simple, structured and clinician-controlled. No treatment suggestions.</p></div>
 <div className="rx-role"><label htmlFor="rx-role">Access demonstration</label><select id="rx-role" value={role} onChange={e=>{setRole(e.target.value as PreviewRole);setPreview(false);}}><option value="doctor">Assigned doctor</option><option value="receptionist">Receptionist — denied</option></select></div></div>
 {!allowed?<section className="rx-panel" role="alert"><h2>Access restricted</h2><p>Only the assigned doctor can use this fictional clinical preview. Actual authorization belongs in the backend and PostgreSQL.</p></section>:<>
 <section className="rx-patient rx-panel"><div><p className="rx-overline">Fictional patient</p><h2>{FIXTURE_PATIENT.name}</h2><p>{FIXTURE_PATIENT.id} · {FIXTURE_ACCESS.doctorId}</p></div><div className="rx-warning"><strong>Verify allergies</strong><span>{FIXTURE_PATIENT.allergyReminder}</span></div></section>
 <section className="rx-panel"><div className="rx-section"><div><h2>1 · Enter medicines</h2><p>Every medicine instruction is entered by a clinician. No auto-prescribing.</p></div><span className="rx-chip">Draft revision {draft.revision}</span></div>
 <form onSubmit={e=>{e.preventDefault();add();}}><div className="rx-grid">{FIELDS.map(f=><div className="rx-field" key={f.key}><label htmlFor={'rx-'+f.key}>{f.label}</label><input id={'rx-'+f.key} list={f.key==='medicine'?'rx-list':undefined} maxLength={f.key==='instructions'?500:120} placeholder={f.hint} value={medicine[f.key]} onChange={e=>setMedicine(m=>({...m,[f.key]:e.target.value}))}/></div>)}
 <div className="rx-field"><label htmlFor="rx-quantity">Quantity</label><input id="rx-quantity" type="number" min="1" max="10000" step="1" value={medicine.quantity} onChange={e=>setMedicine(m=>({...m,quantity:Number(e.target.value)}))}/></div></div>
 <datalist id="rx-list">{SAMPLE_CATALOGUE.map(x=><option key={x} value={x}/>)}</datalist>
 <div className="rx-buttons"><button type="submit">Add to draft</button><span>Nothing is issued, sent or finalized.</span></div></form>
 {draft.medicines.length>0&&<div className="rx-table"><table><caption>Draft medicines</caption><thead><tr><th>Medicine</th><th>Instructions</th><th>Qty</th><th>Action</th></tr></thead><tbody>{draft.medicines.map(m=><tr key={m.id}><td><strong>{m.medicine}</strong><small>{m.strength} · {m.form}</small></td><td>{m.dose} · {m.route} · {m.frequency} · {m.duration}{m.instructions&&<small>{m.instructions}</small>}</td><td>{m.quantity}</td><td><button type="button" className="rx-muted" onClick={()=>remove(m.id)}>Remove</button></td></tr>)}</tbody></table></div>}
 </section>
 <section className="rx-panel"><div className="rx-section"><div><h2>2 · Clinical ink</h2><p>Use a mouse, finger or stylus. Structured medicine fields stay separate.</p></div><span className="rx-chip">{draft.ink.length} strokes</span></div>
 <div className="rx-drawing"><canvas ref={canvas} width={860} height={220} aria-label="Drawing surface for mouse touch and pen. Keyboard users can enter a text annotation below." onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={()=>{active.current=null;setLive(null);}}/></div>
 <div className="rx-buttons"><button type="button" className="rx-muted" disabled={!draft.ink.length} onClick={undo}>Undo</button><button type="button" className="rx-muted" disabled={!redo.length} onClick={redoInk}>Redo</button><button type="button" className="rx-muted" disabled={!draft.ink.length} onClick={clear}>Clear</button></div>
 <div className="rx-field rx-textarea"><label htmlFor="rx-note">Accessible text alternative / clinical note</label><textarea id="rx-note" rows={3} maxLength={2000} value={note} onChange={e=>setDraftNote(e.target.value)} placeholder="Fictional notes only. No medication orders." /></div><button type="button" className="rx-muted" onClick={saveNote}>Save note to demo</button>
 </section>
 <section className="rx-panel rx-review"><div><h2>3 · Review before finalization</h2><p>This preview cannot issue a prescription or print a clinical document.</p></div><button type="button" onClick={inspect}>Preview draft</button></section>
 {preview&&<section className="rx-panel rx-watermark" aria-label="Non-clinical draft preview"><h2>DEMO ONLY — INVALID AS PRESCRIPTION</h2><p>Fictional patient · Revision {draft.revision}</p><ol>{draft.medicines.map(m=><li key={m.id}>{m.medicine} · {m.strength} · {m.dose} · {m.route} · {m.frequency} · {m.duration} · Qty {m.quantity}</li>)}</ol></section>}
 </>}
 <div className="rx-status" role="status" aria-live="polite">{notice}</div>
 <p className="rx-disclaimer">Internal synthetic prototype only. No patient data, clinical authorization, production security, persistence, legally valid signature, PDF export or finalized prescription.</p>
 </main></div>;
}
