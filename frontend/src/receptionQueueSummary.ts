import type {AppointmentCandidateReceipt} from './appointmentCandidateClient.ts';

/**
 * Counts ONLY the currently fetched, server-verified appointment records.
 * This is UI presentation, not a clinical queue priority or entitlement check.
 * Never cache these numbers across branch/date/session changes.
 */
export interface ReceptionQueueSummary {
  readonly total: number;
  readonly booked: number;
  readonly checkedIn: number;
}

export function summarizeReceptionQueue(
  rows: readonly AppointmentCandidateReceipt[],
): ReceptionQueueSummary {
  let booked = 0;
  let checkedIn = 0;
  for (const row of rows) {
    if (row.stage === 'BOOKED') booked++;
    else if (row.stage === 'CHECKED_IN') checkedIn++;
    else throw new Error('Unsupported appointment stage in active queue');
  }
  return Object.freeze({total: rows.length, booked, checkedIn});
}
