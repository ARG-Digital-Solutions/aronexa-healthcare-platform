import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  ACTORS, FICTIONAL_CONTEXT, LABELS, ORDER, RULES,
  createJourney, nextAction, transition,
} from '../src/opd-journey/model.ts';

const start = () => createJourney();
const a = ACTORS;
const advance = (s, actor, action, id = 'SYN-EVT-' + (s.revision + 1), expectedRevision = s.revision) =>
  transition(s, actor, action, id, expectedRevision);
const registered = () => advance(start(), a.receptionist, 'CONFIRM_DUPLICATE_REVIEW');
const booked = () => advance(registered(), a.receptionist, 'BOOK_SLOT');
const checkedIn = () => advance(booked(), a.receptionist, 'CHECK_IN');
const consult = () => advance(checkedIn(), a.doctor, 'SAVE_CONSULT_DRAFT');
const rx = () => advance(consult(), a.doctor, 'SAVE_RX_DRAFT');
const bill = () => advance(rx(), a.billing, 'PREVIEW_BILL');

test('starts in fictional intake review with empty history', () => {
  const j = start(); assert.equal(j.stage, 'INTAKE_REVIEW'); assert.equal(j.revision, 0);
  assert.equal(j.history.length, 0); assert.equal(j.syntheticOnly, true);
});
test('preserves canonical synthetic identity', () => {
  const j = start(); assert.deepEqual(j.context, FICTIONAL_CONTEXT);
  assert.notStrictEqual(j.context, FICTIONAL_CONTEXT);
});
test('rejects real-looking context', () =>
  assert.throws(() => createJourney({ ...FICTIONAL_CONTEXT, patientId: 'REAL-123' }), /synthetic/));
test('rejects incomplete context', () =>
  assert.throws(() => createJourney({ patientId: FICTIONAL_CONTEXT.patientId }), /synthetic/));
test('rejects extra properties in synthetic context', () =>
  assert.throws(() => createJourney({ ...FICTIONAL_CONTEXT, unexpected: 'SYN-EXTRA-123' }), /synthetic/));
test('intake review transitions to registered', () =>
  assert.equal(registered().stage, 'REGISTERED'));
test('registration does not mutate prior state or history', () => {
  const original = start(); const result = advance(original, a.receptionist, 'CONFIRM_DUPLICATE_REVIEW');
  assert.equal(original.stage, 'INTAKE_REVIEW'); assert.equal(original.history.length, 0);
  assert.equal(result.history.length, 1); assert.equal(result.revision, 1);
  assert.notStrictEqual(result.context, original.context);
});
test('cannot book before duplicate review', () =>
  assert.throws(() => advance(start(), a.receptionist, 'BOOK_SLOT'), /out of sequence/));
test('cannot check in before booking', () =>
  assert.throws(() => advance(registered(), a.receptionist, 'CHECK_IN'), /out of sequence/));
test('receptionist cannot write doctor consultation step', () =>
  assert.throws(() => advance(checkedIn(), a.receptionist, 'SAVE_CONSULT_DRAFT'), /Role not permitted/));
test('doctor cannot check in at reception', () =>
  assert.throws(() => advance(booked(), a.doctor, 'CHECK_IN'), /Role not permitted/));
test('doctor cannot post bill preview', () =>
  assert.throws(() => advance(rx(), a.doctor, 'PREVIEW_BILL'), /Role not permitted/));
test('billing staff cannot write prescription draft', () =>
  assert.throws(() => advance(consult(), a.billing, 'SAVE_RX_DRAFT'), /Role not permitted/));
test('cross-tenant actor denied', () =>
  assert.throws(() => advance(start(), { ...a.receptionist, tenantId: 'SYN-OTHER-TENANT' }, 'CONFIRM_DUPLICATE_REVIEW'), /access denied/));
test('cross-branch actor denied', () =>
  assert.throws(() => advance(start(), { ...a.receptionist, branchId: 'SYN-OTHER-BRANCH' }, 'CONFIRM_DUPLICATE_REVIEW'), /access denied/));
test('different patient scope denied', () =>
  assert.throws(() => advance(start(), { ...a.receptionist, patientId: 'SYN-OTHER-PATIENT' }, 'CONFIRM_DUPLICATE_REVIEW'), /access denied/));
test('different doctor denied', () =>
  assert.throws(() => advance(checkedIn(), { ...a.doctor, actorId: 'SYN-OTHER-DOCTOR' }, 'SAVE_CONSULT_DRAFT'), /Assigned doctor required/));
test('non-synthetic actor identity denied', () =>
  assert.throws(() => advance(start(), { ...a.receptionist, actorId: 'REAL' }, 'CONFIRM_DUPLICATE_REVIEW'), /access denied/));
test('invalid event key denied', () =>
  assert.throws(() => advance(start(), a.receptionist, 'CONFIRM_DUPLICATE_REVIEW', 'event1'), /Invalid synthetic event ID/));
test('rejects stale model revision', () =>
  assert.throws(() => advance(registered(), a.receptionist, 'BOOK_SLOT', 'SYN-EVT-2', 0), /Stale journey revision/));
test('replay same event/action/actor is idempotent, with no new history', () => {
  const first = registered();
  const replay = transition(first, a.receptionist, 'CONFIRM_DUPLICATE_REVIEW', 'SYN-EVT-1', 0);
  assert.deepEqual(replay, first); assert.notStrictEqual(replay, first); assert.notStrictEqual(replay.history[0], first.history[0]);
});
test('rejects reused event key for different action', () =>
  assert.throws(() => advance(registered(), a.receptionist, 'BOOK_SLOT', 'SYN-EVT-1'), /Event key reused/));
test('rejects reused event key from different actor', () =>
  assert.throws(() => transition(registered(), { ...a.receptionist, actorId: 'SYN-OTHER-STAFF' }, 'CONFIRM_DUPLICATE_REVIEW', 'SYN-EVT-1', 0), /Event key reused/));
test('unknown action fails closed', () =>
  assert.throws(() => advance(start(), a.receptionist, 'UNKNOWN'), /Unsupported/));
test('all seven fictional actions proceed with fixed patient context', () => {
  let j = start();
  for (const action of Object.keys(RULES)) {
    const role = RULES[action].role;
    j = advance(j, ACTORS[role], action);
    assert.deepEqual(j.context, FICTIONAL_CONTEXT);
    assert.equal(j.history.length, j.revision);
  }
  assert.equal(j.stage, 'REPORT_PREVIEW'); assert.equal(j.revision, 7);
  assert.equal(nextAction(j.stage), null);
});
test('history has the right role order', () => {
  const j = advance(bill(), a.billing, 'PREVIEW_REPORT');
  assert.deepEqual(j.history.map(e => e.actorId),
    ['SYN-RECEPTION-01', 'SYN-RECEPTION-01', 'SYN-RECEPTION-01',
      'SYN-DOCTOR-01', 'SYN-DOCTOR-01', 'SYN-BILLING-01', 'SYN-BILLING-01']);
});
test('final stage cannot advance past preview', () =>
  assert.equal(nextAction(advance(bill(), a.billing, 'PREVIEW_REPORT').stage), null));
test('all ordered stage labels are defined', () =>
  assert.ok(ORDER.every(s => Boolean(LABELS[s]))));
