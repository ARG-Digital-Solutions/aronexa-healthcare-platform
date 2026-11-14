import type {DoctorHandoff} from './receptionDoctorHandoff.ts';
import {doctorCanReceiveHandoff} from './receptionDoctorHandoff.ts';
import type {StaffSession} from './staffSessionClient.ts';
import type {DoctorRxReceipt} from './doctorPrescriptionCandidateClient.ts';
import type {BillingContext} from './billingCandidateClient.ts';

/** Test-only, in-memory routing hint; never authorizes billing or stores tokens. */
export interface BillingHandoff extends DoctorHandoff {
  readonly prescriptionRevision:number;
  readonly consultationRevision:number;
}

export function prepareBillingHandoff(
  handoff:DoctorHandoff,doctor:StaffSession,rx:DoctorRxReceipt,
):BillingHandoff{
  if(!doctorCanReceiveHandoff(handoff,doctor,handoff.branchId)
    ||!doctor.permissions.includes('OPD_RX_DRAFT_WRITE')
    ||rx.stage!=='RX_DRAFT'
    ||!Number.isSafeInteger(rx.revision)||rx.revision<1
    ||!Number.isSafeInteger(rx.consultationRevision)
    ||rx.consultationRevision<1
    ||!Array.isArray(rx.medicines)||rx.medicines.length<1
    ||rx.tenantId!==handoff.tenantId
    ||rx.branchId!==handoff.branchId
    ||rx.patientId!==handoff.patientId
    ||rx.appointmentId!==handoff.appointmentId
    ||rx.doctorMembershipId!==handoff.doctorMembershipId)
    throw new Error('Billing handoff requires an authenticated saved prescription draft');
  return Object.freeze({
    ...handoff,
    prescriptionRevision:rx.revision,
    consultationRevision:rx.consultationRevision,
  });
}

export function billingCanReceiveHandoff(
  handoff:BillingHandoff,staff:StaffSession,branchId:string,
):boolean{
  return !!handoff&&!!staff
    &&staff.tenantId===handoff.tenantId
    &&branchId===handoff.branchId
    &&staff.membershipId!==handoff.doctorMembershipId
    &&staff.roles.includes('BILLING')
    &&staff.permissions.includes('OPD_BILL_READ')
    &&staff.permissions.includes('OPD_DAILY_REPORT_READ')
    &&handoff.prescriptionRevision>=1
    &&handoff.consultationRevision>=1;
}

export function billingContextMatchesHandoff(
  handoff:BillingHandoff,context:BillingContext,
):boolean{
  return !!context&&!!handoff
    &&context.tenantId===handoff.tenantId
    &&context.branchId===handoff.branchId
    &&context.patientId===handoff.patientId
    &&context.appointmentId===handoff.appointmentId
    &&context.doctorMembershipId===handoff.doctorMembershipId
    &&context.serviceCode==='OPD-CONSULTATION';
}
