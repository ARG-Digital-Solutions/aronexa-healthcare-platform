import {useEffect,useRef,useState,type FormEvent} from 'react';
import type {StaffSession} from '../staffSessionClient.ts';
import {normalizeMedicine,type MedicineEntry} from '../smart-rx/model.ts';
import {
  createDoctorPrescriptionCandidateFlow,type DoctorRxReceipt,
} from '../doctorPrescriptionCandidateClient.ts';
import './doctor-rx-preview.css';

type InputMedicine=Omit<MedicineEntry,'id'>;
type TextField=Exclude<keyof InputMedicine,'quantity'>;
const freshMedicine=():InputMedicine=>({
  medicine:'',strength:'',form:'',dose:'',route:'',frequency:'',
  duration:'',quantity:1,instructions:'',
});
const fields:ReadonlyArray<{key:TextField,label:string,max:number,required:boolean}>=[
  {key:'medicine',label:'Medicine name',max:120,required:true},
  {key:'strength',label:'Strength',max:80,required:true},
  {key:'form',label:'Form',max:50,required:true},
  {key:'dose',label:'Dose',max:120,required:true},
  {key:'route',label:'Route',max:60,required:true},
  {key:'frequency',label:'Frequency',max:100,required:true},
  {key:'duration',label:'Duration',max:100,required:true},
  {key:'instructions',label:'Additional instructions',max:500,required:false},
];

