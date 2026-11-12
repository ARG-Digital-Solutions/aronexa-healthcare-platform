import assert from 'node:assert/strict';
import {signInStaff, revokeStaffToken} from '../../src/staffSessionClient.ts';
import {createConnectedRegistrationFlow} from '../../src/connectedRegistrationFlow.ts';

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
  const staff=await signInStaff({
    tenantCode:fixture.tenantCode,
    username:fixture.username,
    password:fixture.password,
  },transport);
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
    // The database is checked separately by JUnit using an independent
    // connection, not inferred from this client-side receipt.
    process.stdout.write('CONNECTED_RECEPTION_WIRE_PASS\n');
  } finally {
    await revokeStaffToken(staff.accessToken,transport);
  }
}
main().catch(() => {
  // Do not emit thrown responses, bearer tokens, connection addresses or
  // synthetic patient data; JUnit checks the nonzero exit status.
  process.stderr.write('CONNECTED_RECEPTION_WIRE_FAILED\n');
  process.exitCode=1;
});
