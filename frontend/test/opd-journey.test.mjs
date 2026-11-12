import test from 'node:test';
import assert from 'node:assert/strict';
import {startOpdJourney, acceptOpdReceipt, OpdLinkError} from '../src/opdJourney.ts';

const id = suffix => 'aaaaaaaa-aaaa-4aaa-8aaa-' + String(suffix).padStart(12, '0');
const context = Object.freeze({tenantId: id(1), branchId: id(2)});
const registration = Object.freeze({
  patientId: id(3), registrationId: id(4), mrn: 'PAT-0123456789AB', replayed: false,
});
const start = () => startOpdJourney(context, registration);
const baseReceipt = (journey, stage, extra={}) => ({
  stage, tenantId: journey.tenantId, branchId: journey.branchId,
  patientId: journey.patientId, registrationId: journey.registrationId,
  ...(journey.appointmentId ? {appointmentId: journey.appointmentId} : {}),
  ...(journey.encounterId ? {encounterId: journey.encounterId} : {}),
  ...(journey.prescriptionId ? {prescriptionId: journey.prescriptionId} : {}),
  ...(journey.invoiceId ? {invoiceId: journey.invoiceId} : {}),
  ...(journey.encounterRevision ? {encounterRevision: journey.encounterRevision} : {}),
  ...(journey.prescriptionVersion ? {prescriptionVersion: journey.prescriptionVersion} : {}),
  ...extra,
});
const book = journey => acceptOpdReceipt(journey, baseReceipt(journey, 'BOOKED', {appointmentId: id(5)}));
const checkIn = journey => acceptOpdReceipt(journey, baseReceipt(journey, 'CHECKED_IN'));
const consult = journey => acceptOpdReceipt(journey, baseReceipt(journey, 'CONSULTED', {
  encounterId: id(6), encounterRevision: 1,
}));
const prescribe = journey => acceptOpdReceipt(journey, baseReceipt(journey, 'PRESCRIBED', {
  prescriptionId: id(7), prescriptionVersion: 1,
}));
const bill = journey => acceptOpdReceipt(journey, baseReceipt(journey, 'BILLED', {invoiceId: id(8)}));
const expectDenied = fn => assert.throws(fn, error =>
  error instanceof OpdLinkError && !/aaaaaaaa|patient|token|password/i.test(error.message));

test('links the complete OPD journey using exactly the original registered patient ID', () => {
  const registered = start();
  const booked = book(registered);
  const checkedIn = checkIn(booked);
  const consulted = consult(checkedIn);
  const prescribed = prescribe(consulted);
  const billed = bill(prescribed);
  assert.deepEqual([registered, booked, checkedIn, consulted, prescribed, billed].map(x => x.stage),
    ['REGISTERED', 'BOOKED', 'CHECKED_IN', 'CONSULTED', 'PRESCRIBED', 'BILLED']);
  for (const link of [registered, booked, checkedIn, consulted, prescribed, billed]) {
    assert.equal(link.patientId, registration.patientId);
    assert.equal(link.registrationId, registration.registrationId);
    assert.equal(link.branchId, context.branchId);
    assert.equal(link.tenantId, context.tenantId);
    assert.ok(Object.isFrozen(link));
  }
  assert.equal(billed.appointmentId, id(5));
  assert.equal(billed.encounterId, id(6));
  assert.equal(billed.prescriptionId, id(7));
  assert.equal(billed.invoiceId, id(8));
  assert.equal(billed.encounterRevision, 1);
  assert.equal(billed.prescriptionVersion, 1);
  assert.equal(registered.appointmentId, undefined);
  assert.deepEqual(Object.keys(billed).filter(x => /token|password|clinical|medicine|payment/i.test(x)), []);
});

