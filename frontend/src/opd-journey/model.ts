/**
 * COMM-V1-T06: fictional, in-memory OPD journey contract.
 * This is NOT production authentication, persistence, clinical authorization,
 * prescription finalization, invoicing or a real workflow integration.
 */
export type DemoRole = 'receptionist' | 'doctor' | 'billing';
export type Stage = 'INTAKE_REVIEW' | 'REGISTERED' | 'BOOKED' | 'CHECKED_IN'
  | 'CONSULT_DRAFT' | 'RX_DRAFT' | 'BILL_DRAFT' | 'REPORT_PREVIEW';
export type Action = 'CONFIRM_DUPLICATE_REVIEW' | 'BOOK_SLOT' | 'CHECK_IN'
  | 'SAVE_CONSULT_DRAFT' | 'SAVE_RX_DRAFT' | 'PREVIEW_BILL' | 'PREVIEW_REPORT';

export interface SyntheticContext {
  readonly tenantId: string;
  readonly branchId: string;
  readonly patientId: string;
  readonly assignedDoctorId: string;
  readonly encounterId: string;
}
export interface DemoActor {
  readonly role: DemoRole;
  readonly actorId: string;
  readonly tenantId: string;
  readonly branchId: string;
  readonly patientId: string;
}
export interface JourneyEvent {
  readonly id: string;
  readonly action: Action;
  readonly actorId: string;
  readonly from: Stage;
  readonly to: Stage;
  readonly revision: number;
}
export interface JourneyState {
  readonly context: SyntheticContext;
  readonly stage: Stage;
  readonly revision: number;
  readonly history: readonly JourneyEvent[];
  readonly syntheticOnly: true;
}
export interface Transition {
  readonly from: Stage;
  readonly to: Stage;
  readonly role: DemoRole;
  readonly buttonLabel: string;
}
export const RULES: Readonly<Record<Action, Transition>> = {
  CONFIRM_DUPLICATE_REVIEW: {
    from: 'INTAKE_REVIEW', to: 'REGISTERED', role: 'receptionist',
    buttonLabel: 'Confirm intake review',
  },
  BOOK_SLOT: { from: 'REGISTERED', to: 'BOOKED', role: 'receptionist', buttonLabel: 'Book fictional slot' },
  CHECK_IN: { from: 'BOOKED', to: 'CHECKED_IN', role: 'receptionist', buttonLabel: 'Check in patient' },
  SAVE_CONSULT_DRAFT: { from: 'CHECKED_IN', to: 'CONSULT_DRAFT', role: 'doctor', buttonLabel: 'Save fictional consultation draft' },
  SAVE_RX_DRAFT: { from: 'CONSULT_DRAFT', to: 'RX_DRAFT', role: 'doctor', buttonLabel: 'Review fictional Rx draft' },
  PREVIEW_BILL: { from: 'RX_DRAFT', to: 'BILL_DRAFT', role: 'billing', buttonLabel: 'Preview fictional bill' },
  PREVIEW_REPORT: { from: 'BILL_DRAFT', to: 'REPORT_PREVIEW', role: 'billing', buttonLabel: 'View sample daily report' },
};
export const ORDER: readonly Stage[] = [
  'INTAKE_REVIEW', 'REGISTERED', 'BOOKED', 'CHECKED_IN',
  'CONSULT_DRAFT', 'RX_DRAFT', 'BILL_DRAFT', 'REPORT_PREVIEW',
];
export const LABELS: Readonly<Record<Stage, string>> = {
  INTAKE_REVIEW: 'Duplicate review',
  REGISTERED: 'Registration reviewed',
  BOOKED: 'Appointment booked',
  CHECKED_IN: 'Checked in',
  CONSULT_DRAFT: 'Consultation draft',
  RX_DRAFT: 'Prescription draft preview',
  BILL_DRAFT: 'Billing draft preview',
  REPORT_PREVIEW: 'Daily report preview',
};
export const FICTIONAL_CONTEXT: SyntheticContext = {
  tenantId: 'SYN-TENANT-01',
  branchId: 'SYN-BRANCH-01',
  patientId: 'SYN-PATIENT-01',
  assignedDoctorId: 'SYN-DOCTOR-01',
  encounterId: 'SYN-ENCOUNTER-01',
};
export const ACTORS: Readonly<Record<DemoRole, DemoActor>> = {
  receptionist: { role: 'receptionist', actorId: 'SYN-RECEPTION-01', tenantId: FICTIONAL_CONTEXT.tenantId, branchId: FICTIONAL_CONTEXT.branchId, patientId: FICTIONAL_CONTEXT.patientId },
  doctor: { role: 'doctor', actorId: FICTIONAL_CONTEXT.assignedDoctorId, tenantId: FICTIONAL_CONTEXT.tenantId, branchId: FICTIONAL_CONTEXT.branchId, patientId: FICTIONAL_CONTEXT.patientId },
  billing: { role: 'billing', actorId: 'SYN-BILLING-01', tenantId: FICTIONAL_CONTEXT.tenantId, branchId: FICTIONAL_CONTEXT.branchId, patientId: FICTIONAL_CONTEXT.patientId },
};

