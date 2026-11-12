/**
 * Read the minimal branch-authorized patient/registration UUID mapping.
 *
 * This does not register patients. The server performs live membership, module,
 * permission and SQL RLS checks and audits successful reads. Do not use this
 * with synthetic demo IDs or as a replacement for login/session management.
 * Token and patient details are deliberately never stored or logged.
 */
export interface RegistrationReference {
  readonly tenantId: string;
  readonly branchId: string;
  readonly registrationId: string;
  readonly patientId: string;
}
export type ReferenceReadCode =
  'SESSION_EXPIRED' | 'REGISTRATION_READ_DENIED' |
  'REGISTRATION_NOT_FOUND' | 'OUTCOME_UNKNOWN';

const messages: Record<ReferenceReadCode, string> = {
  SESSION_EXPIRED: 'Sign in again before opening this patient.',
  REGISTRATION_READ_DENIED: 'This registration is unavailable to your current role or branch.',
  REGISTRATION_NOT_FOUND: 'The registration could not be found in your authorized branch.',
  OUTCOME_UNKNOWN: 'The registration could not be verified. Try again.',
};

export class RegistrationReferenceError extends Error {
  readonly code: ReferenceReadCode;
  constructor(code: ReferenceReadCode) {
    super(messages[code]);
    this.name = 'RegistrationReferenceError';
    this.code = code;
  }
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const valid = (value: unknown): value is string =>
  typeof value === 'string' && uuid.test(value) &&
  value.toLowerCase() !== '00000000-0000-0000-0000-000000000000';
type Transport = (url: string, options: RequestInit) => Promise<Response>;

export async function loadAuthorizedRegistrationReference(
  context: Readonly<{tenantId: string; branchId: string; registrationId: string}>,
  accessToken: string,
  transport: Transport = (url, options) => fetch(url, options),
): Promise<RegistrationReference> {
  if (!valid(context?.tenantId) || !valid(context?.branchId) ||
      !valid(context?.registrationId) ||
      typeof accessToken !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(accessToken)) {
    throw new RegistrationReferenceError('REGISTRATION_READ_DENIED');
  }
  const path = '/api/v1/branches/' + context.branchId +
      '/patient-registrations/' + context.registrationId;
  try {
    const response = await transport(path, {
      method: 'GET',
      mode: 'same-origin',
      credentials: 'omit',
      redirect: 'error',
      cache: 'no-store',
      headers: {Accept: 'application/json', Authorization: 'Bearer ' + accessToken},
      signal: AbortSignal.timeout(10_000),
    });
    if (response.status === 401) throw new RegistrationReferenceError('SESSION_EXPIRED');
    if (response.status === 403) throw new RegistrationReferenceError('REGISTRATION_READ_DENIED');
    if (response.status === 404) throw new RegistrationReferenceError('REGISTRATION_NOT_FOUND');
    if (response.status !== 200) throw new RegistrationReferenceError('OUTCOME_UNKNOWN');
    const value: unknown = await response.json();
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      throw new RegistrationReferenceError('OUTCOME_UNKNOWN');
    }
    const record = value as Record<string, unknown>;
    if (!valid(record.tenantId) || record.tenantId !== context.tenantId ||
        !valid(record.branchId) || record.branchId !== context.branchId ||
        !valid(record.registrationId) || record.registrationId !== context.registrationId ||
        !valid(record.patientId)) {
      throw new RegistrationReferenceError('OUTCOME_UNKNOWN');
    }
    return Object.freeze({
      tenantId: record.tenantId,
      branchId: record.branchId,
      registrationId: record.registrationId,
      patientId: record.patientId,
    });
  } catch (failure) {
    // Never forward API error bodies, URLs, auth headers, tokens or patient data.
    throw failure instanceof RegistrationReferenceError
      ? failure : new RegistrationReferenceError('OUTCOME_UNKNOWN');
  }
}