test('rejects demo IDs, missing identities, invalid MRNs, fake replay and mutable inputs', () => {
  for (const candidate of [
    {...registration, patientId: 'DEMO-PATIENT-001'},
    {...registration, registrationId: id(3)},
    {...registration, mrn: 'DEMO-MRN'},
    {...registration, mrn: 'PAT-00000000000O'},
    {...registration, replayed: 'false'},
  ]) expectDenied(() => startOpdJourney(context, candidate));
  expectDenied(() => startOpdJourney({...context, branchId: 'DEMO-BRANCH'}, registration));
  expectDenied(() => startOpdJourney(null, registration));
  expectDenied(() => startOpdJourney(context, null));
});

test('a structurally validated ID does not allow stage skipping or duplicate transitions', () => {
  const registered = start();
  expectDenied(() => acceptOpdReceipt(registered, baseReceipt(registered, 'CONSULTED', {
    encounterId: id(6), encounterRevision: 1,
  })));
  const booked = book(registered);
  expectDenied(() => acceptOpdReceipt(booked, baseReceipt(booked, 'BOOKED', {appointmentId: id(5)})));
  expectDenied(() => acceptOpdReceipt(bill(prescribe(consult(checkIn(booked)))),
    baseReceipt(booked, 'BILLED', {invoiceId: id(8)})));
});

test('rejects mismatched tenant, branch, patient and registration references', () => {
  const current = start();
  for (const field of ['tenantId', 'branchId', 'patientId', 'registrationId']) {
    expectDenied(() => acceptOpdReceipt(current, baseReceipt(current, 'BOOKED', {
      appointmentId: id(5), [field]: id(99),
    })));
  }
});

test('rejects fabricated future IDs, missing IDs and reuse of an unrelated ID', () => {
  const current = start();
  expectDenied(() => acceptOpdReceipt(current, baseReceipt(current, 'BOOKED', {appointmentId: 'DEMO-APT-0001'})));
  expectDenied(() => acceptOpdReceipt(current, baseReceipt(current, 'BOOKED')));
  expectDenied(() => acceptOpdReceipt(current, baseReceipt(current, 'BOOKED', {
    appointmentId: id(5), encounterId: id(6),
  })));
  expectDenied(() => acceptOpdReceipt(current, baseReceipt(current, 'BOOKED', {
    appointmentId: current.patientId,
  })));
});

test('check-in must retain server-confirmed appointment; no client reassignment', () => {
  const booked = book(start());
  expectDenied(() => acceptOpdReceipt(booked, baseReceipt(booked, 'CHECKED_IN', {appointmentId: id(99)})));
  const receipt = baseReceipt(booked, 'CHECKED_IN');
  delete receipt.appointmentId;
  expectDenied(() => acceptOpdReceipt(booked, receipt));
  assert.equal(checkIn(booked).appointmentId, booked.appointmentId);
});

test('consultation revision and prescription version must be positive integers', () => {
  const checkedIn = checkIn(book(start()));
  for (const revision of [0, -1, 1.1, '1', null]) {
    expectDenied(() => acceptOpdReceipt(checkedIn, baseReceipt(checkedIn, 'CONSULTED', {
      encounterId: id(6), encounterRevision: revision,
    })));
  }
  const consulted = consult(checkedIn);
  for (const version of [0, -2, 1.7, '1']) {
    expectDenied(() => acceptOpdReceipt(consulted, baseReceipt(consulted, 'PRESCRIBED', {
      prescriptionId: id(7), prescriptionVersion: version,
    })));
  }
});

test('later steps must retain every prior ID and revision exactly', () => {
  const consulted = consult(checkIn(book(start())));
  for (const alteration of [
    {appointmentId: id(99)}, {encounterId: id(99)},
    {encounterRevision: 2}, {invoiceId: id(99)},
  ]) {
    expectDenied(() => acceptOpdReceipt(consulted, baseReceipt(consulted, 'PRESCRIBED', {
      prescriptionId: id(7), prescriptionVersion: 1, ...alteration,
    })));
  }
});

test('rejects missing or unexpected response shapes without echoing data', () => {
  const current = start();
  for (const value of [null, false, [], {}, 'secret', {stage:'BOOKED'}]) {
    expectDenied(() => acceptOpdReceipt(current, value));
  }
});
