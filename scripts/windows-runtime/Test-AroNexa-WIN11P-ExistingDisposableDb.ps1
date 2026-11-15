# WIN-11P: read-only acceptance of the specifically named owner-provisioned disposable PG17 database.
# No role creation, Flyway execution, synthetic records, launcher activation, or customer data.
[CmdletBinding()]
param(
  [string]$Container='aronexa-win11p-loopback-qa',
  [string]$Database='aronexa_win11p_loopback_qa',
  [string]$ExpectedBinding='127.0.0.1:49742',
  [switch]$CheckHttp401
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'

# Deliberately refuse caller-supplied alternate DB/container names.
if($Container -cne 'aronexa-win11p-loopback-qa' -or
   $Database -cne 'aronexa_win11p_loopback_qa' -or
   $ExpectedBinding -cne '127.0.0.1:49742'){
  throw 'WIN11P STOP: only the exact approved fictional loopback database is permitted'
}
$raw=@(& docker inspect $Container)
if($LASTEXITCODE -ne 0 -or @($raw).Count -eq 0){throw 'WIN11P STOP: Docker inspect failed'}
$info=@(($raw -join "`n" | ConvertFrom-Json))[0]
if($info.Name -cne '/aronexa-win11p-loopback-qa' -or
   $info.Config.Labels.'aronexa.qa' -cne 'disposable-synthetic-only'){
  throw 'WIN11P STOP: container name or synthetic-only label mismatch'
}
$binding=@(& docker port $Container '5432/tcp')
if($LASTEXITCODE -ne 0 -or @($binding).Count -ne 1 -or
   ([string]$binding[0]).Trim() -cne $ExpectedBinding){
  throw 'WIN11P STOP: expected a single loopback-only port binding'
}
# Identity must be verified independently before any schema query.
$identity=@(& docker exec $Container psql -X -t -A -v ON_ERROR_STOP=1 -U postgres -d $Database -c 'SELECT current_database(), current_user;')
if($LASTEXITCODE -ne 0 -or (($identity -join '').Trim()) -cne 'aronexa_win11p_loopback_qa|postgres'){
  throw 'WIN11P STOP: database connection identity mismatch'
}
# Read-only SQL; output is one summary row for deterministic and secret-free validation.
$sql=@'
SELECT
  (SELECT count(*) FROM public.flyway_schema_history WHERE version ~ '^[1-9]$' AND success),
  (SELECT count(*) FROM public.flyway_schema_history WHERE version IS NOT NULL),
  (SELECT count(*) FROM pg_roles
     WHERE rolname IN ('aronexa_app_runtime','aronexa_auth_runtime',
                       'aronexa_patient_runtime','aronexa_registration_writer')
       AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
       AND NOT rolinherit AND NOT rolbypassrls),
  (SELECT count(*) FROM pg_roles
     WHERE rolname = 'aronexa_dev' AND rolcanlogin
       AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
       AND NOT rolinherit AND NOT rolbypassrls),
  (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
     WHERE n.nspname='public' AND c.relname IN
       ('tenant','facility','branch','department','patient','patient_identifier',
        'patient_contact','patient_relationship','patient_registration','patient_identity_audit')
       AND c.relkind='r' AND c.relrowsecurity),
  (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
     WHERE n.nspname='public' AND c.relname IN
       ('facility','branch','department','patient','patient_identifier',
        'patient_contact','patient_relationship','patient_registration')
       AND c.relkind='r' AND c.relforcerowsecurity),
  has_table_privilege('aronexa_app_runtime','public.app_user','SELECT'),
  has_table_privilege('aronexa_auth_runtime','public.branch','SELECT'),
  has_table_privilege('aronexa_patient_runtime','public.patient_registration','SELECT'),
  has_table_privilege('aronexa_patient_runtime','public.patient','SELECT');
'@
$observed=@(& docker exec $Container psql -X -t -A -v ON_ERROR_STOP=1 -U postgres -d $Database -c $sql)
if($LASTEXITCODE -ne 0){throw 'WIN11P FAIL: database read-only SQL checks'}
$actual=($observed -join '').Trim()
$expected='9|9|4|1|10|8|f|f|t|f'
if($actual -cne $expected){
  throw ('WIN11P FAIL: database baseline differs. Observed='+$actual+' Expected='+$expected)
}
Write-Host 'PASS: disposable DB identity, Flyway V1-V9, restricted roles, RLS configuration and privileges' -ForegroundColor Green
if($CheckHttp401){
  $status=$null
  try {
    $r=Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/v1/auth/me' -UseBasicParsing -TimeoutSec 5
    $status=[int]$r.StatusCode
  } catch {
    if($null -ne $_.Exception.Response){$status=[int]$_.Exception.Response.StatusCode}
    else {throw 'WIN11P FAIL: HTTP auth endpoint was unreachable'}
  }
  if($status -ne 401){throw ('WIN11P FAIL: expected HTTP 401, got '+$status)}
  Write-Host 'PASS: localhost unauthenticated auth/me returns HTTP 401' -ForegroundColor Green
}
Write-Host 'Classification=LOCAL_DATABASE_SMOKE_ONLY; ReceptionE2E=NOT_PROVEN; CustomerRelease=NO_GO' -ForegroundColor Yellow
