import assert from 'node:assert/strict';
import {signInStaff, revokeStaffToken} from '../../src/staffSessionClient.ts';
import {createConnectedRegistrationFlow} from '../../src/connectedRegistrationFlow.ts';
import {createRegistrationRecoveryFlow} from '../../src/registrationRecoveryFlow.ts';

/**
 * Invoked only by disposable Spring Boot + PostgreSQL Testcontainers acceptance.
 * Receives a synthetic account over stdin. No secrets or patient details are
 * emitted to stdout, stderr or filesystem. No browser storage.
 */
async function main() {
  const pieces=[];
  for await(const chunk of process.stdin) pieces.push(chunk);
  const fixture=JSON.parse(Buffer.concat(pieces).toString('utf8'));
  assert.match(fixture.origin,/^http:\/\/127\.0\.0\.1:[0-9]{2,5}\/$/);
  assert.match(fixture.branchId,/^[a-f0-9-]{36}$/);
  const transport=(path,options)=>fetch(new URL(path,fixture.origin),options);
  const loginDetails={
    tenantCode:fixture.tenantCode,
    username:fixture.username,
    password:fixture.password,
  };
  const staff=await signInStaff(loginDetails,transport);
  let created;
  try {
    assert.equal(staff.permissions.includes('PATIENT_REGISTER'),true);
    const flow=createConnectedRegistrationFlow(staff,fixture.branchId,transport);
    const summary=flow.prepare({
      name:'Fictional Connected Reception Wire',
      precision:'FULL',
      dob:'1990-02-03',
      phone:'',
    });
    assert.equal(summary.displayName,'Fictional Connected Reception Wire');
    assert.equal(flow.snapshot().status,'REVIEW');
    const first=await flow.submitReviewed();
    created=first;
    assert.equal(first.stage,'REGISTERED');
    assert.equal(first.tenantId,staff.tenantId);
    assert.equal(first.branchId,fixture.branchId);
    assert.match(first.mrn,/^PAT-[0-9A-HJKMNP-TV-Z]{12}$/);
    assert.match(first.patientId,/^[a-f0-9-]{36}$/i);
    assert.match(first.registrationId,/^[a-f0-9-]{36}$/i);
    assert.notEqual(first.registrationId,first.patientId);
    assert.equal(flow.snapshot().status,'REGISTERED');
    assert.equal(JSON.stringify(flow.snapshot()).includes(staff.accessToken),false);
    assert.equal(JSON.stringify(flow.snapshot()).includes('Fictional Connected Reception Wire'),false);
    // Actual persistence and append-only write audit are independently
    // checked by JUnit on a different JDBC connection.
  } finally {
    await revokeStaffToken(staff.accessToken,transport);
  }
  // A new login cannot inherit the old bearer; recovery must re-read SQL.
  assert.ok(created);
  const fresh=await signInStaff(loginDetails,transport);
  try {
    assert.notEqual(fresh.accessToken,staff.accessToken);
    assert.equal(fresh.permissions.includes('PATIENT_REGISTRATION_READ'),true);
    const recovery=createRegistrationRecoveryFlow(fresh,fixture.branchId,transport);
    const restored=await recovery.recover(created.registrationId);
    assert.equal(restored.tenantId,fresh.tenantId);
    assert.equal(restored.branchId,fixture.branchId);
    assert.equal(restored.registrationId,created.registrationId);
    assert.equal(restored.patientId,created.patientId);
    assert.equal(recovery.snapshot().status,'FOUND');
    assert.equal(JSON.stringify(recovery.snapshot()).includes(fresh.accessToken),false);
    recovery.dispose();
    assert.equal(recovery.snapshot().reference,null);
    process.stdout.write('CONNECTED_RECEPTION_WIRE_PASS\n');
    process.stdout.write('REGISTRATION_RELOGIN_READBACK_PASS\n');
  } finally {
    await revokeStaffToken(fresh.accessToken,transport);
  }
}
main().catch(() => {
  // Do not emit thrown responses, bearer tokens, connection addresses or
  // synthetic patient data; JUnit checks the nonzero exit status.
  process.stderr.write('CONNECTED_RECEPTION_WIRE_FAILED\n');
  process.exitCode=1;
});
