import type {AppointmentCandidateReceipt} from './appointmentCandidateClient.ts';
import type {StaffSession} from './staffSessionClient.ts';

const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const valid=(v:unknown):v is string=>typeof v==='string'&&uuid.test(v)
  &&v!=='00000000-0000-0000-0000-000000000000';

/** In-memory routing hint ONLY. Never authorizes or caches clinical content. */
export interface DoctorHandoff {
  readonly tenantId:string;
  readonly branchId:string;
  readonly appointmentId:string;
  readonly doctorMembershipId:string;
  readonly patientId:string;
}

export function prepareDoctorHandoff(
  receipt:AppointmentCandidateReceipt, session:StaffSession,
):DoctorHandoff {
  if(!session||!valid(session.tenantId)
    ||!session.permissions.includes('APPOINTMENT_CHECK_IN')
    ||receipt?.stage!=='CHECKED_IN'
    ||receipt.tenantId!==session.tenantId
    ||!valid(receipt.branchId)||!valid(receipt.patientId)
    ||!valid(receipt.appointmentId)||!valid(receipt.doctorMembershipId)
    ||!Number.isSafeInteger(receipt.revision)||receipt.revision<2)
    throw new Error('Doctor handoff requires an authorized checked-in appointment');
  return Object.freeze({
    tenantId:receipt.tenantId,branchId:receipt.branchId,
    patientId:receipt.patientId,appointmentId:receipt.appointmentId,
    doctorMembershipId:receipt.doctorMembershipId,
  });
}

/** Verify new doctor login; backend consultation-context and draft reads still gate access. */
export function doctorCanReceiveHandoff(
  handoff:DoctorHandoff,session:StaffSession,selectedBranch:string,
):boolean {
  return !!session && !!handoff
    && valid(handoff.tenantId)&&valid(handoff.branchId)
    && valid(handoff.patientId)&&valid(handoff.appointmentId)
    && valid(handoff.doctorMembershipId)
    && session.tenantId===handoff.tenantId
    && session.membershipId===handoff.doctorMembershipId
    && selectedBranch===handoff.branchId
    && session.permissions.includes('OPD_DRAFT_WRITE');
}
