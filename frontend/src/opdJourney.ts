import type {RegistrationReceipt} from './registrationClient.ts';

/**
 * UI-only reference continuity for the OPD V1 journey.
 *
 * Receipts must come from a separately authenticated, server-authorized API.
 * These checks NEVER grant permission or prove database persistence; the
 * backend must enforce session, tenant, branch, doctor, patient-object,
 * module, transaction, audit and replay rules on EVERY operation.
 *
 * No credentials, demographics, clinical content or payment details are stored.
 * This module deliberately performs no fetch or browser storage operations.
 */
export type OpdStage =
  'REGISTERED' | 'BOOKED' | 'CHECKED_IN' | 'CONSULTED' | 'PRESCRIBED' | 'BILLED';

export interface OpdJourney {
  readonly stage: OpdStage;
  readonly tenantId: string;
  readonly branchId: string;
  readonly patientId: string;
  readonly registrationId: string;
  readonly mrn: string;
  readonly appointmentId?: string;
  readonly encounterId?: string;
  readonly prescriptionId?: string;
  readonly invoiceId?: string;
  readonly encounterRevision?: number;
  readonly prescriptionVersion?: number;
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const mrn = /^PAT-[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{12}$/;
const stages: readonly OpdStage[] =
  ['REGISTERED', 'BOOKED', 'CHECKED_IN', 'CONSULTED', 'PRESCRIBED', 'BILLED'];
const identifiers = ['appointmentId', 'encounterId', 'prescriptionId', 'invoiceId'] as const;
type Identifier = typeof identifiers[number];
type Receipt = Readonly<Record<string, unknown>>;

export class OpdLinkError extends Error {
  constructor() {
    // Do not echo untrusted receipt values or reveal clinical identifiers.
    super('The clinical workflow reference could not be verified. Refresh from the authorized server.');
    this.name = 'OpdLinkError';
  }
}

function validId(value: unknown): value is string {
  return typeof value === 'string' && uuid.test(value) && value !== '00000000-0000-0000-0000-000000000000';
}
function check(value: unknown): asserts value {
  if (!value) throw new OpdLinkError();
}
function object(value: unknown): Receipt {
  check(value !== null && typeof value === 'object' && !Array.isArray(value));
  return value as Receipt;
}
function allDistinct(values: readonly (string | undefined)[]): boolean {
  const present = values.filter((value): value is string => value !== undefined);
  return new Set(present.map(value => value.toLowerCase())).size === present.length;
}

/** Called after a successful authorized registration receipt, never by demo registration. */
export function startOpdJourney(
  authorizedContext: Readonly<{tenantId: string; branchId: string}>,
  registration: RegistrationReceipt,
): OpdJourney {
  check(validId(authorizedContext?.tenantId) && validId(authorizedContext?.branchId));
  check(validId(registration?.patientId) && validId(registration?.registrationId));
  check(typeof registration.mrn === 'string' && mrn.test(registration.mrn));
  check(allDistinct([authorizedContext.tenantId, authorizedContext.branchId,
    registration.patientId, registration.registrationId]));
  check(registration.replayed === true || registration.replayed === false);
  return Object.freeze({
    stage: 'REGISTERED', tenantId: authorizedContext.tenantId,
    branchId: authorizedContext.branchId, patientId: registration.patientId,
    registrationId: registration.registrationId, mrn: registration.mrn,
  });
}

/**
 * Advance using a fresh server response. Each response MUST carry:
 * stage, tenantId, branchId, patientId and registrationId; prior linked
 * appointment/encounter/etc IDs; and the new stage's ID.
 * CHECKED_IN reuses the same appointmentId.
 * CONSUL­TED and PRESCRIBED carry their server revision/version.
 *
 * The UI may display the next stage only after this structural check.
 * A reload must re-fetch the journey using authorized backend endpoints:
 * this state is intentionally not durable.
 */
export function acceptOpdReceipt(current: OpdJourney, response: unknown): OpdJourney {
  const receipt = object(response);
  check(stages.includes(current?.stage));
  const index = stages.indexOf(current.stage);
  check(index >= 0 && index < stages.length - 1);
  const nextStage = stages[index + 1];
  check(receipt.stage === nextStage);
  for (const field of ['tenantId', 'branchId', 'patientId', 'registrationId'] as const) {
    check(validId(receipt[field]) && receipt[field] === current[field]);
  }

  const newId: Identifier | undefined = nextStage === 'BOOKED' ? 'appointmentId'
    : nextStage === 'CONSULTED' ? 'encounterId'
    : nextStage === 'PRESCRIBED' ? 'prescriptionId'
    : nextStage === 'BILLED' ? 'invoiceId' : undefined;
  const updated: Record<string, unknown> = {...current, stage: nextStage};

  for (const field of identifiers) {
    const prior = current[field];
    if (field === newId) {
      check(prior === undefined && validId(receipt[field]));
      updated[field] = receipt[field];
    } else if (prior !== undefined) {
      check(validId(prior) && receipt[field] === prior);
    } else {
      // No premature or fictional future-stage identity may appear.
      check(receipt[field] === undefined);
    }
  }
  if (nextStage === 'CHECKED_IN') {
    check(validId(current.appointmentId));
  }
  if (newId) {
    check(allDistinct([current.tenantId, current.branchId, current.patientId,
      current.registrationId, current.appointmentId, current.encounterId,
      current.prescriptionId, current.invoiceId, updated[newId] as string]));
  }
  if (nextStage === 'CONSULTED') {
    check(Number.isSafeInteger(receipt.encounterRevision) &&
      (receipt.encounterRevision as number) >= 1);
    updated.encounterRevision = receipt.encounterRevision;
  } else {
    check(receipt.encounterRevision === undefined ||
      receipt.encounterRevision === current.encounterRevision);
  }
  if (nextStage === 'PRESCRIBED') {
    check(Number.isSafeInteger(receipt.prescriptionVersion) &&
      (receipt.prescriptionVersion as number) >= 1);
    updated.prescriptionVersion = receipt.prescriptionVersion;
  } else {
    check(receipt.prescriptionVersion === undefined ||
      receipt.prescriptionVersion === current.prescriptionVersion);
  }
  return Object.freeze(updated) as unknown as OpdJourney;
}
