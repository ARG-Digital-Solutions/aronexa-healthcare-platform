import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
import path from 'node:path';
import {existsSync} from 'node:fs';

/**
 * RC03: registration/booking/check-in are all CREATED by real Chromium
 * clicks against a marker-guarded isolated PostgreSQL 17 backend.
 * Fixture via stdin, never echo credentials or any bearer/patient name.
 * Success stdout contains pseudonymous server-issued UUIDs/date only.
 */
let stage='startup';
let browser;
try {
  const qaRoot=process.env.ARONEXA_PILOT06_QA_ROOT;
  assert.ok(qaRoot&&path.isAbsolute(qaRoot));
  const playwrightPath=path.join(qaRoot,'node_modules','playwright','index.mjs');
  assert.ok(existsSync(playwrightPath));
  const {chromium}=await import(pathToFileURL(playwrightPath).href);
  const buffers=[];
  for await(const bytes of process.stdin)buffers.push(bytes);
  const input=JSON.parse(Buffer.concat(buffers).toString('utf8'));
  const origin=new URL(input.origin);
  assert.equal(origin.protocol,'http:');
  assert.equal(origin.hostname,'127.0.0.1');
  assert.equal(origin.port,'5176');
  assert.equal(origin.pathname,'/');
  assert.match(input.branchId,/^[a-f0-9-]{36}$/i);
  assert.equal(input.confirm,'RC03_MARKED_DISPOSABLE_ONLY');
  const configured=process.env.ARONEXA_CHROMIUM_BIN?.trim();
  if(configured)assert.ok(path.isAbsolute(configured)&&existsSync(configured));
  else assert.ok(existsSync(chromium.executablePath()));
  stage='launch';
  browser=await chromium.launch({
    headless:true,timeout:15000,
    ...(configured?{executablePath:configured}:{}),
  });
  const context=await browser.newContext({
    timezoneId:'Asia/Kolkata',viewport:{width:1366,height:900},
  });
  const page=await context.newPage();
  page.setDefaultTimeout(15000);
  const url=new URL('connected-reception-preview.html',origin).href;
  const registrationUrl='/api/v1/branches/'+input.branchId+'/patient-registrations';
  const appointmentUrl='/api/v1/branches/'+input.branchId+'/appointments';
  const uuid=/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
  const fictionalName='Fictional Rc03 Browser Registration';
  async function signIn() {
    await page.locator('#tenant').fill(input.tenantCode);
    await page.locator('#username').fill(input.receptionUser);
    await page.locator('#password').fill(input.receptionPassword);
    await page.locator('#branch').fill(input.branchId);
    await page.locator('input[type=checkbox]').first().check();
    await page.getByRole('button',{name:'Sign in and verify session'}).click();
    await page.locator('#full-name').waitFor();
  }
  stage='first-login';
  await page.goto(url);
  await signIn();
  stage='register-review';
  await page.locator('#full-name').fill(fictionalName);
  await page.getByRole('button',{name:'Review registration'}).click();
  await page.getByRole('heading',{name:'Confirm before creating a new patient identity'}).waitFor();
  stage='browser-registration-post';
  const registeredResponse=page.waitForResponse(r=>
    new URL(r.url()).pathname===registrationUrl &&
      r.request().method()==='POST'&&r.status()===201);
  await page.getByRole('button',{name:'Confirm and save to test PostgreSQL'}).click();
  const registered=await (await registeredResponse).json();
  assert.match(registered.patientId,uuid);
  assert.match(registered.registrationId,uuid);
  assert.match(registered.mrn,/^PAT-[A-Z0-9]{12}$/);
  await page.getByRole('heading',{name:'Registration acknowledged'}).waitFor();
  stage='verified-registration-read';
  await page.getByRole('button',{name:'Verify authorized read-back'}).click();
  await page.getByText('Authorized server read-back confirms this registration/patient reference.',{exact:false}).waitFor();
  stage='select-doctor';
  await page.getByRole('button',{name:'Book a test appointment'}).click();
  const doctor=page.locator('#appointment-doctor');
  await doctor.waitFor();
  assert.equal(await doctor.evaluate(e=>e.tagName),'SELECT');
  const doctorIds=await doctor.locator('option').evaluateAll(options=>
    options.map(o=>o.value).filter(Boolean));
  assert.equal(doctorIds.length,1);
  assert.equal(await doctor.inputValue(),doctorIds[0]);
  await page.locator('#appointment-slot').selectOption('14:30');
  stage='browser-booking-post';
  const bookedResponse=page.waitForResponse(r=>
    new URL(r.url()).pathname===appointmentUrl &&
      r.request().method()==='POST'&&r.status()===201);
  await page.getByRole('button',{name:'Book test appointment',exact:true}).click();
  const booked=await (await bookedResponse).json();
  assert.match(booked.appointmentId,uuid);
  assert.equal(booked.patientId,registered.patientId);
  assert.equal(booked.registrationId,registered.registrationId);
  assert.equal(booked.doctorMembershipId,doctorIds[0]);
  assert.equal(booked.stage,'BOOKED');
  await page.getByRole('heading',{name:'Saved appointment'}).waitFor();
  stage='browser-check-in';
  const checkedResponse=page.waitForResponse(r=>
    new URL(r.url()).pathname===
      appointmentUrl+'/'+booked.appointmentId+'/check-in' &&
      r.request().method()==='POST'&&r.status()===200);
  await page.getByRole('button',{name:'Check in this appointment'}).click();
  const checked=await (await checkedResponse).json();
  assert.equal(checked.patientId,registered.patientId);
  assert.equal(checked.appointmentId,booked.appointmentId);
  assert.equal(checked.stage,'CHECKED_IN');
  assert.equal(checked.revision,2);
  stage='queue-read-after-browser-write';
  await page.getByRole('button',{name:'Return to daily queue'}).click();
  await page.getByRole('button',{name:'Load daily queue'}).click();
  const item=page.getByRole('listitem').filter({hasText:booked.appointmentId});
  await item.getByText('CHECKED_IN',{exact:false}).waitFor();
  await item.getByRole('button',{name:'Open verified registration'}).click();
  await page.getByRole('heading',{name:'Existing registration found'}).waitFor();
  await page.getByText(registered.registrationId,{exact:true}).waitFor();
  stage='duplicate-review-new-session';
  await page.getByRole('button',{name:/^Sign out/}).click();
  await page.getByRole('heading',{name:'Staff sign-in'}).waitFor();
  await signIn();
  await page.locator('#full-name').fill(fictionalName);
  await page.getByRole('button',{name:'Review registration'}).click();
  await page.getByRole('heading',{name:'Confirm before creating a new patient identity'}).waitFor();
  const duplicateResponse=page.waitForResponse(r=>
    new URL(r.url()).pathname===registrationUrl &&
      r.request().method()==='POST'&&r.status()===409);
  await page.getByRole('button',{name:'Confirm and save to test PostgreSQL'}).click();
  const duplicate=await (await duplicateResponse).json();
  assert.equal(duplicate.code,'DUPLICATE_REVIEW_REQUIRED');
  await page.getByRole('heading',{name:'Do not create a second registration'}).waitFor();
  await context.close();
  // A separate JVM will read these exact values from the labelled PG17.
  process.stdout.write(
    'PILOT08_RC03_BROWSER_CREATED='+
    [registered.registrationId,registered.patientId,
      booked.appointmentId,booked.visitDate].join('|')+'\n');
  process.stdout.write('PILOT08_RC03_BROWSER_DUPLICATE_REVIEW_REJECTED\n');
} catch (failure) {
  // Preserve test privacy: no response bodies, patient names, bearer or
  // credentials in failure logs. Stage/reason only.
  const reason=failure?.name==='TimeoutError'?'TIMEOUT':
    failure?.code==='ENOENT'||failure?.code==='EACCES'?
      'EXECUTABLE_ACCESS':'UNVERIFIED';
  process.stderr.write('PILOT08_RC03_BROWSER_FAIL_STAGE='+stage+
    '_REASON='+reason+'\n');
  process.exitCode=1;
} finally {
  if(browser)await browser.close();
}
