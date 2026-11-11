/** P01-T07-C1 — synthetic, in-memory UI demonstration ONLY. No patient backend. */
export type Precision = "UNKNOWN" | "FULL";
export type Arrival = "Waiting" | "Registered" | "In consultation";
export interface DemoPatient { id: string; name: string; dob: string; phone: string; branch: string; status: Arrival; time: string; }
export interface Intake { name: string; precision: Precision; dob: string; phone: string; }
export const DEMO_BRANCH = "Pimpri Clinic · Demo Branch";
export const seedPatients: DemoPatient[] = [
  {id:"DEMO-0001",name:"Anaya Kulkarni",dob:"1992-06-18",phone:"9000000001",branch:DEMO_BRANCH,status:"Waiting",time:"09:05"},
  {id:"DEMO-0002",name:"Rohan Deshpande",dob:"1986-11-04",phone:"9000000002",branch:DEMO_BRANCH,status:"In consultation",time:"09:12"},
  {id:"DEMO-0003",name:"Meera Shah",dob:"1978-02-21",phone:"9000000003",branch:DEMO_BRANCH,status:"Waiting",time:"09:20"},
  {id:"DEMO-0004",name:"Aarav Patil",dob:"2015-08-09",phone:"9000000004",branch:DEMO_BRANCH,status:"Registered",time:"09:30"}
];
export const blankIntake = (): Intake => ({name:"",precision:"UNKNOWN",dob:"",phone:""});
export function localCalendarDate(now=new Date()):string {
  return `${String(now.getFullYear()).padStart(4,"0")}-${String(now.getMonth()+1).padStart(2,"0")}-${String(now.getDate()).padStart(2,"0")}`;
}
export function normalizeIntake(v:Intake):Intake {
  return {...v,name:v.name.trim().replace(/\s+/g," "),phone:v.phone.trim()};
}
export function validateIntake(input:Intake, today=localCalendarDate()): Record<string,string> {
  const v=normalizeIntake(input);
  const errors:Record<string,string> = {};
  const name=v.name;
  if(name.length<2 || name.length>100 || !/[\p{L}]/u.test(name) || /[<>]/.test(name)) errors.name="Enter a valid patient name (2–100 characters).";
  if(v.precision==="FULL") {
    if(!/^\d{4}-\d{2}-\d{2}$/.test(v.dob) || Number.isNaN(Date.parse(v.dob)) || new Date(v.dob).toISOString().slice(0,10)!==v.dob || v.dob>today) errors.dob="Enter a valid date of birth that is not in the future.";
  } else if(v.dob) errors.dob="Remove date of birth when it is unknown.";
  if(v.phone && !/^\d{10}$/.test(v.phone.trim())) errors.phone="Enter 10 digits or leave the phone blank.";
  return errors;
}
const norm=(v:string)=>v.trim().replace(/\s+/g," ").toLocaleLowerCase("en-IN");
export function candidateMatches(v:Intake, patients:DemoPatient[]):DemoPatient[] {
  v=normalizeIntake(v);
  const query=norm(v.name);
  if(query.length<2) return [];
  return patients.filter(p=>norm(p.name)===query && (v.precision!=="FULL" || !v.dob || p.dob===v.dob)).slice(0,5);
}
export function maskPhone(phone:string):string {return phone ? "******"+phone.slice(-4) : "Not provided";}
export function demoRegistration(v:Intake, ordinal:number, now="09:45"):DemoPatient {
  v=normalizeIntake(v);
  if(Object.keys(validateIntake(v)).length) throw new Error("Invalid intake");
  return {id:"DEMO-"+String(ordinal).padStart(4,"0"),name:v.name,dob:v.precision==="FULL"?v.dob:"",phone:v.phone,branch:DEMO_BRANCH,status:"Registered",time:now};
}
