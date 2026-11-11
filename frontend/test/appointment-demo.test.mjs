import test from 'node:test';
import assert from 'node:assert/strict';
import {book,act,slots,validDate,filterAppointments} from '../src/appointmentModel.ts';
import {seedPatients,DEMO_BRANCH,localCalendarDate,demoRegistration} from '../src/receptionModel.ts';
const today='2026-11-08';
const draft={patientId:seedPatients[0].id,doctorId:'SYN-D01',branch:DEMO_BRANCH,date:today,slot:'09:00'};
const booking=(all=[],v=draft,patients=seedPatients)=>{const r=book(all,v,patients,today);assert.equal(r.ok,true,r.error);return r.appointments;};
const action=(all,id,kind,v=all[0],patients=seedPatients)=>act(all,id,kind,v,patients,today);
test('A01 newly registered session patient can book immutably',()=>{
 const patients=[...seedPatients,demoRegistration({name:'Synthetic Person',precision:'UNKNOWN',dob:'',phone:''},5)];const before=structuredClone(patients);
 assert.equal(booking([],{...draft,patientId:patients.at(-1).id},patients)[0].patientId,patients.at(-1).id);assert.deepEqual(patients,before);
});
for(const [name,patch] of [['unknown patient',{patientId:'missing'}],['branch mismatch',{branch:'Other'}],['unknown doctor',{doctorId:'missing'}],['past date',{date:'2026-11-07'}],['invalid date',{date:'2026-02-30'}],['malformed date',{date:'2026-1-08'}],['empty date',{date:''}],['invalid slot',{slot:'09:07'}],['end of day',{slot:'17:00'}]])test(`A02/A08 rejects ${name}`,()=>assert.equal(book([],{...draft,...patch},seedPatients,today).ok,false));
test('A02 calendar and slot boundaries',()=>{
 assert.equal(validDate('2024-02-29'),true);assert.equal(validDate('2025-02-29'),false);assert.equal(validDate('0000-01-01'),false);assert.equal(localCalendarDate(new Date(2026,10,8,0,1)),today);assert.equal(slots.length,32);assert.equal(slots[0],'09:00');assert.equal(slots.at(-1),'16:45');assert.equal(book([],draft,seedPatients,'invalid').ok,false);
});
test('A03 unique DEMO tokens after cancellation',()=>{const all=booking();const r=action(all,all[0].id,'cancel');assert.equal(r.ok,true);const next=booking(r.appointments);assert.equal(new Set(next.map(a=>a.id)).size,2);assert.match(next[1].id,/^DEMO-APT-/);});
test('A08 occupied slots include checked-in appointments',()=>{
 const all=booking();const before=structuredClone(all);assert.equal(book(all,{...draft,patientId:seedPatients[1].id},seedPatients,today).ok,false);const r=action(all,all[0].id,'checkIn');assert.equal(r.ok,true);assert.equal(book(r.appointments,draft,seedPatients,today).ok,false);assert.deepEqual(all,before);
});
test('A08 independent doctor, date and branch slots',()=>{
 let all=booking();all=booking(all,{...draft,doctorId:'SYN-D02'});all=booking(all,{...draft,date:'2026-11-09'});const patients=[...seedPatients,{...seedPatients[0],id:'DEMO-OTHER',branch:'Synthetic other branch'}];all=booking(all,{...draft,patientId:'DEMO-OTHER',branch:'Synthetic other branch'},patients);assert.equal(all.length,4);
});
test('A04 combined date, doctor, status and patient/token searches',()=>{
 const all=booking(booking(),{...draft,doctorId:'SYN-D02',date:'2026-11-09'});const before=structuredClone(all);const f={date:today,doctorId:'SYN-D01',status:'Booked',query:' anaya '};assert.equal(filterAppointments(all,f,seedPatients).length,1);for(const query of [all[0].id,all[0].patientId])assert.equal(filterAppointments(all,{...f,query},seedPatients).length,1);assert.equal(filterAppointments(all,{...f,status:'Cancelled'},seedPatients).length,0);assert.deepEqual(all,before);
});
test('A05 reschedule releases former slot and preserves immutable history',()=>{
 const all=booking();const before=structuredClone(all);const r=action(all,all[0].id,'reschedule',{...draft,slot:'09:15'});assert.equal(r.ok,true);assert.equal(r.appointments[0].history.length,2);assert.equal(booking(r.appointments).length,2);assert.deepEqual(all,before);
});
test('A05 rejects occupied, repeated, invalid replacements and identity changes',()=>{
 const all=booking(booking(),{...draft,slot:'09:15'});for(const patch of [{slot:'09:15'},{},{patientId:seedPatients[1].id},{branch:'Other'},{doctorId:'bad'},{date:'2026-02-30'},{slot:'09:01'},{date:'2026-11-07'}])assert.equal(action(all,all[0].id,'reschedule',{...draft,...patch}).ok,false);
});
test('A06 cancellation releases slot and retains history; repeats fail',()=>{
 const all=booking();const r=action(all,all[0].id,'cancel');assert.equal(r.ok,true);assert.equal(r.appointments[0].status,'Cancelled');assert.equal(r.appointments[0].history.length,2);assert.equal(booking(r.appointments).length,2);for(const kind of ['cancel','checkIn','reschedule'])assert.equal(action(r.appointments,all[0].id,kind,{...draft,slot:'10:00'}).ok,false);assert.equal(all[0].status,'Booked');
});
test('A07 check-in once on appointment day without clinical changes',()=>{
 const patients=structuredClone(seedPatients);const before=structuredClone(patients);const all=booking();const r=action(all,all[0].id,'checkIn',draft,patients);assert.equal(r.ok,true);assert.equal(r.appointments.filter(a=>a.status==='Checked in').length,1);assert.deepEqual(patients,before);for(const kind of ['checkIn','cancel','reschedule'])assert.equal(action(r.appointments,all[0].id,kind).ok,false);const future=booking([],{...draft,date:'2026-11-09'});assert.equal(action(future,future[0].id,'checkIn').ok,false);
});
test('A08 invalid appointment IDs, actions and patient IDs fail closed',()=>{
 const all=booking();for(const kind of ['cancel','checkIn','reschedule'])assert.equal(action(all,'missing',kind).ok,false);assert.equal(action(all,all[0].id,'unknown').ok,false);for(const kind of ['cancel','checkIn','reschedule'])assert.equal(action(all,all[0].id,kind,draft,[]).ok,false);
});
