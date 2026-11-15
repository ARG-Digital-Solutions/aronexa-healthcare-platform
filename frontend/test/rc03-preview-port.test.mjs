import test from 'node:test';
import assert from 'node:assert/strict';
import {rc03PreviewPort, verifyRc03Origin} from './browser/rc03-preview-port.ts';

test('RC03 defaults to 5176 and explicitly accepts only approved 5186 alternate',()=>{
  assert.equal(rc03PreviewPort(undefined),5176);
  assert.equal(rc03PreviewPort('5176'),5176);
  assert.equal(rc03PreviewPort('5186'),5186);
});
test('RC03 rejects malformed, unapproved and blank preview ports',()=>{
  for(const value of ['', '0','5187','65536','05186','5186x',' 5186','http://127.0.0.1:5186'])
    assert.throws(()=>rc03PreviewPort(value));
});
test('RC03 origin must exactly match independently configured loopback port',()=>{
  assert.equal(verifyRc03Origin('http://127.0.0.1:5186/',5186).port,'5186');
  assert.equal(verifyRc03Origin('http://127.0.0.1:5176/',5176).port,'5176');
  for(const value of ['http://127.0.0.1:5176/','http://localhost:5186/',
    'https://127.0.0.1:5186/','http://user:secret@127.0.0.1:5186/',
    'http://127.0.0.1:5186/path','http://127.0.0.1:5186/?q=1',
    'http://127.0.0.1:5186/#fragment','http://192.0.2.1:5186/'])
    assert.throws(()=>verifyRc03Origin(value,5186));
});
