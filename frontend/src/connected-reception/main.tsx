import React,{useState} from 'react';
import ReactDOM from 'react-dom/client';
import ConnectedReceptionPreview from './ConnectedReceptionPreview.tsx';
import DoctorConsultationPreview from '../connected-doctor/DoctorConsultationPreview.tsx';
import ConnectedBillingPreview from '../connected-billing/ConnectedBillingPreview.tsx';
import type {DoctorHandoff} from '../receptionDoctorHandoff.ts';
import type {BillingHandoff} from '../pilot06Handoff.ts';

/**
 * PILOT-06 test-only same-tab journey. Each role signs in independently,
 * revokes its prior bearer and re-reads the same synthetic patient record
 * from its own authorized server endpoint. No tokens, notes, medicines,
 * clinical details, patient names, or credentials cross role boundaries.
 * Reload clears the in-memory hints; persistence lives on disposable PG17.
 */
function PilotIntegratedClinicJourney(){
  const [doctorHandoff,setDoctorHandoff]=useState<DoctorHandoff|null>(null);
  const [billingHandoff,setBillingHandoff]=useState<BillingHandoff|null>(null);
  return billingHandoff
    ?<ConnectedBillingPreview handoff={billingHandoff}/>
    :doctorHandoff
      ?<DoctorConsultationPreview handoff={doctorHandoff}
          onBillingHandoff={setBillingHandoff}/>
      :<ConnectedReceptionPreview onDoctorHandoff={setDoctorHandoff}/>;
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode><PilotIntegratedClinicJourney /></React.StrictMode>
);
