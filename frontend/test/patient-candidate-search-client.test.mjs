import test from 'node:test';
import assert from 'node:assert/strict';
import {findPatientCandidates,PatientSearchError}
  from '../src/patientCandidateSearchClient.ts';

const id=n=>'44444444-4444-4444-8444-'+String(n).padStart(12,'0');
const branch=id(2);
const actor=Object.freeze({tenantId:id(1),accessToken:'A'.repeat(43),
  permissions:['PATIENT_REGISTRATION_READ','PATIENT_CANDIDATE_SEARCH']});
const result=Object.freeze({tenantId:id(1),branchId:branch,
  registrationId:id(3),patientId:id(4),displayName:'Fictional Search',
  mrn:'PAT-FAKE001'});
const reply=(body,status=200)=>new Response(JSON.stringify(body),{status});

test('exact MRN search sends only bounded same-origin bearer and reads server data',async()=>{
  let n=0;
  const rows=await findPatientCandidates(actor,branch,'MRN','PAT-FAKE001',
    async(url,init)=>{
      n++;
      assert.equal(url,'/api/v1/branches/'+branch+
        '/patient-search?mode=MRN&term=PAT-FAKE001');
      assert.equal(init.method,'GET');
      assert.equal(init.cache,'no-store');
      assert.equal(init.credentials,'omit');
      assert.equal(init.headers.Authorization,'Bearer '+actor.accessToken);
      return reply([{...result,ignored:'not returned'}]);
    });
  assert.equal(n,1);
  assert.deepEqual(rows,[result]);
  assert.equal(Object.isFrozen(rows),true);
  assert.equal(Object.isFrozen(rows[0]),true);
  assert.equal(Object.hasOwn(rows[0],'ignored'),false);
});

test('name prefix is URL-escaped without placing names in any audit logging',async()=>{
  await findPatientCandidates(actor,branch,'NAME_PREFIX','Fictional Search',
    async(url)=>{assert.ok(url.includes('term=Fictional%20Search'));return reply([])});
});

test('missing role, expired local format or unsafe syntax stops before HTTP',async()=>{
  let calls=0;
  const network=async()=>{calls++;return reply([result])};
  for(const [staff,mode,query] of [
    [{...actor,permissions:['PATIENT_REGISTRATION_READ']},'MRN','PAT-FAKE001'],
    [{...actor,permissions:['PATIENT_CANDIDATE_SEARCH']},'MRN','PAT-FAKE001'],
    [{...actor,accessToken:'bad'},'MRN','PAT-FAKE001'],
    [actor,'MRN','%'],
    [actor,'MRN','ab'],
    [actor,'NAME_PREFIX','Fi'],
    [actor,'NAME_PREFIX','Fic%'],
    [actor,'NAME_PREFIX','Fic_'],
    [actor,'NAME_PREFIX','Fic; DROP'],
    [actor,'INVALID','ABC'],
  ])await assert.rejects(findPatientCandidates(staff,branch,mode,query,network),
    PatientSearchError);
  assert.equal(calls,0);
});

test('forged tenant/branch IDs, malformed fields and duplicate references fail',async()=>{
  for(const fake of [
    [{...result,tenantId:id(8)}],
    [{...result,branchId:id(8)}],
    [{...result,patientId:id(8).replace(/^4444/,'abcd'),registrationId:'no'}],
    [{...result,displayName:''}],
    [{...result,mrn:'sensitive-do-not-return'}],
    [result,result],
    Array.from({length:11},()=>result),
    {secret:'bad'},null,
  ])await assert.rejects(findPatientCandidates(actor,branch,'MRN','PAT-FAKE001',
    async()=>reply(fake)),PatientSearchError);
});

test('backend denial, lost session and network failure have no identity fallback',async()=>{
  for(const status of [400,401,403,404,409,429,500,503]){
    await assert.rejects(findPatientCandidates(actor,branch,'MRN','PAT-FAKE001',
      async()=>reply({secret:'hidden'},status)),PatientSearchError);
  }
  await assert.rejects(findPatientCandidates(actor,branch,'MRN','PAT-FAKE001',
    async()=>{throw Error('Internal sensitive response')}),PatientSearchError);
});
