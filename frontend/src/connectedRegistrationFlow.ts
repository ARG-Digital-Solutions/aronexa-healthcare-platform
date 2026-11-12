import type {Intake} from './receptionModel.ts';
import {normalizeIntake, validateIntake} from './receptionModel.ts';
import {createRegistrationAttempt, RegistrationError} from './registrationClient.ts';
import {loadAuthorizedRegistrationReference} from './registrationReferenceClient.ts';
import {startOpdJourney, type OpdJourney} from './opdJourney.ts';
import type {StaffSession} from './staffSessionClient.ts';

/**
 * Explicitly separate integration flow; never import demoRegistration,
 * DEMO_BRANCH, seedPatients or appointments. The normal Reception App.tsx
 * remains synthetic and cannot silently fall back to demo on server errors.
 *
 * Not a substitute for server-side authorizations or an approved production
 * registration migration. The prod POST route is still denied.
 */
type Transport = (url: string, options: RequestInit) => Promise<Response>;
const defaultTransport: Transport = (url, options) => fetch(url, options);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const bearer = /^[A-Za-z0-9_-]{43}$/;

export type FlowStatus =
  'IDLE' | 'REVIEW' | 'SAVING' | 'UNCERTAIN' | 'REGISTERED';

export interface ReviewedRegistration {
  readonly displayName: string;
  readonly dateOfBirth: string | null;
  readonly branchId: string;
}

/** Does not contain a token or any patient demographic field. */
export interface FlowSnapshot {
  readonly status: FlowStatus;
  readonly journey: OpdJourney | null;
  readonly lastError: string | null;
}

export class ConnectedFlowError extends Error {
  readonly code: 'NOT_AUTHORIZED' | 'REVIEW_REQUIRED' | 'UNRESOLVED_SUBMISSION';
  constructor(code: ConnectedFlowError['code']) {
    super(code === 'NOT_AUTHORIZED'
      ? 'This staff account is not authorized for patient registration in this clinic.'
      : code === 'REVIEW_REQUIRED'
        ? 'Review and confirm the registration before saving.'
        : 'The previous save is unresolved. Retry that same submission before starting another.');
    this.name = 'ConnectedFlowError';
    this.code = code;
  }
}

/**
 * One active staff session and ONE pending registration. A lost HTTP response
 * must be retried on the SAME object (same idempotency key and payload).
 * Cannot prepare a different patient while the prior write is uncertain.
 * No browser storage, login credentials or backend error payloads.
 */
export function createConnectedRegistrationFlow(
  session: StaffSession, branchId: string,
  transport: Transport = defaultTransport,
) {
  if (!session || !uuid.test(session.tenantId) ||
      !bearer.test(session.accessToken) ||
      !session.permissions.includes('PATIENT_REGISTER') ||
      !uuid.test(branchId)) {
    throw new ConnectedFlowError('NOT_AUTHORIZED');
  }

  let status: FlowStatus = 'IDLE';
  let lastError: string | null = null;
  let journey: OpdJourney | null = null;
  let operation: ReturnType<typeof createRegistrationAttempt> | null = null;
  let reviewed: ReviewedRegistration | null = null;
  let inflight: Promise<OpdJourney> | null = null;

  function snapshot(): FlowSnapshot {
    return Object.freeze({status, journey, lastError});
  }

  /** Local review only. Never searches or compares demo records. */
  function prepare(input: Intake): ReviewedRegistration {
    if (status === 'UNCERTAIN' || status === 'SAVING') {
      throw new ConnectedFlowError('UNRESOLVED_SUBMISSION');
    }
    if (status === 'REVIEW') {
      // Changing input invalidates the old attempt and its key BEFORE any POST.
      operation = null;
    }
    if (status === 'REGISTERED') {
      throw new ConnectedFlowError('REVIEW_REQUIRED');
    }
    if (!input || Object.keys(validateIntake(input)).length ||
        typeof input.phone !== 'string' || input.phone.trim()) {
      throw new RegistrationError(input?.phone?.trim()
        ? 'CONTACT_NOT_SUPPORTED' : 'INVALID_REGISTRATION_REQUEST');
    }
    const normalized = normalizeIntake(input);
    operation = createRegistrationAttempt(normalized, branchId, session.accessToken, transport);
    reviewed = Object.freeze({
      displayName: normalized.name,
      dateOfBirth: normalized.precision === 'FULL' ? normalized.dob : null,
      branchId,
    });
    journey = null;
    status = 'REVIEW';
    lastError = null;
    return reviewed;
  }

  /** Explicit confirmation only; never automatic retries. */
  function submitReviewed(): Promise<OpdJourney> {
    if (inflight) return inflight;
    if (!operation || !reviewed ||
        (status !== 'REVIEW' && status !== 'UNCERTAIN')) {
      return Promise.reject(new ConnectedFlowError('REVIEW_REQUIRED'));
    }
    status = 'SAVING';
    lastError = null;
    inflight = operation.submit().then(receipt => {
      const confirmed = startOpdJourney(
        {tenantId: session.tenantId, branchId}, receipt);
      journey = confirmed;
      status = 'REGISTERED';
      return confirmed;
    }).catch((failure: unknown) => {
      lastError = failure instanceof RegistrationError ? failure.code :
        'OUTCOME_UNKNOWN';
      // Do not replace an uncertain submission with a new key, even on 409.
      status = 'UNCERTAIN';
      throw failure;
    }).finally(() => {inflight = null;});
    return inflight;
  }

  /** When no write was ever sent, editing is safe. */
  function cancelReview(): void {
    if (status !== 'REVIEW') throw new ConnectedFlowError('UNRESOLVED_SUBMISSION');
    reviewed = null;
    operation = null;
    status = 'IDLE';
    lastError = null;
  }

  function nextPatient(): void {
    if (status !== 'REGISTERED') {
      throw new ConnectedFlowError('UNRESOLVED_SUBMISSION');
    }
    operation = null;
    reviewed = null;
    journey = null;
    status = 'IDLE';
    lastError = null;
  }

  /** Optional server read-back: only for staff with explicit read permission. */
  async function verifySavedRegistration(): Promise<boolean> {
    if (status !== 'REGISTERED' || !journey) {
      throw new ConnectedFlowError('REVIEW_REQUIRED');
    }
    if (!session.permissions.includes('PATIENT_REGISTRATION_READ')) {
      throw new ConnectedFlowError('NOT_AUTHORIZED');
    }
    const ref = await loadAuthorizedRegistrationReference({
      tenantId: session.tenantId, branchId,
      registrationId: journey.registrationId,
    }, session.accessToken, transport);
    return ref.patientId === journey.patientId;
  }

  return Object.freeze({
    snapshot, prepare, submitReviewed, cancelReview, nextPatient,
    verifySavedRegistration,
  });
}

export type ConnectedRegistrationFlow =
  ReturnType<typeof createConnectedRegistrationFlow>;
