import {useRef,useState,type FormEvent} from 'react';
import {signInStaff,revokeStaffToken,type StaffSession} from '../staffSessionClient.ts';
import {
  createBillingCandidateFlow,loadDailyBillingCandidate,rupeesToPaise,
  type BillingContext,type BillReceipt,type DailyCollections,BillingError,
} from '../billingCandidateClient.ts';
import {formatINR} from '../billing/model.ts';
import '../connected-reception/connected-reception.css';
import './billing-preview.css';

type Credentials={tenantCode:string;username:string;password:string};
const blank=():Credentials=>({tenantCode:'',username:'',password:''});
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function todayClinic(){
  const parts=new Intl.DateTimeFormat('en-GB',{
    timeZone:'Asia/Kolkata',year:'numeric',month:'2-digit',day:'2-digit',
  }).formatToParts(new Date());
  const field=(name:string)=>parts.find(x=>x.type===name)?.value??'';
  return field('year')+'-'+field('month')+'-'+field('day');
}

export default function ConnectedBillingPreview(){
  const auth=useRef<StaffSession|null>(null);
  const flow=useRef<ReturnType<typeof createBillingCandidateFlow>|null>(null);
  const [credentials,setCredentials]=useState<Credentials>(blank);
  const [branchId,setBranchId]=useState('');
  const [appointmentId,setAppointmentId]=useState('');
  const [synthetic,setSynthetic]=useState(false);
  const [signedIn,setSignedIn]=useState(false);
  const [actorLabel,setActorLabel]=useState('');
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [context,setContext]=useState<BillingContext|null>(null);
  const [bill,setBill]=useState<BillReceipt|null>(null);
  const [paymentRupees,setPaymentRupees]=useState('');
  const [date,setDate]=useState(todayClinic);
  const [report,setReport]=useState<DailyCollections|null>(null);

  function explanation(e:unknown){
    return e instanceof Error
      &&['StaffSessionError','BillingError'].includes(e.name)
      ?e.message:'Billing request failed. Reopen the server record before continuing.';
  }
  function clearVisit(){
    flow.current?.dispose();flow.current=null;
    setContext(null);setBill(null);setPaymentRupees('');
  }
  async function signIn(e:FormEvent<HTMLFormElement>){
    e.preventDefault();if(busy||!synthetic)return;
    setBusy(true);setError('');setNotice('');
    let active:StaffSession|null=null;
    try{
      if(!uuid.test(branchId))throw new BillingError('INVALID_REQUEST');
      active=await signInStaff(credentials);
      if(!active.roles.includes('BILLING')
        ||!active.permissions.includes('OPD_BILL_READ')
        ||!active.permissions.includes('OPD_DAILY_REPORT_READ'))
        throw new BillingError('NOT_AUTHORIZED');
      auth.current=active;
      setActorLabel(active.username);
      setCredentials(blank());
      setSignedIn(true);
    }catch(failure){
      if(active)await revokeStaffToken(active.accessToken);
      setCredentials(prev=>({...prev,password:''}));
      setError(explanation(failure));
    }finally{setBusy(false);}
  }

  async function openVisit(e:FormEvent<HTMLFormElement>){
    e.preventDefault();if(busy||!auth.current)return;
    setBusy(true);setError('');setNotice('');
    clearVisit();
    try{
      const candidate=createBillingCandidateFlow(auth.current,
        branchId,appointmentId.trim());
      flow.current=candidate;
      const state=await candidate.open();
      setContext(state.context);setBill(state.bill);
      if(state.bill)setNotice('Existing fictional OPD bill verified on the server.');
    }catch(failure){
      clearVisit();setError(explanation(failure));
    }finally{setBusy(false);}
  }

  async function createBill(){
    if(busy||!flow.current||!context||bill)return;
    setBusy(true);setError('');setNotice('');
    try{
      const created=await flow.current.create();
      setBill(created);
      setNotice('Fictional OPD bill created with server fee. NOT a legal invoice.');
    }catch(failure){
      clearVisit();setError(explanation(failure));
    }finally{setBusy(false);}
  }

  async function postCash(e:FormEvent<HTMLFormElement>){
    e.preventDefault();if(busy||!flow.current||!bill)return;
    setBusy(true);setError('');setNotice('');
    try{
      const amount=rupeesToPaise(paymentRupees);
      const result=await flow.current.cash(amount);
      setBill(result);setPaymentRupees('');
      setReport(null);
      setNotice('FICTIONAL cash entry recorded. No money was collected or verified.');
    }catch(failure){
      clearVisit();setReport(null);setError(explanation(failure));
    }finally{setBusy(false);}
  }

  async function loadReport(e:FormEvent<HTMLFormElement>){
    e.preventDefault();if(busy||!auth.current)return;
    setBusy(true);setError('');setNotice('');setReport(null);
    try{
      const result=await loadDailyBillingCandidate(auth.current,branchId,date);
      setReport(result);
    }catch(failure){setError(explanation(failure));}
    finally{setBusy(false);}
  }

  function signOut(){
    if(busy)return;
    const token=auth.current?.accessToken;
    auth.current=null;
    clearVisit();setReport(null);setError('');setNotice('');
    setSignedIn(false);setActorLabel('');
    setCredentials(blank());setBranchId('');setAppointmentId('');
    setDate(todayClinic());setSynthetic(false);
    if(token)void revokeStaffToken(token);
  }

  return <div className="connected-shell">
    <header className="connected-header">
      <div className="connected-brand">
        <span className="brand-symbol">₹</span>
        <span>AroNexa <small>OPD Billing — disposable QA</small></span>
      </div>
      {signedIn&&
        <button className="outline" type="button" disabled={busy} onClick={signOut}>
          Sign out{actorLabel?' · '+actorLabel:''}
        </button>}
    </header>
    <div className="connected-warning" role="status">
      <strong>TEST ONLY — FICTIONAL BILLING.</strong>
      {' '}No real patient, money movement, tax invoice, receipt or payment settlement.
    </div>
    <main className="connected-main billing-work">
      <div className="connected-heading">
        <p className="eyebrow">CLINIC EDITION · OPD FIRST</p>
        <h1>{signedIn?'OPD Billing & Daily Collections':'Billing staff sign-in'}</h1>
        <p>Simple consultation bills with server-priced fees and a branch-day collection view.</p>
      </div>
      {error&&<div className="connected-error" role="alert">{error}</div>}
      {notice&&<div className="connected-success" role="status">{notice}</div>}

      {!signedIn&&
        <section className="connected-panel" aria-label="Billing staff sign-in">
          <h2>Verify billing staff</h2>
          <form onSubmit={signIn}>
            <label htmlFor="bill-tenant">Clinic code</label>
            <input id="bill-tenant" required value={credentials.tenantCode}
              autoComplete="off"
              onChange={e=>setCredentials(old=>({...old,tenantCode:e.target.value}))}/>
            <label htmlFor="bill-user">Billing username</label>
            <input id="bill-user" required value={credentials.username}
              autoComplete="username"
              onChange={e=>setCredentials(old=>({...old,username:e.target.value}))}/>
            <label htmlFor="bill-password">Billing password</label>
            <input id="bill-password" required type="password"
              value={credentials.password} autoComplete="current-password"
              onChange={e=>setCredentials(old=>({...old,password:e.target.value}))}/>
            <label htmlFor="bill-branch">Assigned branch UUID</label>
            <input id="bill-branch" required value={branchId}
              onChange={e=>setBranchId(e.target.value.trim())}/>
            <label className="connected-check">
              <input type="checkbox" checked={synthetic}
                onChange={e=>setSynthetic(e.target.checked)}/>
              This is a disposable fictional clinic with no real billing.
            </label>
            <button className="solid" type="submit" disabled={busy||!synthetic}>
              {busy?'Verifying…':'Sign in to billing'}
            </button>
          </form>
        </section>}

      {signedIn&&<>
        <section className="connected-panel billing-panel"
          aria-label="Open OPD bill">
          <h2>1 · Find a checked-in consultation</h2>
          <form onSubmit={openVisit}>
            <label htmlFor="bill-appointment">Appointment UUID</label>
            <input id="bill-appointment" required autoComplete="off"
              value={appointmentId}
              onChange={e=>setAppointmentId(e.target.value.trim())}/>
            <button className="solid" type="submit" disabled={busy}>
              {busy?'Verifying…':'Open OPD billing'}
            </button>
          </form>
          {context&&<div className="doctor-section">
            <p className="billing-number">Patient reference: {context.patientId}</p>
            <p>Service: {context.serviceLabel}</p>
            <p><strong>Authorized clinic fee: {formatINR(context.amountPaise)}</strong></p>
            {!bill&&
              <div className="billing-actions">
                <button className="solid" type="button"
                  disabled={busy||!auth.current?.permissions.includes('OPD_BILL_CREATE')}
                  onClick={()=>void createBill()}>Create OPD bill</button>
              </div>}
          </div>}
          {bill&&<div className="doctor-section">
            <p className="billing-number">Internal bill # {bill.invoiceNumber}</p>
            <div className="billing-grid">
              <div className="billing-metric"><small>Fees</small>
                <strong>{formatINR(bill.totalPaise)}</strong></div>
              <div className="billing-metric"><small>Cash recorded</small>
                <strong>{formatINR(bill.paidPaise)}</strong></div>
              <div className="billing-metric"><small>Outstanding</small>
                <strong>{formatINR(bill.outstandingPaise)}</strong></div>
              <div className="billing-metric"><small>Status</small>
                <strong>{bill.status}</strong></div>
            </div>
            <p className="billing-amount-hint">
              Internal reference only. Not a tax-compliant invoice or legal receipt.
            </p>
          </div>}
        </section>

        {bill&&bill.outstandingPaise>0&&
          <section className="connected-panel billing-panel"
            aria-label="Record fictional cash collection">
            <h2>2 · Record a fictional cash payment</h2>
            <p className="billing-warning">This does not verify or move money.</p>
            <form onSubmit={postCash}>
              <label htmlFor="bill-cash">Cash amount in rupees (₹)</label>
              <input id="bill-cash" inputMode="decimal" required
                placeholder="e.g. 250.00"
                value={paymentRupees}
                onChange={e=>setPaymentRupees(e.target.value)}/>
              <p className="billing-amount-hint">
                Maximum remaining: {formatINR(bill.outstandingPaise)}
              </p>
              <button className="solid" type="submit" disabled={
                busy||!auth.current?.permissions.includes('OPD_PAYMENT_POST')}>
                {busy?'Recording…':'Record fictional cash'}
              </button>
            </form>
          </section>}

        <section className="connected-panel billing-panel"
          aria-label="Daily collections report">
          <h2>3 · Branch daily collections</h2>
          <p>Invoices by creation date; cash records by transaction date.
            Values are fictional and shown in Indian rupees.</p>
          <form onSubmit={loadReport}>
            <label htmlFor="bill-report-date">Clinic date (Asia/Kolkata)</label>
            <input id="bill-report-date" type="date" required value={date}
              onChange={e=>setDate(e.target.value)}/>
            <button className="outline" type="submit" disabled={busy}>
              {busy?'Loading…':'Load daily report'}
            </button>
          </form>
          {report&&<div className="billing-grid">
            <div className="billing-metric"><small>Bills created</small>
              <strong>{report.invoiceCount}</strong></div>
            <div className="billing-metric"><small>Amount billed</small>
              <strong>{formatINR(report.billedPaise)}</strong></div>
            <div className="billing-metric"><small>Cash recorded</small>
              <strong>{formatINR(report.collectionsPaise)}</strong></div>
            <div className="billing-metric"><small>Outstanding on day's bills</small>
              <strong>{formatINR(report.outstandingPaise)}</strong></div>
          </div>}
        </section>
      </>}
      <p className="billing-amount-hint">
        R1-08 synthetic QA only. No refunds, taxes, settlement reconciliation,
        legally valid invoicing, production database or real clinic billing.
      </p>
    </main>
  </div>;
}
