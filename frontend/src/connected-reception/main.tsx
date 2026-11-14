import React,{useState} from 'react';
import ReactDOM from 'react-dom/client';
import ConnectedReceptionPreview from './ConnectedReceptionPreview.tsx';
import DoctorConsultationPreview from '../connected-doctor/DoctorConsultationPreview.tsx';
import type {DoctorHandoff} from '../receptionDoctorHandoff.ts';

/**
 * Test-only, same-tab role handoff; no URL/query, localStorage or cross-tab
 * tokens, patient data or clinical notes. Doctor must sign in independently.
 * Reload intentionally returns to receptionist login; persistence is server-side.
 */
function PilotReceptionDoctorJourney(){
  const [handoff,setHandoff]=useState<DoctorHandoff|null>(null);
  return handoff
    ?<DoctorConsultationPreview handoff={handoff}/>
    :<ConnectedReceptionPreview onDoctorHandoff={setHandoff}/>;
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode><PilotReceptionDoctorJourney /></React.StrictMode>
);
