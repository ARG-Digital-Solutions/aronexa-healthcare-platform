/**
 * In-memory staff authentication adapter for OPD integration.
 *
 * The server establishes identity, tenant, roles and permissions. This client
 * never provisions a user, grants access or stores any credential in browser
 * storage/cookies. No raw response bodies or identifying session values appear
 * in errors. Server authorization is mandatory on every clinical operation.
 */
export interface StaffSession {
  readonly tenantId: string;
  readonly userId: string;
  readonly membershipId: string;
  readonly username: string;
  readonly roles: readonly string[];
  readonly permissions: readonly string[];
  /** Secret: retain only in the active in-memory workflow. Never display/log/store. */
  readonly accessToken: string;
}

export type StaffSessionErrorCode =
  'INVALID_LOGIN' | 'LOGIN_DENIED' | 'SESSION_EXPIRED' |
  'PASSWORD_CHANGE_REQUIRED' | 'SESSION_UNVERIFIED' | 'NETWORK_UNAVAILABLE';

const messages: Record<StaffSessionErrorCode, string> = {
  INVALID_LOGIN: 'Enter a valid clinic code, username and password.',
  LOGIN_DENIED: 'Sign-in was not accepted. Check your clinic account.',
  SESSION_EXPIRED: 'Your session is no longer active. Sign in again.',
  PASSWORD_CHANGE_REQUIRED: 'Change your initial password using the approved staff procedure before continuing.',
  SESSION_UNVERIFIED: 'The server could not verify this clinic session.',
  NETWORK_UNAVAILABLE: 'The clinic server could not be reached. Try again.',
};

export class StaffSessionError extends Error {
  readonly code: StaffSessionErrorCode;
  constructor(code: StaffSessionErrorCode) {
    super(messages[code]);
    this.name = 'StaffSessionError';
    this.code = code;
  }
}

type Transport = (url: string, options: RequestInit) => Promise<Response>;
const defaultTransport: Transport = (url, options) => fetch(url, options);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const zero = '00000000-0000-0000-0000-000000000000';
const tokenPattern = /^[A-Za-z0-9_-]{43}$/;
const isId = (value: unknown): value is string =>
  typeof value === 'string' && uuid.test(value) && value.toLowerCase() !== zero;
const isStrings = (v: unknown): v is string[] =>
  Array.isArray(v) && v.length <= 256 &&
  v.every(x => typeof x === 'string' && /^[A-Z][A-Z0-9_]{0,79}$/.test(x));

function request(method: 'GET' | 'POST', token?: string, body?: string): RequestInit {
  return {
    method, mode: 'same-origin', credentials: 'omit', redirect: 'error', cache: 'no-store',
    headers: {
      Accept: 'application/json',
      ...(body === undefined ? {} : {'Content-Type': 'application/json'}),
      ...(token === undefined ? {} : {Authorization: 'Bearer ' + token}),
    },
    ...(body === undefined ? {} : {body}),
    signal: AbortSignal.timeout(15_000),
  };
}

/** Validate /auth/me after login, never trust a token alone as tenant scope. */
export async function verifyStaffSession(
  accessToken: string, transport: Transport = defaultTransport,
): Promise<StaffSession> {
  if (typeof accessToken !== 'string' || !tokenPattern.test(accessToken)) {
    throw new StaffSessionError('SESSION_EXPIRED');
  }
  let response: Response;
  try {
    response = await transport('/api/v1/auth/me', request('GET', accessToken));
  } catch {
    throw new StaffSessionError('NETWORK_UNAVAILABLE');
  }
  if (response.status === 401) throw new StaffSessionError('SESSION_EXPIRED');
  if (response.status !== 200) throw new StaffSessionError('SESSION_UNVERIFIED');
  let data: unknown;
  try { data = await response.json(); }
  catch { throw new StaffSessionError('SESSION_UNVERIFIED'); }
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    throw new StaffSessionError('SESSION_UNVERIFIED');
  }
  const member = data as Record<string, unknown>;
  if (!isId(member.tenantId) || !isId(member.userId) ||
      !isId(member.membershipId) || typeof member.username !== 'string' ||
      member.username.length < 3 || member.username.length > 120 ||
      !isStrings(member.roles) || !isStrings(member.permissions) ||
      typeof member.mustChangePassword !== 'boolean') {
    throw new StaffSessionError('SESSION_UNVERIFIED');
  }
  if (member.mustChangePassword) throw new StaffSessionError('PASSWORD_CHANGE_REQUIRED');
  return Object.freeze({
    tenantId: member.tenantId,
    userId: member.userId,
    membershipId: member.membershipId,
    username: member.username,
    roles: Object.freeze([...member.roles]),
    permissions: Object.freeze([...member.permissions]),
    accessToken,
  });
}