export function createJourney(context: SyntheticContext = FICTIONAL_CONTEXT): JourneyState {
  if (!Object.values(context).every(value => typeof value === 'string' && /^SYN-[A-Z0-9-]{3,60}$/.test(value))) {
    throw new Error('Only explicit synthetic context is supported.');
  }
  return { context: { ...context }, stage: 'INTAKE_REVIEW', revision: 0, history: [], syntheticOnly: true };
}
export function nextAction(stage: Stage): Action | null {
  return (Object.keys(RULES) as Action[]).find(action => RULES[action].from === stage) ?? null;
}
function clone(state: JourneyState): JourneyState {
  return { ...state, context: { ...state.context }, history: state.history.map(event => ({ ...event })) };
}
function requireScope(state: JourneyState, actor: DemoActor): void {
  if (state.syntheticOnly !== true || !state.context.patientId.startsWith('SYN-')
    || actor.tenantId !== state.context.tenantId
    || actor.branchId !== state.context.branchId
    || actor.patientId !== state.context.patientId
    || !actor.actorId.startsWith('SYN-')) {
    throw new Error('Synthetic patient or organization access denied.');
  }
  if (actor.role === 'doctor' && actor.actorId !== state.context.assignedDoctorId) {
    throw new Error('Assigned doctor required.');
  }
}
/** Pure simulated transition. Backend MUST implement all authorization separately. */
export function transition(
  state: JourneyState, actor: DemoActor, action: Action,
  eventId: string, expectedRevision: number,
): JourneyState {
  requireScope(state, actor);
  if (!Object.prototype.hasOwnProperty.call(RULES, action)) throw new Error('Unsupported journey action.');
  const rule = RULES[action];
  if (rule.role !== actor.role) throw new Error('Role not permitted for this step.');
  if (!/^SYN-EVT-[A-Z0-9-]{1,36}$/.test(eventId)) throw new Error('Invalid synthetic event ID.');
  const replay = state.history.find(event => event.id === eventId);
  if (replay) {
    if (replay.action !== action || replay.actorId !== actor.actorId) throw new Error('Event key reused for a different request.');
    return clone(state);
  }
  if (!Number.isSafeInteger(expectedRevision) || expectedRevision !== state.revision) {
    throw new Error('Stale journey revision.');
  }
  if (state.stage !== rule.from) throw new Error('Action out of sequence.');
  const event: JourneyEvent = {
    id: eventId, action, actorId: actor.actorId, from: state.stage,
    to: rule.to, revision: state.revision + 1,
  };
  return {
    context: { ...state.context }, stage: rule.to, revision: event.revision,
    history: [...state.history.map(item => ({ ...item })), event], syntheticOnly: true,
  };
}
