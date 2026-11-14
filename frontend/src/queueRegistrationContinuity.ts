import type {RegistrationReference} from './registrationReferenceClient.ts';

/**
 * PILOT-08: a queue row is merely a navigation hint, NOT patient authority.
 * A fresh patient-registration read through the dedicated runtime SQL role
 * must return the same tenant, branch, registration and patient identifiers.
 * No demographic data, access tokens or browser persistence.
 */
export class QueueRegistrationMismatch extends Error {
  constructor(){
    super('This registration changed or is no longer accessible. Refresh the queue.');
    this.name='QueueRegistrationMismatch';
  }
}
type Link=Readonly<{
  tenantId:string;branchId:string;registrationId:string;patientId:string;
}>;
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const valid=(x:unknown):x is string=>typeof x==='string'
  &&uuid.test(x)&&x.toLowerCase()!=='00000000-0000-0000-0000-000000000000';

export function verifiedRegistrationFromQueue(
  queue:Link,
  authorized:RegistrationReference,
  tenantId:string,
  branchId:string,
):RegistrationReference{
  if(!queue||!authorized||!valid(tenantId)||!valid(branchId)
    ||!valid(queue.tenantId)||!valid(queue.branchId)
    ||!valid(queue.registrationId)||!valid(queue.patientId)
    ||!valid(authorized.tenantId)||!valid(authorized.branchId)
    ||!valid(authorized.registrationId)||!valid(authorized.patientId)
    ||queue.tenantId!==tenantId||queue.branchId!==branchId
    ||authorized.tenantId!==tenantId||authorized.branchId!==branchId
    ||queue.registrationId!==authorized.registrationId
    ||queue.patientId!==authorized.patientId)
    throw new QueueRegistrationMismatch();
  return Object.freeze({
    tenantId:authorized.tenantId,branchId:authorized.branchId,
    registrationId:authorized.registrationId,patientId:authorized.patientId,
  });
}