/**
 * Real /auth/login + /auth/me handshake, in memory only.
 * Never retries a password request, leaks response bodies or caches credentials.
 */
export async function signInStaff(
  login: Readonly<{tenantCode: string; username: string; password: string}>,
  transport: Transport = defaultTransport,
): Promise<StaffSession> {
  const tenantCode = login?.tenantCode?.trim().toLowerCase();
  const username = login?.username?.trim().toLowerCase();
  const password = login?.password;
  if (typeof tenantCode !== 'string' || !/^[a-z][a-z0-9_-]{1,47}$/.test(tenantCode) ||
      typeof username !== 'string' || username.length < 3 ||
      username.length > 120 || typeof password !== 'string' ||
      password.length < 1 || password.length > 256) {
    throw new StaffSessionError('INVALID_LOGIN');
  }
  let response: Response;
  try {
    response = await transport('/api/v1/auth/login',
      request('POST', undefined, JSON.stringify({tenantCode, username, password})));
  } catch {
    throw new StaffSessionError('NETWORK_UNAVAILABLE');
  }
  if (response.status === 401 || response.status === 403) {
    throw new StaffSessionError('LOGIN_DENIED');
  }
  if (response.status !== 200) throw new StaffSessionError('SESSION_UNVERIFIED');
  let payload: unknown;
  try { payload = await response.json(); }
  catch { throw new StaffSessionError('SESSION_UNVERIFIED'); }
  if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) {
    throw new StaffSessionError('SESSION_UNVERIFIED');
  }
  const result = payload as Record<string, unknown>;
  if (result.tokenType !== 'Bearer' ||
      typeof result.accessToken !== 'string' ||
      !tokenPattern.test(result.accessToken) ||
      typeof result.expiresInSeconds !== 'number' ||
      !Number.isInteger(result.expiresInSeconds) ||
      result.expiresInSeconds < 1 || result.expiresInSeconds > 3600 ||
      typeof result.mustChangePassword !== 'boolean') {
    throw new StaffSessionError('SESSION_UNVERIFIED');
  }
  if (result.mustChangePassword) {
    await revokeStaffToken(result.accessToken as string, transport);
    throw new StaffSessionError('PASSWORD_CHANGE_REQUIRED');
  }
  try {
    return await verifyStaffSession(result.accessToken as string, transport);
  } catch (failure) {
    await revokeStaffToken(result.accessToken as string, transport);
    throw failure instanceof StaffSessionError ? failure :
      new StaffSessionError('SESSION_UNVERIFIED');
  }
}

/** The caller must discard the session from UI state before calling logout. */
export async function revokeStaffToken(
  accessToken: string, transport: Transport = defaultTransport,
): Promise<void> {
  if (!tokenPattern.test(accessToken)) return;
  try {
    // Do not forward logout network errors or response text to user interfaces.
    await transport('/api/v1/auth/logout', request('POST', accessToken, '{}'));
  } catch {
    // A failed logout requires server-side token expiry/revocation monitoring.
  }
}

/**
 * Test-only role handoff boundary. Do not carry a role's routing hint into
 * another role until the server confirms token revocation by returning 401
 * to an authenticated /me read. No token or response body is logged.
 */
export async function revokeStaffTokenAndConfirm(
  accessToken:string,transport:Transport=defaultTransport,
):Promise<void>{
  if(!tokenPattern.test(accessToken))
    throw new StaffSessionError('SESSION_UNVERIFIED');
  try{
    const result=await transport('/api/v1/auth/logout',
      request('POST',accessToken,'{}'));
    if(result.status!==200&&result.status!==204)
      throw new StaffSessionError('SESSION_UNVERIFIED');
    const probe=await transport('/api/v1/auth/me',request('GET',accessToken));
    if(probe.status!==401)
      throw new StaffSessionError('SESSION_UNVERIFIED');
  }catch{
    throw new StaffSessionError('SESSION_UNVERIFIED');
  }
}
