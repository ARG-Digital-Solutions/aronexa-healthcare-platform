import type {DemoPatient} from "./receptionModel.ts";
export const doctors = [{id:"SYN-D01",name:"Synthetic Doctor A"},{id:"SYN-D02",name:"Synthetic Doctor B"}] as const;
export const slots = Array.from({length:32},(_,i)=>`${String(9+Math.floor(i/4)).padStart(2,"0")}:${String(i%4*15).padStart(2,"0")}`);
export type AppointmentStatus = "Booked" | "Checked in" | "Cancelled";
export interface Booking {patientId:string; doctorId:string; branch:string; date:string; slot:string}
export interface Appointment extends Booking {id:string; status:AppointmentStatus; history:readonly string[]}
export type Result = {ok:true; appointments:Appointment[]; id:string} | {ok:false; error:string};
export function validDate(date:string):boolean {
 return /^\d{4}-\d{2}-\d{2}$/.test(date) && date>="0001-01-01" && !Number.isNaN(Date.parse(date)) && new Date(date).toISOString().slice(0,10)===date;
}
function validate(v:Booking,patients:readonly DemoPatient[],today:string):string {
 if(!patients.some(p=>p.id===v.patientId && p.branch===v.branch))return "Select a valid session patient and matching demo branch.";
 if(!doctors.some(d=>d.id===v.doctorId))return "Select a synthetic doctor.";
 if(!validDate(today) || !validDate(v.date) || v.date<today)return "Select a valid local-calendar date today or later.";
 if(!slots.includes(v.slot))return "Select a simulated 15-minute slot between 09:00 and 16:45.";
 return "";
}
const occupied=(all:readonly Appointment[],v:Booking,except="")=>all.some(a=>a.id!==except && a.status!=="Cancelled" && a.branch===v.branch && a.doctorId===v.doctorId && a.date===v.date && a.slot===v.slot);
export function book(all:readonly Appointment[],v:Booking,patients:readonly DemoPatient[],today:string):Result {
 const error=validate(v,patients,today);
 if(error)return {ok:false,error};
 if(occupied(all,v))return {ok:false,error:"That synthetic doctor slot is occupied in this branch."};
 const id=`DEMO-APT-${String(all.reduce((max,a)=>Math.max(max,Number(a.id.replace("DEMO-APT-",""))||0),0)+1).padStart(4,"0")}`;
 return {ok:true,id,appointments:[...all,{...v,id,status:"Booked",history:[`Booked: ${v.date} ${v.slot} (${v.doctorId})`]}]};
}
export function act(all:readonly Appointment[],id:string,action:"reschedule"|"cancel"|"checkIn",replacement:Booking,patients:readonly DemoPatient[],today:string):Result {
 const current=all.find(a=>a.id===id);
 if(!current)return {ok:false,error:"Invalid demo appointment ID."};
 if(current.status!=="Booked")return {ok:false,error:"Only booked appointments allow this action; repeated actions are rejected."};
 if(!["reschedule","cancel","checkIn"].includes(action))return {ok:false,error:"Invalid action."};
 let next:Appointment;
 if(action==="reschedule"){
  if(replacement.patientId!==current.patientId || replacement.branch!==current.branch)return {ok:false,error:"Rescheduling cannot change patient or branch."};
  const error=validate(replacement,patients,today);
  if(error)return {ok:false,error};
  if(current.date===replacement.date && current.slot===replacement.slot && current.doctorId===replacement.doctorId)return {ok:false,error:"Choose a different appointment slot or doctor."};
  if(occupied(all,replacement,id))return {ok:false,error:"Replacement slot is occupied."};
  next={...current,...replacement,history:[...current.history,`Rescheduled from ${current.date} ${current.slot} (${current.doctorId}) to ${replacement.date} ${replacement.slot} (${replacement.doctorId})`]};
 }else{
  if(!patients.some(p=>p.id===current.patientId && p.branch===current.branch))return {ok:false,error:"Invalid session patient."};
  if(!validDate(today) || (action==="checkIn" && current.date!==today))return {ok:false,error:"Check-in requires the appointment's local-calendar date."};
  next={...current,status:action==="cancel"?"Cancelled":"Checked in",history:[...current.history,`${action==="cancel"?"Cancelled":"Reception check-in"}: ${today}`]};
 }
 return {ok:true,id,appointments:all.map(a=>a.id===id?next:a)};
}
export function filterAppointments(all:readonly Appointment[],filters:{date:string;doctorId:string;status:string;query:string},patients:readonly DemoPatient[]):Appointment[]{
 const q=filters.query.trim().toLowerCase();
 return all.filter(a=>(!filters.date||a.date===filters.date)&&(!filters.doctorId||a.doctorId===filters.doctorId)&&(!filters.status||a.status===filters.status)&&[a.id,a.patientId,patients.find(p=>p.id===a.patientId)?.name??""].some(s=>s.toLowerCase().includes(q)));
}
