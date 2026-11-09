import assert from 'node:assert/strict';
import { test } from 'node:test';
import { canViewSyntheticPatient, DEMO_BRANCH, DEMO_DOCTOR, DEMO_TENANT, EMPTY_DRAFT, saveSyntheticDraft, SYNTHETIC_PATIENTS } from '../src/consultation/model.ts';

const doctor = { role: 'doctor', doctorId: DEMO_DOCTOR, tenantId: DEMO_TENANT, branchId: DEMO_BRANCH };
const patient = SYNTHETIC_PATIENTS[0];
const notes = { ...EMPTY_DRAFT, complaint: 'Synthetic cough only' };
const time = '2026-11-10T00:00:00.000Z';

test('assigned doctor may view synthetic patient', () => assert.equal(canViewSyntheticPatient(doctor, patient), true));
test('receptionist denied clinical access', () => assert.equal(canViewSyntheticPatient({ ...doctor, role: 'receptionist' }, patient), false));
test('doctor identity mismatch denied', () => assert.equal(canViewSyntheticPatient({ ...doctor, doctorId: 'OTHER' }, patient), false));
test('tenant mismatch denied', () => assert.equal(canViewSyntheticPatient({ ...doctor, tenantId: 'OTHER' }, patient), false));
test('branch mismatch denied', () => assert.equal(canViewSyntheticPatient({ ...doctor, branchId: 'OTHER' }, patient), false));
test('unknown patient denied', () => assert.equal(canViewSyntheticPatient(doctor, undefined), false));
test('unknown patient cannot be saved', () => assert.throws(() => saveSyntheticDraft(doctor, undefined, notes, null, time), /Access denied/));
test('non-doctor cannot save', () => assert.throws(() => saveSyntheticDraft({ ...doctor, role: 'receptionist' }, patient, notes, null, time), /Access denied/));
test('empty draft cannot be saved', () => assert.throws(() => saveSyntheticDraft(doctor, patient, EMPTY_DRAFT, null, time), /Enter at least one/));
test('oversized field rejected', () => assert.throws(() => saveSyntheticDraft(doctor, patient, { ...notes, complaint: 'A'.repeat(2001) }, null, time), /Invalid complaint length/));
test('invalid timestamp rejected', () => assert.throws(() => saveSyntheticDraft(doctor, patient, notes, null, 'invalid'), /timestamp/));
test('versioned save returns trimmed clone without modifying source', () => {
  const source = { ...EMPTY_DRAFT, complaint: '  fictional cough  ' };
  const result = saveSyntheticDraft(doctor, patient, source, null, time);
  assert.equal(result.version, 1);
  assert.equal(result.status, 'SYNTHETIC_DRAFT_ONLY');
  assert.equal(result.fields.complaint, 'fictional cough');
  assert.equal(source.complaint, '  fictional cough  ');
  assert.notStrictEqual(result.fields, source);
  const next = saveSyntheticDraft(doctor, patient, notes, result, time);
  assert.equal(next.version, 2);
  assert.equal(result.version, 1);
});
test('patient-context mixup cannot overwrite previous draft', () => {
  const first = saveSyntheticDraft(doctor, patient, notes, null, time);
  assert.throws(() => saveSyntheticDraft(doctor, SYNTHETIC_PATIENTS[1], notes, first, time), /context mismatch/);
});
test('author-context mixup cannot overwrite previous draft', () => {
  const first = saveSyntheticDraft(doctor, patient, notes, null, time);
  assert.throws(() => saveSyntheticDraft({ ...doctor, doctorId: 'OTHER' }, patient, notes, first, time), /Access denied/);
});
