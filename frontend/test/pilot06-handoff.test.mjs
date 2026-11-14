import test from 'node:test';
import assert from 'node:assert/strict';
import {prepareBillingHandoff,billingCanReceiveHandoff,billingContextMatchesHandoff}
  from '../src/pilot06Handoff.ts';
import {revokeStaffTokenAndConfirm} from '../src/staffSessionClient.ts';

const id=n=>'11111111-1111-4111-8111-'+String(n).padStart(12,'0');
const handoff=Object.freeze({
  tenantId:id(1),branchId:id(2),patientId:id(3),
  appointmentId:id(4),doctorMembershipId:id(5),
});
const doctor={
  tenantId:id(1),membershipId:id(5),accessToken:'A'.repeat(43),
  roles:['DOCTOR'],permissions:['OPD_DRAFT_WRITE','OPD_RX_DRAFT_WRITE'],
};
const rx={
  ...handoff,stage:'RX_DRAFT',revision:1,consultationRevision:1,
  medicines:[{id:id(8),medicine:'FICTIONAL ITEM'}],
};
const billing={
  tenantId:id(1),membershipId:id(6),roles:['BILLING'],
  permissions:['OPD_BILL_READ','OPD_DAILY_REPORT_READ'],
};
const context={
  ...handoff,serviceCode:'OPD-CONSULTATION',
  serviceLabel:'Fictional consultation',amountPaise:50000,
};

test('doctor can transfer only a saved immutable server Rx reference, not tokens',()=>{
  const next=prepareBillingHandoff(handoff,doctor,rx);
  assert.deepEqual(Object.keys(next).sort(),[
    'appointmentId','branchId','consultationRevision','doctorMembershipId',
    'patientId','prescriptionRevision','tenantId',
  ]);
  assert.equal(Object.isFrozen(next),true);
  assert.equal(JSON.stringify(next).includes(doctor.accessToken),false);
  assert.equal(billingCanReceiveHandoff(next,billing,id(2)),true);
  assert.equal(billingContextMatchesHandoff(next,context),true);
});

test('not-saved, empty and mismatched Rx cannot transfer',()=>{
  for(const candidate of [
    {...rx,stage:'EMPTY_DRAFT',revision:0},
    {...rx,medicines:[]},
    {...rx,patientId:id(9)},
    {...rx,doctorMembershipId:id(9)},
    {...rx,appointmentId:id(9)},
    {...rx,consultationRevision:0},
  ]) assert.throws(()=>prepareBillingHandoff(handoff,doctor,candidate));
  assert.throws(()=>prepareBillingHandoff(handoff,{
    ...doctor,permissions:['OPD_DRAFT_WRITE'],
  },rx));
});

test('billing role, tenant, branch and server identity must match',()=>{
  const next=prepareBillingHandoff(handoff,doctor,rx);
  for(const actor of [
    {...billing,tenantId:id(9)},
    {...billing,membershipId:id(5)},
    {...billing,roles:['DOCTOR']},
    {...billing,permissions:['OPD_BILL_READ']},
  ]) assert.equal(billingCanReceiveHandoff(next,actor,id(2)),false);
  assert.equal(billingCanReceiveHandoff(next,billing,id(9)),false);
  for(const value of [
    {...context,patientId:id(9)},
    {...context,appointmentId:id(9)},
    {...context,doctorMembershipId:id(9)},
    {...context,branchId:id(9)},
  ]) assert.equal(billingContextMatchesHandoff(next,value),false);
});

test('cross-role handoff verifies logout 204 and subsequent 401',async()=>{
  const seen=[];
  await revokeStaffTokenAndConfirm('A'.repeat(43),async(path,options)=>{
    seen.push({path,method:options.method});
    return {status:path.includes('logout')?204:401};
  });
  assert.deepEqual(seen,[
    {path:'/api/v1/auth/logout',method:'POST'},
    {path:'/api/v1/auth/me',method:'GET'},
  ]);
});

test('handoff refuses uncertain revocation, 200 /me or failing logout',async()=>{
  for(const [logout,probe] of [[503,401],[204,200],[204,403]]){
    await assert.rejects(
      revokeStaffTokenAndConfirm('A'.repeat(43),async(path)=>({
        status:path.includes('logout')?logout:probe,
      })),
      {code:'SESSION_UNVERIFIED'},
    );
  }
  await assert.rejects(
    revokeStaffTokenAndConfirm('A'.repeat(43),async()=>{throw Error('offline');}),
    {code:'SESSION_UNVERIFIED'},
  );
});