export default function DoctorPrescriptionPreview({
  session,branchId,appointmentId,onClose,
}:{
  session:StaffSession;branchId:string;appointmentId:string;onClose:()=>void;
}){
  const flowRef=useRef<ReturnType<typeof createDoctorPrescriptionCandidateFlow>|null>(null);
  const [rx,setRx]=useState<DoctorRxReceipt|null>(null);
  const [lines,setLines]=useState<MedicineEntry[]>([]);
  const [form,setForm]=useState<InputMedicine>(freshMedicine);
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const hasChanges=rx!==null&&JSON.stringify(lines)!==JSON.stringify(rx.medicines);

  useEffect(()=>{
    let live=true;
    const flow=createDoctorPrescriptionCandidateFlow(session,branchId,appointmentId);
    flowRef.current=flow;
    void flow.open().then(receipt=>{
      if(!live)return;
      setRx(receipt);setLines(receipt.medicines.map(m=>({...m})));
    }).catch(failure=>{
      if(!live)return;
      setError(failure instanceof Error?failure.message:
        'Unable to verify the prescription draft.');
    }).finally(()=>{if(live)setLoading(false);});
    return ()=>{
      live=false;flow.dispose();
      if(flowRef.current===flow)flowRef.current=null;
    };
  },[session,branchId,appointmentId]);

  async function reload(){
    if(loading||!flowRef.current)return;
    setLoading(true);setError('');setNotice('');setRx(null);setLines([]);
    try{
      const next=await flowRef.current.open();
      setRx(next);setLines(next.medicines.map(m=>({...m})));
    }catch(failure){
      setError(failure instanceof Error?failure.message:
        'Unable to verify the prescription draft.');
    }finally{setLoading(false);}
  }

  function addLine(e:FormEvent<HTMLFormElement>){
    e.preventDefault();
    if(loading||!rx||lines.length>=30)return;
    setError('');setNotice('');
    try{
      const normalized=normalizeMedicine({...form,id:crypto.randomUUID()});
      setLines(old=>[...old,normalized]);
      setForm(freshMedicine());
    }catch{
      setError('Enter complete fictional medicine details and a valid quantity.');
    }
  }

  function removeLine(id:string){
    if(loading)return;
    setLines(old=>old.filter(m=>m.id!==id));
    setError('');setNotice('');
  }

  async function save(e:FormEvent<HTMLFormElement>){
    e.preventDefault();
    if(loading||!flowRef.current||!rx||!lines.length||!hasChanges)return;
    setLoading(true);setError('');setNotice('');
    try{
      const result=await flowRef.current.save(lines);
      setRx(result);setLines(result.medicines.map(m=>({...m})));
      setNotice('Prescription draft saved on the test server. Revision '+
        result.revision+'. This is NOT a valid prescription.');
    }catch(failure){
      // Never keep a stale draft on a network/authorization/version error.
      setRx(null);setLines([]);
      setError(failure instanceof Error?failure.message:
        'Save was not verified. Reopen the draft before continuing.');
    }finally{setLoading(false);}
  }

  return <section className="connected-panel" aria-label="Doctor prescription draft">
    <h2>Structured prescription — draft only</h2>
    <p className="connected-footnote">
      FICTIONAL MEDICINES ONLY. No prescribing, dispensing, signature,
      printing, sharing, automatic dosing or treatment recommendations.
    </p>
    <p className="doctor-ref">Appointment: {appointmentId}</p>
    {error&&<p role="alert" className="connected-error">{error}</p>}
    {notice&&<p role="status" className="connected-success">{notice}</p>}
    {loading&&<p role="status">Verifying the prescription draft…</p>}
    {!loading&&!rx&&
      <div className="doctor-actions">
        <button className="outline" type="button" onClick={()=>void reload()}>
          Reopen server draft
        </button>
        <button className="outline" type="button" onClick={onClose}>
          Return to consultation lookup
        </button>
      </div>}
    {rx&&
      <>
        <p className="doctor-note-info">
          Stage: {rx.stage} · Prescription revision: {rx.revision} ·
          Consultation revision: {rx.consultationRevision}
        </p>
        <form className="rx-entry-form" onSubmit={addLine}
          aria-label="Enter fictional medicine">
          <h3>Add structured medicine</h3>
          <div className="rx-entry-grid">
            {fields.map(f=>
              <div key={f.key} className="rx-entry-field">
                <label htmlFor={'med-'+f.key}>{f.label}</label>
                <input id={'med-'+f.key} required={f.required}
                  maxLength={f.max} disabled={loading||lines.length>=30}
                  value={form[f.key]}
                  onChange={e=>setForm(old=>({...old,[f.key]:e.target.value}))}/>
              </div>)}
            <div className="rx-entry-field">
              <label htmlFor="med-quantity">Quantity</label>
              <input id="med-quantity" type="number" min={1} max={10000}
                step={1} required disabled={loading||lines.length>=30}
                value={form.quantity}
                onChange={e=>setForm(old=>({...old,quantity:Number(e.target.value)}))}/>
            </div>
          </div>
          <button className="outline" type="submit"
            disabled={loading||lines.length>=30}>Add medicine to draft</button>
        </form>
        <div className="rx-draft-list" aria-label="Draft medicine lines">
          <h3>Draft medicine lines ({lines.length}/30)</h3>
          {lines.length===0&&
            <p>No medicine lines. Add at least one before saving.</p>}
          {lines.map((m,i)=><div key={m.id} className="rx-draft-line">
            <strong>{i+1}. {m.medicine} · {m.strength}</strong>
            <p>{m.form} · {m.dose} · {m.route} · {m.frequency} ·
              {m.duration} · Qty {m.quantity}</p>
            {m.instructions&&<p>{m.instructions}</p>}
            <button className="outline" type="button" disabled={loading}
              onClick={()=>removeLine(m.id)}>Remove medicine</button>
          </div>)}
        </div>
        <form onSubmit={save} className="rx-save-form">
          <div className="doctor-actions">
            <button className="solid" type="submit"
              disabled={loading||!lines.length||!hasChanges}>
              Save prescription draft
            </button>
            <button className="outline" type="button" disabled={loading}
              onClick={()=>void reload()}>Reopen server draft</button>
            <button className="outline" type="button" disabled={loading}
              onClick={onClose}>Return to consultation lookup</button>
          </div>
        </form>
        <p className="rx-invalid-watermark">
          DEMO ONLY — INVALID AS PRESCRIPTION
        </p>
      </>}
  </section>;
}
