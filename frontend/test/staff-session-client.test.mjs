import test from 'node:test';
import assert from 'node:assert/strict';
import {
  signInStaff, verifyStaffSession, revokeStaffToken, StaffSessionError,
} from '../src/staffSessionClient.ts';

const id = n => '11111111-1111-4111-8111-' + String(n).padStart(12,'0');
const token = 'A'.repeat(43);
const login = {tenantCode:' TEST_CLINIC ', username:' RECEPTION ',password:'Synthetic-Strong-Password!1'};
const member = {
  tenantId:id(1), userId:id(2), membershipId:id(3),
  username:'reception', mustChangePassword:false,
  roles:['RECEPTION'], permissions:['PATIENT_REGISTER','PATIENT_REGISTRATION_READ'],
};
const json=(body,status=200)=>new Response(JSON.stringify(body),{status});
const accepted = {accessToken:token,tokenType:'Bearer',expiresInSeconds:1800,mustChangePassword:false};
const errors=(error,code)=>error instanceof StaffSessionError &&
  error.code===code &&
  !/Synthetic-Strong|11111111|Bearer A|sensitive/.test(error.message);

test('staff login requires independently verified /me and never stores credentials',async()=>{
  const calls=[];
  const transport=async(path,options)=>{
    calls.push({path,options});
    return path.endsWith('/login')?json(accepted):json(member);
  };
  const session=await signInStaff(login,transport);
  assert.equal(calls.length,2);
  assert.equal(calls[0].path,'/api/v1/auth/login');
  assert.deepEqual(JSON.parse(calls[0].options.body),{
    tenantCode:'test_clinic',username:'reception',password:login.password,
  });
  assert.equal(calls[0].options.headers.Authorization,undefined);
  assert.equal(calls[0].options.credentials,'omit');
  assert.equal(calls[0].options.cache,'no-store');
  assert.equal(calls[1].path,'/api/v1/auth/me');
  assert.equal(calls[1].options.headers.Authorization,'Bearer '+token);
  assert.equal(calls[1].options.method,'GET');
  assert.deepEqual(session,{
    tenantId:member.tenantId,userId:member.userId,membershipId:member.membershipId,
    username:member.username,roles:member.roles,permissions:member.permissions,
    accessToken:token,
  });
  assert.equal(Object.isFrozen(session),true);
  assert.equal(Object.isFrozen(session.permissions),true);
});

test('wrong password and rejected /me return safe codes, no server text',async()=>{
  await assert.rejects(signInStaff(login,async()=>json({error:'sensitive'},401)),
    err=>errors(err,'LOGIN_DENIED'));
  const calls=[];
  await assert.rejects(signInStaff(login,async(path,options)=>{
    calls.push({path,options});
    if(path.endsWith('/login'))return json(accepted);
    if(path.endsWith('/me'))return json({secret:'sensitive'},401);
    return json({},204);
  }),err=>errors(err,'SESSION_EXPIRED'));
  assert.deepEqual(calls.map(x=>x.path),[
    '/api/v1/auth/login','/api/v1/auth/me','/api/v1/auth/logout',
  ]);
  assert.equal(calls[2].options.headers.Authorization,'Bearer '+token);
});

test('forced password change is denied and server token revoked',async()=>{
  const paths=[];
  await assert.rejects(signInStaff(login,async(path)=>{
    paths.push(path);
    return path.endsWith('/login')?
      json({...accepted,mustChangePassword:true}):
      new Response(null,{status:204});
  }),err=>errors(err,'PASSWORD_CHANGE_REQUIRED'));
  assert.deepEqual(paths,['/api/v1/auth/login','/api/v1/auth/logout']);
  await assert.rejects(verifyStaffSession(token,async()=>json({...member,mustChangePassword:true})),
    err=>errors(err,'PASSWORD_CHANGE_REQUIRED'));
});

test('invalid account inputs never send any HTTP request',async()=>{
  for (const credentials of [
    {...login,tenantCode:'../demo'},
    {...login,username:'x'},
    {...login,password:''},
    null,
  ]) {
    await assert.rejects(signInStaff(credentials,()=>assert.fail('No HTTP')),
      err=>errors(err,'INVALID_LOGIN'));
  }
});

test('malformed bearer, missing tenant IDs or weird permission response fail closed',async()=>{
  await assert.rejects(verifyStaffSession('bad',()=>assert.fail('No HTTP')),
    err=>errors(err,'SESSION_EXPIRED'));
  for(const body of [
    {...member,tenantId:'DEMO-TENANT'},
    {...member,permissions:['PATIENT_REGISTER','\nFAKE']},
    {...member,roles:'RECEPTION'},
    {...member,username:42},
    {...member,mustChangePassword:null},
  ]) {
    await assert.rejects(verifyStaffSession(token,async()=>json(body)),
      err=>errors(err,'SESSION_UNVERIFIED'));
  }
});

test('network and non-JSON errors never reveal patient or connection details',async()=>{
  await assert.rejects(signInStaff(login,async()=>{throw Error('sensitive network details')}),
    err=>errors(err,'NETWORK_UNAVAILABLE'));
  await assert.rejects(verifyStaffSession(token,async()=>new Response('not-json')),
    err=>errors(err,'SESSION_UNVERIFIED'));
});

test('logout is explicit and never retries, persists or leaks server errors',async()=>{
  let seen;
  await revokeStaffToken(token,async(path,options)=>{
    seen={path,options};
    return new Response(null,{status:204});
  });
  assert.equal(seen.path,'/api/v1/auth/logout');
  assert.equal(seen.options.method,'POST');
  assert.equal(seen.options.headers.Authorization,'Bearer '+token);
  assert.equal(seen.options.credentials,'omit');
  await revokeStaffToken(token,async()=>{throw Error('sensitive failure')});
});
