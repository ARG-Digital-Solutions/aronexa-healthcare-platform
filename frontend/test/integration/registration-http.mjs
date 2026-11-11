// Launched by the Maven acceptance test against its real random-port Spring
// server and disposable PostgreSQL. Fixtures arrive on stdin, never argv/logs.
import assert from 'node:assert/strict';
import {createRegistrationAttempt} from '../../src/registrationClient.ts';

let stage='fixture';
try {
  let input='';
  for await (const chunk of process.stdin) input+=chunk;
  const fixture=JSON.parse(input);
  const origin=new URL(fixture.origin);
  assert.equal(origin.protocol,'http:');
  assert.equal(origin.hostname,'127.0.0.1');
  assert.equal(origin.pathname,'/');
  assert.equal(origin.username,'');assert.equal(origin.password,'');
  const transport=(path,options)=>{
    assert.match(path,/^\/api\/v1\/branches\/[0-9a-f-]{36}\/patient-registrations$/);
    return fetch(new URL(path,origin),options);
  };
  const intake={name:'Synthetic Wire Registration',precision:'FULL',dob:'1990-02-03',phone:''};
  let writes=0;
  const operation=createRegistrationAttempt(intake,fixture.branch,fixture.token,async(path,options)=>{
    const response=await transport(path,options);
    writes++;
    if(writes===1) {
      assert.equal(response.status,201);
      // Commit and receipt have arrived; discard the response to model a client
      // losing confirmation after the server has durably committed its write.
      await response.arrayBuffer();
      throw new Error('Simulated lost response');
    }
    assert.equal(response.status,200);
    return response;
  });
  stage='lost-confirmation';
  const first=operation.submit(), duplicateClick=operation.submit();
  for(const result of await Promise.allSettled([first,duplicateClick])) {
    assert.equal(result.status,'rejected');
    assert.equal(result.reason.code,'OUTCOME_UNKNOWN');
  }
  assert.equal(writes,1);
  stage='durable-replay';
  const saved=await operation.submit();
  assert.equal(saved.replayed,true);
  assert.match(saved.mrn,/^PAT-[0-9A-HJKMNP-TV-Z]{12}$/);
  assert.equal(writes,2);
  assert.deepEqual(await operation.submit(),saved);
  assert.equal(writes,2);
  stage='duplicate-review';
  await assert.rejects(createRegistrationAttempt(intake,fixture.branch,fixture.token,transport).submit(),
    error=>error.code==='DUPLICATE_REVIEW_REQUIRED');
  stage='tenant-denial';
  await assert.rejects(createRegistrationAttempt({...intake,name:'Synthetic Cross Tenant'},fixture.otherBranch,fixture.token,transport).submit(),
    error=>error.code==='REGISTRATION_DENIED');
  stage='revoked-session';
  await assert.rejects(createRegistrationAttempt({...intake,name:'Synthetic Revoked Session'},fixture.revokedBranch,fixture.revokedToken,transport).submit(),
    error=>error.code==='SESSION_EXPIRED');
  process.stdout.write('REGISTRATION_WIRE_ACCEPTANCE_PASS\n');
} catch {
  // No raw response, request, error, credential or patient value in CI output.
  process.stderr.write(`Registration wire acceptance failed at ${stage}\n`);
  process.exitCode=1;
}
