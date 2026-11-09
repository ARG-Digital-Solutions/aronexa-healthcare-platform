import test from "node:test";
import assert from "node:assert/strict";
import {previewRegistrationDto,previewSyntheticOutcome} from "../src/registrationContractPreview.ts";

const proposed=(values)=>previewRegistrationDto({name:"Synthetic Contract",precision:"UNKNOWN",dob:"",phone:"",...values},"2026-11-09");

test("REG02A-01 UNKNOWN maps to UNKNOWN/null without extra fields",()=>{
  const result=proposed({name:"  Synthetic   Contract Unknown  "});
  assert.deepEqual(result,{ok:true,proposedDto:{displayName:"Synthetic Contract Unknown",birthDatePrecision:"UNKNOWN",dateOfBirth:null}});
  assert.deepEqual(Object.keys(result.proposedDto).sort(),["birthDatePrecision","dateOfBirth","displayName"]);
});
test("REG02A-02 FULL maps to EXACT ISO date",()=>{
  assert.deepEqual(proposed({name:"Synthetic Contract Exact",precision:"FULL",dob:"2001-04-12"}),
    {ok:true,proposedDto:{displayName:"Synthetic Contract Exact",birthDatePrecision:"EXACT",dateOfBirth:"2001-04-12"}});
});
test("REG02A-03 all nonempty phone values block, rather than being silently dropped",()=>{
  for(const phone of ["9999999999"," 9999999999 ","invalid","  123  "])
    assert.deepEqual(proposed({phone}),{ok:false,reason:"UNSUPPORTED_PHONE"});
});
test("REG02A-04 invalid names and dates fail closed",()=>{
  for(const input of [{name:""},{name:"x"},{name:"<script>"},{name:"x".repeat(101)},
    {precision:"FULL",dob:""},{precision:"FULL",dob:"2026-02-30"},
    {precision:"FULL",dob:"2099-01-01"},{precision:"UNKNOWN",dob:"2000-01-01"}])
    assert.deepEqual(proposed(input),{ok:false,reason:"INVALID_INTAKE"});
});
test("REG02A-05 unsupported runtime precision fails closed",()=>{
  assert.deepEqual(proposed({precision:"ESTIMATED"}),{ok:false,reason:"UNSUPPORTED_PRECISION"});
});
test("REG02A-06 mapping never mutates original intake",()=>{
  const original=Object.freeze({name:" Synthetic   Person ",precision:"FULL",dob:"2000-02-29",phone:""});
  const before={...original};
  assert.equal(previewRegistrationDto(original,"2026-11-09").ok,true);
  assert.deepEqual(original,before);
});
test("REG02A-07 simulated 201/200 do not create a demo record or expose an MRN",()=>{
  for(const status of [201,200]){
    const result=previewSyntheticOutcome(status);
    assert.equal(result.outcome,status===201?"CREATED_NOT_LINKED":"REPLAYED_NOT_LINKED");
    assert.equal(result.canUpdateReceptionQueue,false);
    assert.equal(result.canDisplayCandidateIdentities,false);
    assert.equal("patientId" in result,false);
    assert.equal("mrn" in result,false);
  }
});
test("REG02A-08 409 duplicate review stays blocked without candidate data or auto-merge",()=>{
  const v=previewSyntheticOutcome(409,"DUPLICATE_REVIEW_REQUIRED");
  assert.deepEqual(v,{outcome:"DUPLICATE_REVIEW_BLOCKED",canUpdateReceptionQueue:false,canDisplayCandidateIdentities:false});
  assert.equal(previewSyntheticOutcome(409,"IDEMPOTENCY_CONFLICT").outcome,"IDEMPOTENCY_CONFLICT");
  assert.equal(previewSyntheticOutcome(409,"UNEXPECTED").outcome,"UNKNOWN_BLOCKED");
  assert.equal(previewSyntheticOutcome(409).outcome,"UNKNOWN_BLOCKED");
});
test("REG02A-09 deny and limitation statuses never update frontend queue",()=>{
  const byStatus=new Map([[400,"INVALID_REQUEST"],[401,"AUTH_REQUIRED"],[403,"PERMISSION_DENIED"],
    [413,"PAYLOAD_TOO_LARGE"],[429,"RATE_LIMITED"],[503,"UNAVAILABLE"]]);
  for(const [status,outcome] of byStatus){
    const v=previewSyntheticOutcome(status);
    assert.equal(v.outcome,outcome);
    assert.equal(v.canUpdateReceptionQueue,false);
  }
});
test("REG02A-10 no unknown success or failure response is treated as approved",()=>{
  for(const status of [0,202,204,302,404,408,422,500,502,504,999]){
    const v=previewSyntheticOutcome(status,"DUPLICATE_REVIEW_REQUIRED");
    assert.equal(v.outcome,"UNKNOWN_BLOCKED");
    assert.equal(v.canUpdateReceptionQueue,false);
  }
});
