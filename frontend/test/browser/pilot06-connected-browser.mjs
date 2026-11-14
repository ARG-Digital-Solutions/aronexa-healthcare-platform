import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
import path from 'node:path';
import {existsSync} from 'node:fs';

/**
 * Real Chromium browser / actual Spring HTTP via loopback Vite proxy.
 * Instantiated only by the disposable Testcontainers JUnit controller.
 * Fixture delivered over stdin; never print usernames/tokens/PHI.
 */
let stage='startup';
let browser;
try {
  stage='qa-root';
  const qaRoot=process.env.ARONEXA_PILOT06_QA_ROOT;
  assert.ok(qaRoot&&path.isAbsolute(qaRoot));
  const modulePath=path.join(qaRoot,'node_modules','playwright','index.mjs');
  stage='playwright-module';
  assert.ok(existsSync(modulePath),'Playwright must be installed in QA Central');
  const {chromium}=await import(pathToFileURL(modulePath).href);
  stage='fixture-stdin';
  const chunks=[];
  for await(const chunk of process.stdin) chunks.push(chunk);
  stage='fixture-validation';
  const fixture=JSON.parse(Buffer.concat(chunks).toString('utf8'));
  const ui=new URL(fixture.origin);
  assert.equal(ui.protocol,'http:');
  assert.equal(ui.hostname,'127.0.0.1');
  assert.equal(ui.port,'5176');
  assert.equal(ui.pathname,'/');
  for(const key of ['branchId','appointmentId']) {
    assert.match(fixture[key],/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i);
  }
  stage='chromium-executable';
  const configured=process.env.ARONEXA_CHROMIUM_BIN?.trim();
  if(configured){
    assert.ok(path.isAbsolute(configured)&&existsSync(configured));
  } else {
    assert.ok(existsSync(chromium.executablePath()));
  }
  stage='chromium-launch';
  browser=await chromium.launch({
    headless:true,timeout:15000,
    ...(configured?{executablePath:configured}:{}),
  });
  stage='chromium-context';
  const context=await browser.newContext({
    timezoneId:'Asia/Kolkata',viewport:{width:1366,height:900},
  });
  const page=await context.newPage();
  page.setDefaultTimeout(15000);
  stage='reception-login';
  const entered=[];
  page.on('response',response=>{
    const url=new URL(response.url());
    if(url.pathname.startsWith('/api/v1/'))entered.push({
      path:url.pathname,status:response.status(),
    });
  });
  await page.goto(new URL('connected-reception-preview.html',ui).href);
  await page.locator('#tenant').fill(fixture.tenantCode);
  await page.locator('#username').fill(fixture.receptionUser);
  await page.locator('#password').fill(fixture.receptionPassword);
  await page.locator('#branch').fill(fixture.branchId);
  await page.locator('input[type=checkbox]').first().check();
  await page.getByRole('button',{name:'Sign in and verify session'}).click();

  stage='reception-queue';
  await page.getByRole('button',{name:'Daily appointments'}).click();
  await page.getByRole('button',{name:'Load daily queue'}).click();
  const item=page.getByRole('listitem').filter({hasText:fixture.appointmentId});
  await item.getByRole('button',{name:'Manage appointment'}).click();
  await page.getByRole('button',{name:'Check in this appointment'}).click();
  await page.getByRole('button',{name:'Hand off to assigned doctor sign-in'}).click();

  stage='assigned-doctor-login';
  await page.getByRole('heading',{name:'Doctor sign-in'}).waitFor();
  assert.equal(await page.locator('#doctor-branch').inputValue(),fixture.branchId);
  await page.locator('#doctor-tenant').fill(fixture.tenantCode);
  await page.locator('#doctor-user').fill(fixture.doctorUser);
  await page.locator('#doctor-password').fill(fixture.doctorPassword);
  await page.locator('input[type=checkbox]').first().check();
  await page.getByRole('button',{name:'Sign in as doctor'}).click();

  stage='consultation-draft';
  assert.equal(await page.locator('#consult-appointment').inputValue(),
    fixture.appointmentId);
  await page.getByRole('button',{name:'Open consultation'}).click();
  await page.locator('#consult-note').fill('FICTIONAL PILOT06 BROWSER CONSULTATION');
  await page.getByRole('button',{name:'Save consultation draft'}).click();
  await page.getByText('Draft saved and verified on the server.',{exact:false}).waitFor();
  await page.getByRole('button',{name:'Open prescription draft'}).click();

  stage='prescription-draft';
  await page.getByRole('heading',{name:'Structured prescription — draft only'}).waitFor();
  const meds={
    medicine:'FICTIONAL-DEMO-ITEM',strength:'SYNTHETIC',
    form:'DEMO',dose:'DEMO',route:'DEMO',frequency:'DEMO',duration:'DEMO',
  };
  for(const [key,value] of Object.entries(meds))await page.locator('#med-'+key).fill(value);
  await page.getByRole('button',{name:'Add medicine to draft'}).click();
  await page.getByRole('button',{name:'Save prescription draft'}).click();
  await page.getByText('Prescription draft saved on the test server.',{exact:false}).waitFor();
  await page.getByRole('button',{name:'Hand off to billing staff sign-in'}).click();

  stage='billing-login';
  await page.getByRole('heading',{name:'Billing staff sign-in'}).waitFor();
  assert.equal(await page.locator('#bill-branch').inputValue(),fixture.branchId);
  await page.locator('#bill-tenant').fill(fixture.tenantCode);
  await page.locator('#bill-user').fill(fixture.billingUser);
  await page.locator('#bill-password').fill(fixture.billingPassword);
  await page.locator('input[type=checkbox]').first().check();
  await page.getByRole('button',{name:'Sign in to billing'}).click();

  stage='billing-create';
  assert.equal(await page.locator('#bill-appointment').inputValue(),
    fixture.appointmentId);
  await page.getByRole('button',{name:'Open OPD billing'}).click();
  await page.getByRole('button',{name:'Create OPD bill'}).click();
  await page.getByText('Fictional OPD bill created with server fee.',{exact:false}).waitFor();

  stage='daily-report';
  await page.getByRole('button',{name:'Load daily report'}).click();
  await page.getByText('Bills created',{exact:true}).waitFor();
  assert.ok(entered.some(e=>e.path.endsWith('/check-in')&&e.status===200));
  assert.ok(entered.some(e=>e.path.endsWith('/consultation-draft')
    &&e.status===200));
  assert.ok(entered.some(e=>e.path.endsWith('/prescription-draft')
    &&e.status===200));
  assert.ok(entered.some(e=>e.path.endsWith('/bill')&&e.status===201));
  assert.ok(entered.some(e=>e.path.endsWith('/billing/daily')&&e.status===200));
  await context.close();
  process.stdout.write('PILOT06_CONNECTED_REAL_BROWSER_HTTP_PASS\n');
} catch (failure) {
  // The test is synthetic but still must not log bearer tokens, clinical
  // notes, responses, fixture credentials or backend exception bodies.
  const reason=failure?.name==='TimeoutError'?'TIMEOUT'
    :failure?.code==='ENOENT'||failure?.code==='EACCES'
      ?'EXECUTABLE_ACCESS':'UNVERIFIED';
  process.stderr.write('PILOT06_BROWSER_FAILED_STAGE='+stage+
    '_REASON='+reason+'\n');
  process.exitCode=1;
} finally {
  if(browser)await browser.close();
}
