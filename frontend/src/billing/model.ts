/** Synthetic-only OPD billing ledger; no API, DB, payments or issued legal receipts. */
export type FinanceRole='billing'|'admin'|'doctor'|'receptionist';
export interface Access {role:FinanceRole; tenantId:string;branchId:string;actorId:string}
export interface Line {code:string;label:string;amountPaise:number}
export type EntryType='PAYMENT'|'REFUND';
export interface MoneyEvent {id:string;kind:EntryType;amountPaise:number;date:string;actorId:string;againstPaymentId?:string}
export interface Invoice {id:string;tenantId:string;branchId:string;syntheticPatientId:string;doctorId:string;date:string;lines:Line[];events:MoneyEvent[];status:'SYNTHETIC_ONLY'}
export const DEMO_ACCESS:Access={role:'billing',tenantId:'DEMO-TENANT',branchId:'DEMO-BRANCH',actorId:'DEMO-STAFF'};
function fail(m:string):never{throw new Error(m)}
function textValue(v:unknown,label:string,max=100):string{if(typeof v!=='string'||!v.trim()||v.trim().length>max)fail('Invalid '+label);return v.trim()}
export function validPaise(n:number){return Number.isSafeInteger(n)&&n>0&&n<=10000000000}
export function validateDate(date:string){if(typeof date!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(date)||Number.isNaN(Date.parse(date+'T00:00:00Z'))||new Date(date+'T00:00:00Z').toISOString().slice(0,10)!==date)fail('Invalid date');return date}
function guard(access:Access,invoice?:Invoice){if(!['billing','admin'].includes(access.role))fail('Billing access denied');if(!access.tenantId||!access.branchId||!access.actorId)fail('Invalid actor context');if(invoice&&(invoice.tenantId!==access.tenantId||invoice.branchId!==access.branchId||invoice.status!=='SYNTHETIC_ONLY'))fail('Billing context mismatch')}
function copy(i:Invoice):Invoice{return {...i,lines:i.lines.map(x=>({...x})),events:i.events.map(x=>({...x}))}}
function amount(xs:Line[]):number{let total=0;for(const x of xs){total+=x.amountPaise;if(!Number.isSafeInteger(total)||total>10000000000)fail('Total exceeds supported range')}return total}
export function createDraft(access:Access,info:{id:string;patientId:string;doctorId:string;date:string;lines:Line[]}):Invoice{
 guard(access);if(!Array.isArray(info.lines)||!info.lines.length||info.lines.length>30)fail('Invalid line count');
 const lines=info.lines.map(x=>{if(!validPaise(x.amountPaise))fail('Invalid amount');return {code:textValue(x.code,'service code',40),label:textValue(x.label,'service label'),amountPaise:x.amountPaise}});
 amount(lines);
 return {id:textValue(info.id,'invoice ID',80),tenantId:access.tenantId,branchId:access.branchId,syntheticPatientId:textValue(info.patientId,'patient',80),doctorId:textValue(info.doctorId,'doctor',80),date:validateDate(info.date),lines,events:[],status:'SYNTHETIC_ONLY'};
}
export function totals(invoice:Invoice){const charged=amount(invoice.lines);let paid=0,refunded=0;for(const e of invoice.events){if(e.kind==='PAYMENT')paid+=e.amountPaise;else refunded+=e.amountPaise}return {charged,paid,refunded,balance:charged-paid+refunded}}
export function postPayment(access:Access,invoice:Invoice,payment:{id:string;amountPaise:number;date:string}):Invoice{
 guard(access,invoice);const id=textValue(payment.id,'payment ID',80);const date=validateDate(payment.date);
 if(invoice.events.some(x=>x.id===id))fail('Duplicate event ID');if(!validPaise(payment.amountPaise))fail('Invalid payment');
 if(payment.amountPaise>totals(invoice).balance)fail('Payment exceeds outstanding balance');
 const result=copy(invoice);result.events.push({id,kind:'PAYMENT',amountPaise:payment.amountPaise,date,actorId:access.actorId});return result;
}
export function postRefund(access:Access,invoice:Invoice,refund:{id:string;paymentId:string;amountPaise:number;date:string}):Invoice{
 guard(access,invoice);const id=textValue(refund.id,'refund ID',80),date=validateDate(refund.date);
 const original=invoice.events.find(e=>e.kind==='PAYMENT'&&e.id===refund.paymentId);
 if(!original)fail('Original payment not found');if(invoice.events.some(x=>x.id===id))fail('Duplicate event ID');if(!validPaise(refund.amountPaise))fail('Invalid refund');
 const already=invoice.events.filter(e=>e.kind==='REFUND'&&e.againstPaymentId===original.id).reduce((sum,e)=>sum+e.amountPaise,0);
 if(already+refund.amountPaise>original.amountPaise)fail('Refund exceeds original payment');
 const result=copy(invoice);result.events.push({id,kind:'REFUND',amountPaise:refund.amountPaise,date,actorId:access.actorId,againstPaymentId:original.id});return result;
}
export function dailyReport(access:Access,invoices:Invoice[],date:string){
 guard(access);validateDate(date);if(!Array.isArray(invoices))fail('Invalid invoices');
 const ids=new Set<string>();let invoiced=0,collected=0,refunded=0,visitCount=0;
 for(const invoice of invoices){
  guard(access,invoice);if(ids.has(invoice.id))fail('Duplicate invoice');ids.add(invoice.id);
  if(invoice.date===date){invoiced+=totals(invoice).charged;visitCount++;}
  for(const e of invoice.events){if(e.date!==date)continue;if(e.kind==='PAYMENT')collected+=e.amountPaise;else refunded+=e.amountPaise;}
 }
 return {date,tenantId:access.tenantId,branchId:access.branchId,invoiceCount:visitCount,invoicedPaise:invoiced,paymentsPaise:collected,refundsPaise:refunded,netCollectionsPaise:collected-refunded};
}
export function formatINR(paise:number):string{if(!Number.isSafeInteger(paise))fail('Invalid amount');return '₹'+(paise/100).toFixed(2)}
