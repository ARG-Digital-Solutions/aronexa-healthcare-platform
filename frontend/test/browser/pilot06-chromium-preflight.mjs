import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';

/**
 * Very fast, fixture-free browser preflight for the owner's Windows machine.
 * No clinic backend, patient data or network access. Does not prove OPD QA.
 * Print only bounded cause categories, never executable paths or passwords.
 */
let stage='qa-root';
let browser;
try {
  const root=process.env.ARONEXA_PILOT06_QA_ROOT;
  assert.ok(root&&path.isAbsolute(root));
  const file=path.join(root,'node_modules','playwright','index.mjs');
  stage='playwright-package';
  assert.ok(existsSync(file));
  const {chromium}=await import(pathToFileURL(file).href);

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
  await page.close();
  await context.close();
  process.stdout.write('PILOT06_CHROMIUM_PREFLIGHT_PASS\n');
} catch (failure) {
  const type=failure&&typeof failure==='object'
    &&('code' in failure)
    &&(failure.code==='ENOENT'||failure.code==='EACCES')
      ?'EXECUTABLE_ACCESS'
      :failure?.name==='TimeoutError'?'TIMEOUT'
      :'UNAVAILABLE';
  process.stderr.write('PILOT06_CHROMIUM_PREFLIGHT_FAILED_STAGE='+
    stage+'_REASON='+type+'\n');
  if(stage==='chromium-executable'||stage==='chromium-launch')
    process.stderr.write('PILOT06_HINT=Run npx playwright install chromium in QA Central, or configure an explicitly approved ARONEXA_CHROMIUM_BIN\n');
  process.exitCode=1;
} finally {
  if(browser)await browser.close();
}
