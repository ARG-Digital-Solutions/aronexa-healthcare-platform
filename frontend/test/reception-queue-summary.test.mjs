import test from 'node:test';
import assert from 'node:assert/strict';
import {summarizeReceptionQueue} from '../src/receptionQueueSummary.ts';

test('empty server-authorized queue displays zero counts',()=>{
  assert.deepEqual(summarizeReceptionQueue([]),{total:0,booked:0,checkedIn:0});
});

test('waiting and checked-in counts derive exclusively from fetched rows',()=>{
  const rows=[{stage:'BOOKED'},{stage:'CHECKED_IN'},{stage:'BOOKED'}];
  const value=summarizeReceptionQueue(rows);
  assert.deepEqual(value,{total:3,booked:2,checkedIn:1});
  assert.equal(Object.isFrozen(value),true);
});

test('cancelled or unsupported entries are refused instead of silently counted',()=>{
  assert.throws(()=>summarizeReceptionQueue([{stage:'CANCELLED'}]));
  assert.throws(()=>summarizeReceptionQueue([{stage:'FUTURE'}]));
});

test('does not mutate fetched rows or store identifiers',()=>{
  const rows=Object.freeze([
    Object.freeze({stage:'BOOKED',patientId:'fictional-id'}),
    Object.freeze({stage:'CHECKED_IN',appointmentId:'fictional-appointment'}),
  ]);
  const result=summarizeReceptionQueue(rows);
  assert.deepEqual(Object.keys(result).sort(),['booked','checkedIn','total']);
  assert.equal(JSON.stringify(result).includes('fictional'),false);
});
