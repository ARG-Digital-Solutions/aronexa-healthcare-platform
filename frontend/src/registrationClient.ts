import {normalizeIntake, validateIntake} from './receptionModel.ts';
import type {Intake} from './receptionModel.ts';

export interface RegistrationReceipt {
  readonly patientId: string;
  readonly registrationId: string;
  readonly mrn: string;
  readonly replayed: boolean;
}

const messages = {
  INVALID_REGISTRATION_REQUEST: 'Check the patient name and birth date.',
  CONTACT_NOT_SUPPORTED: 'Phone storage is not available in this registration contract.',
  SESSION_EXPIRED: 'Sign in again before registering a patient.',
  REGISTRATION_DENIED: 'Registration is not available for this session or branch.',
  DUPLICATE_REVIEW_REQUIRED: 'A possible duplicate requires authorized review. No patient has been selected.',
  IDEMPOTENCY_CONFLICT: 'This submission conflicts with an earlier request. Review it before continuing.',
  REGISTRATION_CONFLICT: 'Registration requires review before continuing.',
  RATE_LIMITED: 'Too many attempts. Wait before retrying this submission.',
  OUTCOME_UNKNOWN: 'The save could not be confirmed. Retry this same submission to check its result.',
} as const;
export type RegistrationErrorCode = keyof typeof messages;

export class RegistrationError extends Error {
  readonly code: RegistrationErrorCode;
  constructor(code: RegistrationErrorCode) {
    super(messages[code]);
    this.name = 'RegistrationError';
    this.code = code;
  }
}

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
type Transport = (url: string, options: RequestInit) => Promise<Response>;

/**
 * One immutable, session-bound submission. Keep this object for explicit retries
 * after an uncertain result; never generate a new key merely because fetch failed.
 * Memory only. No token/intake storage, logging, automatic retry or demo fallback.
 * The server remains authoritative for branch, role, module and session checks.
 * Currently callable only against the existing disposable registration profile.
 */
export function createRegistrationAttempt(
  input: Intake, branchId: string, accessToken: string,
  transport: Transport = (url, options) => fetch(url, options),
): {submit: () => Promise<RegistrationReceipt>} {
  if (!uuid.test(branchId) || !/^[A-Za-z0-9_-]{43}$/.test(accessToken)) {
    throw new RegistrationError('REGISTRATION_DENIED');
  }
  if (input.phone.trim()) throw new RegistrationError('CONTACT_NOT_SUPPORTED');
  if (!['FULL', 'UNKNOWN'].includes(input.precision) || /[\u0000-\u001f\u007f-\u009f]/.test(input.name)
      || Object.keys(validateIntake(input, new Date().toISOString().slice(0, 10))).length) {
    throw new RegistrationError('INVALID_REGISTRATION_REQUEST');
  }
  const intake = normalizeIntake(input);
  const body = JSON.stringify({
    displayName: intake.name,
    birthDatePrecision: intake.precision === 'FULL' ? 'EXACT' : 'UNKNOWN',
    dateOfBirth: intake.precision === 'FULL' ? intake.dob : null,
  });
  const key = crypto.randomUUID();
  const path = `/api/v1/branches/${branchId}/patient-registrations`;
  let pending: Promise<RegistrationReceipt> | undefined;
  let confirmed: RegistrationReceipt | undefined;

  async function send(): Promise<RegistrationReceipt> {
    try {
      const response = await transport(path, {
        method: 'POST', mode: 'same-origin', credentials: 'omit', redirect: 'error', cache: 'no-store',
        headers: {'Content-Type': 'application/json', Accept: 'application/json',
          Authorization: `Bearer ${accessToken}`, 'Idempotency-Key': key},
        body, signal: AbortSignal.timeout(15_000),
      });
      if (response.status === 401) throw new RegistrationError('SESSION_EXPIRED');
      if (response.status === 403) throw new RegistrationError('REGISTRATION_DENIED');
      if (response.status === 400) throw new RegistrationError('INVALID_REGISTRATION_REQUEST');
      if (response.status === 429) throw new RegistrationError('RATE_LIMITED');
      if (response.status === 409) {
        const failure = await response.json().catch(() => null);
        const code = failure?.code;
        throw new RegistrationError(code === 'DUPLICATE_REVIEW_REQUIRED' || code === 'IDEMPOTENCY_CONFLICT'
          ? code : 'REGISTRATION_CONFLICT');
      }
      if (response.status !== 200 && response.status !== 201) throw new RegistrationError('OUTCOME_UNKNOWN');
      const value: unknown = await response.json();
      if (!value || typeof value !== 'object') throw new RegistrationError('OUTCOME_UNKNOWN');
      const receipt = value as Record<string, unknown>;
      if (typeof receipt.patientId !== 'string' || !uuid.test(receipt.patientId)
          || typeof receipt.registrationId !== 'string' || !uuid.test(receipt.registrationId)
          || typeof receipt.mrn !== 'string' || !/^PAT-[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{12}$/.test(receipt.mrn)
          || receipt.replayed !== (response.status === 200)) throw new RegistrationError('OUTCOME_UNKNOWN');
      return Object.freeze({patientId: receipt.patientId, registrationId: receipt.registrationId,
        mrn: receipt.mrn, replayed: receipt.replayed});
    } catch (error) {
      // Never propagate backend text, URLs, credentials or patient details.
      throw error instanceof RegistrationError ? error : new RegistrationError('OUTCOME_UNKNOWN');
    }
  }

  return Object.freeze({submit(): Promise<RegistrationReceipt> {
    if (confirmed) return Promise.resolve(confirmed);
    if (!pending) pending = send().then(result => {
      confirmed = result;
      return result;
    }).finally(() => { pending = undefined; });
    return pending;
  }});
}
