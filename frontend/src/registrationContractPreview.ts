/**
 * P01-T07-INT-REG-02A — synthetic, pure contract preview ONLY.
 * No HTTP, credentials, persistence, patient identifiers, or live registration.
 * This is NOT wired into App.tsx and MUST NOT be used with actual patient data.
 */
import { localCalendarDate, normalizeIntake, validateIntake, type Intake } from "./receptionModel.ts";

export type ProposedRegistrationDto = Readonly<{
  displayName: string;
  birthDatePrecision: "UNKNOWN" | "EXACT";
  dateOfBirth: string | null;
}>;

export type ConversionResult =
  | Readonly<{ ok: true; proposedDto: ProposedRegistrationDto }>
  | Readonly<{ ok: false; reason: "INVALID_INTAKE" | "UNSUPPORTED_PHONE" | "UNSUPPORTED_PRECISION" }>;

/** Fail-closed translation of the frozen synthetic Reception model to the test-only C2 proposal. */
export function previewRegistrationDto(intake: Intake, today = localCalendarDate()): ConversionResult {
  // Runtime guard: JSON/untrusted callers are not constrained by TS types.
  if (intake.precision !== "FULL" && intake.precision !== "UNKNOWN") {
    return { ok: false, reason: "UNSUPPORTED_PRECISION" };
  }
  const normalized = normalizeIntake(intake);
  // Contact storage and consent are NOT in the C2 test contract. Never silently discard a number.
  if (normalized.phone.length !== 0) return { ok: false, reason: "UNSUPPORTED_PHONE" };
  if (Object.keys(validateIntake(normalized, today)).length !== 0) {
    return { ok: false, reason: "INVALID_INTAKE" };
  }
  return {
    ok: true,
    proposedDto: Object.freeze({
      displayName: normalized.name,
      birthDatePrecision: normalized.precision === "FULL" ? "EXACT" : "UNKNOWN",
      dateOfBirth: normalized.precision === "FULL" ? normalized.dob : null,
    }),
  };
}

/**
 * Generic test-response classification for future design review, NOT a network or UI adapter.
 * No patient identity, candidate information, clinical data or backend response body is accepted.
 */
export type ProposedOutcome =
  | "CREATED_NOT_LINKED"
  | "REPLAYED_NOT_LINKED"
  | "DUPLICATE_REVIEW_BLOCKED"
  | "IDEMPOTENCY_CONFLICT"
  | "AUTH_REQUIRED"
  | "PERMISSION_DENIED"
  | "INVALID_REQUEST"
  | "PAYLOAD_TOO_LARGE"
  | "RATE_LIMITED"
  | "UNAVAILABLE"
  | "UNKNOWN_BLOCKED";

export type SyntheticOutcome = Readonly<{
  outcome: ProposedOutcome;
  canUpdateReceptionQueue: false;
  canDisplayCandidateIdentities: false;
}>;

export function previewSyntheticOutcome(status: number, code?: string | null): SyntheticOutcome {
  let outcome: ProposedOutcome = "UNKNOWN_BLOCKED";
  switch (status) {
    case 201: outcome = "CREATED_NOT_LINKED"; break;
    case 200: outcome = "REPLAYED_NOT_LINKED"; break;
    case 409:
      outcome = code === "DUPLICATE_REVIEW_REQUIRED"
        ? "DUPLICATE_REVIEW_BLOCKED"
        : code === "IDEMPOTENCY_CONFLICT" ? "IDEMPOTENCY_CONFLICT" : "UNKNOWN_BLOCKED";
      break;
    case 400: outcome = "INVALID_REQUEST"; break;
    case 401: outcome = "AUTH_REQUIRED"; break;
    case 403: outcome = "PERMISSION_DENIED"; break;
    case 413: outcome = "PAYLOAD_TOO_LARGE"; break;
    case 429: outcome = "RATE_LIMITED"; break;
    case 503: outcome = "UNAVAILABLE"; break;
  }
  // Even 200/201 must not write to the synthetic session queue: server IDs have no approved UI mapping.
  return Object.freeze({outcome, canUpdateReceptionQueue: false, canDisplayCandidateIdentities: false});
}
