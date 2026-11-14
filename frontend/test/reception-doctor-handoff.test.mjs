import test from 'node:test';
import assert from 'node:assert/strict';
import {prepareDoctorHandoff,doctorCanReceiveHandoff} from '../src/receptionDoctorHandoff.ts';

const id=n=>'11111111-1111-4111-8111-'+String(n).padStart(12,'0');
const receipt={
  tenantId:id(1),branchId:id(2),patientId:id(3),registrationId:id(4),
  appointmentId:id(5),doctorMembershipId:id(6),
  stage:'CHECKED_IN',revision:2,visitDate:'2026-11-14',
  slotStart:'09:00:00',replayed:false,
};
const reception={tenantId:id(1),permissions:['APPOINTMENT_CHECK_IN']};
const doctor={tenantId:id(1),membershipId:id(6),permissions:['OPD_DRAFT_WRITE']};

test('checked-in verified receipt yields frozen minimal handoff, no credentials',()=>{
  const value=prepareDoctorHandoff(receipt,reception);
  assert.deepEqual(Object.keys(value).sort(),[
    'appointmentId','branchId','doctorMembershipId','patientId','tenantId',
  ]);
  assert.equal(Object.isFrozen(value),true);
  assert.equal(value.appointmentId,receipt.appointmentId);
  assert.equal(JSON.stringify(value).includes('accessToken'),false);
  assert.equal(doctorCanReceiveHandoff(value,doctor,id(2)),true);
});
test('booking, cancellation, wrong tenant and missing check-in permission deny',()=>{
  for(const stage of ['BOOKED','CANCELLED']){
    assert.throws(()=>prepareDoctorHandoff({...receipt,stage},reception));
  }
  assert.throws(()=>prepareDoctorHandoff(receipt,{...reception,tenantId:id(8)}));
  assert.throws(()=>prepareDoctorHandoff(receipt,{...reception,permissions:[]}));
});
test('wrong doctor, tenant, branch and permission fail closed before opening draft',()=>{
  const value=prepareDoctorHandoff(receipt,reception);
  assert.equal(doctorCanReceiveHandoff(value,{...doctor,membershipId:id(9)},id(2)),false);
  assert.equal(doctorCanReceiveHandoff(value,{...doctor,tenantId:id(9)},id(2)),false);
  assert.equal(doctorCanReceiveHandoff(value,doctor,id(9)),false);
  assert.equal(doctorCanReceiveHandoff(value,{...doctor,permissions:[]},id(2)),false);
  assert.equal(doctorCanReceiveHandoff({...value,patientId:'bad'},doctor,id(2)),false);
});
