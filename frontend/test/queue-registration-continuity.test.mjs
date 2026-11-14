import test from 'node:test';
import assert from 'node:assert/strict';
import {verifiedRegistrationFromQueue,QueueRegistrationMismatch}
  from '../src/queueRegistrationContinuity.ts';

const id=n=>'22222222-2222-4222-8222-'+String(n).padStart(12,'0');
const linked=Object.freeze({tenantId:id(1),branchId:id(2),
  registrationId:id(3),patientId:id(4)});
const fakeQueue=Object.freeze({...linked,appointmentId:id(5),
  doctorMembershipId:id(6),stage:'BOOKED',revision:1});

test('same authoritative registration and queue row navigates without user UUID',()=>{
  const resolved=verifiedRegistrationFromQueue(
    fakeQueue,linked,id(1),id(2));
  assert.deepEqual(resolved,linked);
  assert.equal(Object.isFrozen(resolved),true);
  assert.equal(Object.hasOwn(resolved,'appointmentId'),false);
  assert.equal(Object.hasOwn(resolved,'doctorMembershipId'),false);
});

test('stale registration or changed patient identity denies rather than guessing',()=>{
  for(const tampered of [
    {...linked,tenantId:id(8)},
    {...linked,branchId:id(8)},
    {...linked,registrationId:id(8)},
    {...linked,patientId:id(8)},
    {...linked,patientId:'00000000-0000-0000-0000-000000000000'},
  ])assert.throws(()=>verifiedRegistrationFromQueue(
    fakeQueue,tampered,id(1),id(2)),QueueRegistrationMismatch);
});

test('queue row and current signed-in tenant/branch both independently match',()=>{
  for(const tampered of [
    {...fakeQueue,tenantId:id(8)},
    {...fakeQueue,branchId:id(8)},
    {...fakeQueue,registrationId:id(8)},
    {...fakeQueue,patientId:id(8)},
  ])assert.throws(()=>verifiedRegistrationFromQueue(
    tampered,linked,id(1),id(2)),QueueRegistrationMismatch);
  assert.throws(()=>verifiedRegistrationFromQueue(
    fakeQueue,linked,id(8),id(2)),QueueRegistrationMismatch);
  assert.throws(()=>verifiedRegistrationFromQueue(
    fakeQueue,linked,id(1),id(8)),QueueRegistrationMismatch);
});

test('never trust missing or malformed registration linkage',()=>{
  assert.throws(()=>verifiedRegistrationFromQueue(
    null,linked,id(1),id(2)),QueueRegistrationMismatch);
  assert.throws(()=>verifiedRegistrationFromQueue(
    fakeQueue,null,id(1),id(2)),QueueRegistrationMismatch);
  assert.throws(()=>verifiedRegistrationFromQueue(
    fakeQueue,{...linked,patientId:'synthetic'},id(1),id(2)),
    QueueRegistrationMismatch);
});
