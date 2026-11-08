import test from "node:test";
import assert from "node:assert/strict";
import {localCalendarDate,normalizeIntake,blankIntake,candidateMatches,demoRegistration,maskPhone,seedPatients,validateIntake} from "../src/receptionModel.ts";
test("C1-A03 rejects empty intake",()=>assert.ok(validateIntake(blankIntake()).name));
test("C1-A03 supports unknown DOB with no date",()=>assert.deepEqual(validateIntake({name:"Demo Person",precision:"UNKNOWN",dob:"",phone:""}),{}));
test("C1-A03 rejects future DOB",()=>assert.ok(validateIntake({name:"Demo Person",precision:"FULL",dob:"2099-01-01",phone:""}, "2026-11-07").dob));
test("C1-A03 rejects inconsistent unknown DOB",()=>assert.ok(validateIntake({name:"Demo Person",precision:"UNKNOWN",dob:"1991-01-01",phone:""}).dob));
test("C1-A03 rejects malformed phone",()=>assert.ok(validateIntake({name:"Demo Person",precision:"UNKNOWN",dob:"",phone:"123"}).phone));
test("C1-A04 similar phones alone never produce matches",()=>assert.equal(candidateMatches({name:"Unrelated Person",precision:"UNKNOWN",dob:"",phone:seedPatients[0].phone},seedPatients).length,0));
test("C1-A04 exact synthetic name suggests review",()=>assert.equal(candidateMatches({name:" ANAYA   KULKARNI ",precision:"UNKNOWN",dob:"",phone:""},seedPatients).length,1));
test("C1-A04 DOB mismatch not flagged as same exact candidate",()=>assert.equal(candidateMatches({name:"Anaya Kulkarni",precision:"FULL",dob:"2001-01-01",phone:""},seedPatients).length,0));
test("C1-A05 registers an in-memory result only",()=>{const p=demoRegistration({name:"Test Patient",precision:"UNKNOWN",dob:"",phone:""},5,"11:00");assert.equal(p.id,"DEMO-0005");assert.equal(p.name,"Test Patient");assert.equal(p.status,"Registered");assert.equal(p.dob,"");});
test("C1-A04 masks sample contact display",()=>assert.equal(maskPhone("9000000001"),"******0001"));

// Fresh processes ensure the operating timezone is set before Date is initialized.
import {spawnSync} from "node:child_process";
for(const [tz,instant,today,tomorrow] of [
  ["Asia/Kolkata","2026-11-06T19:00:00Z","2026-11-07","2026-11-08"],
  ["America/Los_Angeles","2026-11-07T01:00:00Z","2026-11-06","2026-11-07"]
])test(`C1-A03 local calendar boundary ${tz}`,()=>{
  const script=`import assert from 'node:assert/strict';
    import {localCalendarDate,validateIntake} from ${JSON.stringify(new URL("../src/receptionModel.ts",import.meta.url).href)};
    const today=localCalendarDate(new Date(${JSON.stringify(instant)}));
    assert.equal(today,${JSON.stringify(today)});
    const intake={name:'Synthetic Person',precision:'FULL',dob:today,phone:''};
    assert.deepEqual(validateIntake(intake,today),{});
    assert.ok(validateIntake({...intake,dob:${JSON.stringify(tomorrow)}},today).dob);`;
  const result=spawnSync(process.execPath,["--experimental-strip-types","--input-type=module","-e",script],{env:{...process.env,TZ:tz},encoding:"utf8"});
  assert.equal(result.status,0,result.stderr);
});
for(const dob of ["2025-02-29","2024-02-30","1900-02-29","2026-04-31","2026-13-01","0000-00-00","not-a-date","2026-1-01"])
 test(`C1-A03 rejects invalid calendar date ${dob}`,()=>assert.ok(validateIntake({name:"Synthetic Person",precision:"FULL",dob,phone:""},"2026-11-07").dob));
for(const dob of ["2000-02-29","2024-02-29"])
 test(`C1-A03 accepts leap date ${dob}`,()=>assert.deepEqual(validateIntake({name:"Synthetic Person",precision:"FULL",dob,phone:""},"2026-11-07"),{}));
test("C1-A04/A05 preview normalization equals created record",()=>{
 const input={name:"  Synthetic   Person  ",precision:"FULL",dob:"2000-02-29",phone:" 9000000000 "};
 const normalized=normalizeIntake(input),created=demoRegistration(input,9);
 assert.equal(normalized.name,created.name);assert.equal(normalized.phone,created.phone);assert.equal(normalized.dob,created.dob);
 assert.equal(maskPhone(normalized.phone),"******0000");assert.equal(input.name,"  Synthetic   Person  ");
});
test("C1-A03 invalid intake cannot bypass confirmation validation",()=>assert.throws(()=>demoRegistration(blankIntake(),5),/Invalid intake/));
test("C1-A04 suggestions cap at five without modifying records",()=>{
 const patients=Array.from({length:7},(_,i)=>({...seedPatients[0],id:`DEMO-${i}`}));
 const before=structuredClone(patients);
 assert.equal(candidateMatches({name:patients[0].name,precision:"UNKNOWN",dob:"",phone:""},patients).length,5);
 assert.deepEqual(patients,before);
});
test("C1-A03 rejects invalid names and phones",()=>{
 for(const name of ["1","<>Synthetic","x".repeat(101)])assert.ok(validateIntake({name,precision:"UNKNOWN",dob:"",phone:""}).name);
 for(const phone of ["900000000a","12345678901"])assert.ok(validateIntake({name:"Synthetic Person",precision:"UNKNOWN",dob:"",phone}).phone);
});
