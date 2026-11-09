import {useRef,useState} from 'react';
import {createDraft,postPayment,postRefund,dailyReport,formatINR,totals,DEMO_ACCESS,type Invoice,type FinanceRole} from './model';
import './preview.css';

const toPaise=(value:string):number=>{
 const t=value.trim();
 if(!/^\d{1,8}(\.\d{1,2})?$/.test(t))throw new Error('Enter a valid amount in rupees, with at most 2 decimal places.');
 const parts=t.split('.');
 const result=Number(parts[0])*100+Number((parts[1]||'').padEnd(2,'0'));
 if(result<1||!Number.isSafeInteger(result))throw new Error('Amount must be greater than zero.');
 return result;
};
export default function BillingPreview(){
 const [role,setRole]=useState<FinanceRole>('billing');
 const [invoices,setInvoices]=useState<Invoice[]>([]);
 const [activeId,setActiveId]=useState('');
 const [service,setService]=useState('OPD Consultation');
 const [fee,setFee]=useState('500');
 const [payment,setPayment]=useState('250');
 const [refund,setRefund]=useState('100');
 const [refundPaymentId,setRefundPaymentId]=useState('');
 const [workDate,setWorkDate]=useState('2026-11-10');
 const [notice,setNotice]=useState('All records are fictional and disappear on refresh.');
 const seq=useRef(1);
 const access={...DEMO_ACCESS,role};
 const permitted=role==='billing'||role==='admin';
 const active=invoices.find(x=>x.id===activeId);
 let report:ReturnType<typeof dailyReport>|null=null;
 try{report=dailyReport(access,invoices,workDate);}catch{/* Deny view for unauthorized role */}
 function attempt(action:()=>void){try{action();}catch(e){setNotice(e instanceof Error?e.message:'Operation refused.');}}
 function replaceInvoice(updated:Invoice){setInvoices(list=>list.map(x=>x.id===updated.id?updated:x));}
 function create(){attempt(()=>{
  const id='SYN-INV-'+String(seq.current++);
  const bill=createDraft(access,{id,patientId:'SYN-PATIENT-001',doctorId:'SYN-DR-001',date:workDate,lines:[{code:'OPD',label:service,amountPaise:toPaise(fee)}]});
  setInvoices(list=>[...list,bill]);setActiveId(id);setRefundPaymentId('');setNotice('Synthetic invoice created. Nothing posted to a real system.');
 });}
 function pay(){if(!active)return;attempt(()=>{
  const result=postPayment(access,active,{id:'PAY-'+String(seq.current++),date:workDate,amountPaise:toPaise(payment)});
  replaceInvoice(result);setNotice('Synthetic partial payment added. No financial transaction occurred.');
 });}
 function refundAmount(){if(!active)return;attempt(()=>{
  const result=postRefund(access,active,{id:'REF-'+String(seq.current++),paymentId:refundPaymentId,amountPaise:toPaise(refund),date:workDate});
  replaceInvoice(result);setNotice('Synthetic refund entry recorded. No money transferred.');
 });}
 return <div className="bill-app">
 <header className="bill-top"><div className="bill-top-inner"><div><strong>AroNexa <small>Clinic Edition</small></strong><p>Billing &amp; Daily Reports · Prototype</p></div><span>FICTIONAL FINANCIAL DATA</span></div></header>
 <main className="bill-container"><div className="bill-title"><div><p className="bill-eyebrow">OPD · Clinic operations</p><h1>Billing &amp; Daily Reports</h1><p>Create a draft bill, record an example payment and review daily collections in one workspace.</p></div><div className="bill-role"><label htmlFor="bill-role">Role demonstration</label><select id="bill-role" value={role} onChange={e=>setRole(e.target.value as FinanceRole)}><option value="billing">Billing staff</option><option value="admin">Clinic admin</option><option value="doctor">Doctor — denied</option><option value="receptionist">Receptionist — denied</option></select></div></div>
 {!permitted?<section className="bill-panel" role="alert"><h2>Billing access restricted</h2><p>This synthetic demo denies unsupported roles. Real user permissions must be enforced by the backend and database.</p></section>:<>
 <section className="bill-panel"><div className="bill-heading"><div><h2>1 · Create an OPD bill</h2><p>Fictional patient: SYN-PATIENT-001 · Synthetic doctor: SYN-DR-001</p></div><span className="bill-chip">Demo only</span></div>
 <form onSubmit={e=>{e.preventDefault();create();}}><div className="bill-grid"><div className="bill-field"><label htmlFor="bill-service">Service</label><input id="bill-service" value={service} maxLength={100} onChange={e=>setService(e.target.value)}/></div><div className="bill-field"><label htmlFor="bill-fee">Fee (₹)</label><input id="bill-fee" type="text" inputMode="decimal" value={fee} onChange={e=>setFee(e.target.value)} placeholder="e.g. 500.00"/></div><div className="bill-field"><label htmlFor="bill-date">Transaction date</label><input id="bill-date" type="date" value={workDate} onChange={e=>setWorkDate(e.target.value)}/></div></div><div className="bill-actions"><button type="submit">Create synthetic bill</button><small>Invoices cannot be sent, printed or posted.</small></div></form>
 </section>
 <section className="bill-panel"><div className="bill-heading"><div><h2>2 · Payment tracking</h2><p>All entries remain in temporary browser memory.</p></div>{active&&<span className="bill-chip">{active.id}</span>}</div>
 {!active?<p>Start by creating a synthetic bill above.</p>:<>
 <div className="bill-totals"><div><span>Total fees</span><strong>{formatINR(totals(active).charged)}</strong></div><div><span>Net paid</span><strong>{formatINR(totals(active).paid-totals(active).refunded)}</strong></div><div><span>Outstanding</span><strong>{formatINR(totals(active).balance)}</strong></div></div>
 <div className="bill-grid"><div className="bill-field"><label htmlFor="bill-payment">Record partial payment (₹)</label><input id="bill-payment" type="text" inputMode="decimal" value={payment} onChange={e=>setPayment(e.target.value)}/><button type="button" onClick={pay}>Add synthetic payment</button></div>
 <div className="bill-field"><label htmlFor="bill-refund-pay">Refund against payment</label><select id="bill-refund-pay" value={refundPaymentId} onChange={e=>setRefundPaymentId(e.target.value)}><option value="">Select payment</option>{active.events.filter(e=>e.kind==='PAYMENT').map(e=><option key={e.id} value={e.id}>{e.id} · {formatINR(e.amountPaise)}</option>)}</select></div>
 <div className="bill-field"><label htmlFor="bill-refund">Synthetic refund (₹)</label><input id="bill-refund" type="text" inputMode="decimal" value={refund} onChange={e=>setRefund(e.target.value)}/><button type="button" className="bill-muted" onClick={refundAmount} disabled={!refundPaymentId}>Record demo refund</button></div></div>
 <div className="bill-table"><table><caption>Append-only demonstration transaction history</caption><thead><tr><th>Type</th><th>Date</th><th>Amount</th><th>Reference</th></tr></thead><tbody>{active.events.map(e=><tr key={e.id}><td>{e.kind}</td><td>{e.date}</td><td>{formatINR(e.amountPaise)}</td><td>{e.againstPaymentId||e.id}</td></tr>)}</tbody></table></div>
 </>}
 </section>
 <section className="bill-panel"><div className="bill-heading"><div><h2>3 · Daily report</h2><p>Collections are calculated from payment and refund events, not from a manually entered summary.</p></div><span className="bill-chip">Date: {workDate}</span></div>
 {report&&<div className="bill-report"><div><span>Invoices</span><strong>{report.invoiceCount}</strong></div><div><span>Billed</span><strong>{formatINR(report.invoicedPaise)}</strong></div><div><span>Payments</span><strong>{formatINR(report.paymentsPaise)}</strong></div><div><span>Refunds</span><strong>{formatINR(report.refundsPaise)}</strong></div><div><span>Net collections</span><strong>{formatINR(report.netCollectionsPaise)}</strong></div></div>}
 </section>
 </>}
 <div className="bill-status" role="status" aria-live="polite">{notice}</div><p className="bill-footer">Internal prototype. No real patient details, database, payment provider, receipt or tax compliance. Access restrictions shown here are not production security.</p></main></div>;
}
