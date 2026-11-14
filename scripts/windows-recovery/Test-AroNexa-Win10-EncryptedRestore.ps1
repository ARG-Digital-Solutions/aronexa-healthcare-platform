# WIN-10: fictional nonempty PG17 encrypted backup -> SECOND isolated PG17.
# Test-only: two auto-remove containers, --network none, tmpfs PGDATA,
# NO persistent Docker volumes or user/bind mounts.
# Not an original Windows DB operation, second PC or customer recovery.
[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)]
 [ValidatePattern('^[a-fA-F0-9]{40}$')]
 [string]$ExpectedCommit,
 [switch]$ConfirmFictionalIsolatedRecovery
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if(-not $ConfirmFictionalIsolatedRecovery){
 throw 'WIN10 STOP: require -ConfirmFictionalIsolatedRecovery'
}
if([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT){
 throw 'WIN10 STOP: run only on Windows internal QA PC'
}
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$sha=(& git -C $repo rev-parse HEAD).Trim().ToLowerInvariant()
if($LASTEXITCODE -ne 0 -or $sha -ne $ExpectedCommit.ToLowerInvariant()){
 throw 'WIN10 STOP: expected exact source SHA mismatch'
}
$dirty=@(& git -C $repo status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or @($dirty | Where-Object {$_.Trim()}).Count -ne 0){
 throw 'WIN10 STOP: tracked source changes present'
}
foreach($key in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
 'SPRING_DATASOURCE_PASSWORD','SPRING_PROFILES_ACTIVE','PGHOST',
 'PGDATABASE','PGUSER','PGPASSWORD','PGSERVICE',
 'ARONEXA_PATIENT_DATASOURCE_URL','ARONEXA_AUTH_DATASOURCE_URL',
 'ARONEXA_OPD_DATASOURCE_URL','ARONEXA_PILOT05_PORT','ARONEXA_PILOT05_RUN_ID')){
 if([Environment]::GetEnvironmentVariable($key)){
  throw ('WIN10 STOP: inherited database/profile setting '+$key)
 }
}
$docker=(Get-Command docker.exe -ErrorAction Stop).Source
$java=(Get-Command java.exe -ErrorAction Stop).Source
$javac=(Get-Command javac.exe -ErrorAction Stop).Source
$version=(& $java --version | Select-Object -First 1).ToString()
if($version -notmatch '(?:^|[\s"])21\.'){
 throw ('WIN10 STOP: Java 21 required, found '+$version)
}
& $docker info '--format' '{{.ServerVersion}}'
if($LASTEXITCODE -ne 0){throw 'WIN10 STOP: Docker Engine unavailable'}
$source=Join-Path $PSScriptRoot 'ClinicBackupCrypto.java'
if(-not(Test-Path -LiteralPath $source -PathType Leaf)){
 throw 'WIN10 STOP: local Java backup source missing'
}
$runId=[guid]::NewGuid().ToString('D')
$short=$runId.Substring(0,12)
$srcName='aronexa-win10-src-'+$short
$dstName='aronexa-win10-restored-'+$short
$labelKey='com.argdigital.aronexa.win10.run'
$label=$labelKey+'='+$runId
$db='aronexa_win10_fictional_only'
$dbUser='aronexa_win10_fictional'
$dbPass=([guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N'))
$tmp=Join-Path $env:TEMP ('aronexa-win10-private-'+[guid]::NewGuid().ToString('N'))
$evidence=Join-Path (Join-Path $env:USERPROFILE 'AroNexa-Local-QA') ('WIN10-'+$runId)
[void][IO.Directory]::CreateDirectory($tmp)
[void][IO.Directory]::CreateDirectory($evidence)
$classes=Join-Path $tmp 'classes'
[void][IO.Directory]::CreateDirectory($classes)
$plain=Join-Path $tmp 'fictional-source.dump'
$restored=Join-Path $tmp 'fictional-restored.dump'
$encrypted=Join-Path $evidence 'FICTIONAL-PG17-backup.axenc'
$srcId=''
$dstId=''
function Confirm-Container {
 param([string]$Id,[string]$Name)
 if($Id -notmatch '^[0-9a-f]{64}$'){throw 'WIN10 STOP: bad container ID'}
 $raw=@(& $docker inspect $Id 2>$null)
 if($LASTEXITCODE -ne 0 -or @($raw).Count -eq 0){
  throw 'WIN10 STOP: Docker inspect failed'
 }
 $rows=ConvertFrom-Json -InputObject ($raw -join [Environment]::NewLine)
 if(@($rows).Count -ne 1){throw 'WIN10 STOP: ambiguous Docker identity'}
 $record=@($rows)[0]
 $found=$record.Config.Labels.PSObject.Properties[$labelKey]
 if($null -eq $found -or $found.Value -ne $runId -or
    $record.Name -ne ('/'+$Name) -or
    $record.HostConfig.NetworkMode -ne 'none' -or
    -not $record.HostConfig.AutoRemove -or
    @($record.Mounts | Where-Object {
      $_.Type -ne 'tmpfs' -or $_.Destination -ne '/var/lib/postgresql/data'
    }).Count -ne 0){
  throw 'WIN10 STOP: container label, network or mount check failed'
 }
 return $record
}
function Start-DisposableDb {
 param([string]$Name)
 $args=@('run','--detach','--rm','--network','none',
   '--tmpfs','/var/lib/postgresql/data:rw,size=128m',
   '--name',$Name,
   '--label',$label,'--env',('POSTGRES_DB='+$db),
   '--env',('POSTGRES_USER='+$dbUser),
   '--env',('POSTGRES_PASSWORD='+$dbPass),'postgres:17-alpine')
 $raw=@(& $docker @args)
 if($LASTEXITCODE -ne 0){throw 'WIN10 STOP: docker run failed'}
 $id=($raw -join '').Trim()
 if($id -notmatch '^[0-9a-f]{64}$'){throw 'WIN10 STOP: unverified container ID'}
 $null=Confirm-Container -Id $id -Name $Name
 $ready=$false
 for($i=0;$i -lt 40;$i++){
  & $docker exec $id pg_isready -U $dbUser -d $db *> $null
  if($LASTEXITCODE -eq 0){$ready=$true;break}
  Start-Sleep -Seconds 1
 }
 if(-not $ready){throw 'WIN10 STOP: disposable PostgreSQL startup failed'}
 return $id
}
function Query {
 param([string]$Id,[string]$Sql)
 $lines=@(& $docker exec $Id psql -X -v ON_ERROR_STOP=1 -U $dbUser -d $db -t -A -c $Sql)
 if($LASTEXITCODE -ne 0){throw 'WIN10 FAIL: fictional PG17 query'}
 return ($lines -join '').Trim()
}
try{
 Write-Host 'WIN10 1/5 — Compile and self-test Java21 AES-256-GCM (wrong key + tamper)'
 & $javac --release 21 -encoding UTF-8 -d $classes $source
 if($LASTEXITCODE -ne 0){throw 'WIN10 FAIL: Java compilation'}
 & $java -cp $classes ClinicBackupCrypto selftest
 if($LASTEXITCODE -ne 0){throw 'WIN10 FAIL: AES-GCM selftest'}
 Write-Host 'WIN10 2/5 — Start TWO separate no-network disposable PG17 containers'
 $srcId=Start-DisposableDb -Name $srcName
 $dstId=Start-DisposableDb -Name $dstName
 if($srcId -eq $dstId){throw 'WIN10 STOP: destination equals source'}
 $sql=@"
CREATE TABLE public.win10_synthetic_case(
 case_id uuid PRIMARY KEY,
 fictional_mrn varchar(40) NOT NULL,
 state varchar(20) NOT NULL CHECK(state IN ('REGISTERED','CHECKED_IN')));
INSERT INTO public.win10_synthetic_case VALUES
('11111111-1111-4111-8111-111111111111','FAKE-ONLY-001','REGISTERED'),
('22222222-2222-4222-8222-222222222222','FAKE-ONLY-002','CHECKED_IN'),
('33333333-3333-4333-8333-333333333333','FAKE-ONLY-003','REGISTERED');
"@
 & $docker exec $srcId psql -X -v ON_ERROR_STOP=1 -U $dbUser -d $db -c $sql
 if($LASTEXITCODE -ne 0){throw 'WIN10 FAIL: prepare fictional source'}
 $countQuery='SELECT count(*) FROM public.win10_synthetic_case'
 $digestQuery="SELECT md5(string_agg(case_id::text || ':' || fictional_mrn || ':' || state, '|' ORDER BY case_id)) FROM public.win10_synthetic_case"
 $before=Query -Id $srcId -Sql $countQuery
 $digest=Query -Id $srcId -Sql $digestQuery
 if($before -ne '3' -or $digest -notmatch '^[0-9a-f]{32}$'){
  throw 'WIN10 FAIL: source rows or identity fingerprint invalid'
 }
 $empty=Query -Id $dstId -Sql "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='win10_synthetic_case'"
 if($empty -ne '0'){throw 'WIN10 FAIL: fresh destination not empty'}
 Write-Host 'WIN10 3/5 — pg_dump nonempty custom format and encrypt'
 & $docker exec $srcId pg_dump --format=custom --no-owner --no-acl -U $dbUser -d $db -f /tmp/fictional.dump
 if($LASTEXITCODE -ne 0){throw 'WIN10 FAIL: pg_dump'}
 & $docker cp ($srcId+':/tmp/fictional.dump') $plain
 if($LASTEXITCODE -ne 0 -or -not(Test-Path -LiteralPath $plain -PathType Leaf)){
  throw 'WIN10 FAIL: copy fictional pg_dump to private temporary folder'
 }
 if((Get-Item -LiteralPath $plain).Length -lt 1024){
  throw 'WIN10 FAIL: nonempty pg_dump is unexpectedly small'
 }
 $originalSha=(Get-FileHash -LiteralPath $plain -Algorithm SHA256).Hash
 Write-Host 'WIN10 4/5 — Type synthetic backup passphrase directly into Java console (16+ characters)'
 Write-Host 'Never use your actual clinic database password or share this temporary passphrase.'
 & $java -cp $classes ClinicBackupCrypto roundtrip $plain $encrypted $restored
 if($LASTEXITCODE -ne 0){throw 'WIN10 FAIL: authenticated encryption/decryption'}
 if(-not(Test-Path -LiteralPath $encrypted -PathType Leaf) -or
    -not(Test-Path -LiteralPath $restored -PathType Leaf)){
  throw 'WIN10 FAIL: encrypted/verified restore artifact missing'
 }
 $restoreSha=(Get-FileHash -LiteralPath $restored -Algorithm SHA256).Hash
 $encryptedSha=(Get-FileHash -LiteralPath $encrypted -Algorithm SHA256).Hash
 if($originalSha -ne $restoreSha -or $encryptedSha -eq $originalSha){
  throw 'WIN10 FAIL: encrypted roundtrip SHA256 mismatch'
 }
 Write-Host 'WIN10 5/5 — pg_restore into SECOND disposable PG17 (no shared volumes)'
 & $docker cp $restored ($dstId+':/tmp/fictional-restored.dump')
 if($LASTEXITCODE -ne 0){throw 'WIN10 FAIL: copy to isolated restore container'}
 & $docker exec $dstId pg_restore --exit-on-error --no-owner --no-acl -U $dbUser -d $db /tmp/fictional-restored.dump
 if($LASTEXITCODE -ne 0){throw 'WIN10 FAIL: pg_restore'}
 $after=Query -Id $dstId -Sql $countQuery
 $afterDigest=Query -Id $dstId -Sql $digestQuery
 if($after -ne '3' -or $afterDigest -ne $digest){
  throw 'WIN10 FAIL: restored synthetic rows differ'
 }
 $lines=@(
  ('SourceCommit='+$sha),('RunId='+$runId),
  'Classification=INTERNAL_SYNTHETIC_PROOF_NOT_CLINIC_RECOVERY',
  'CryptoSelftest=PASS_WRONG_PASSWORD_AND_TAMPER',
  'Encryption=AES256_GCM_PBKDF2_SHA256_450000',
  ('PlainDumpSha256='+$originalSha),
  ('DecryptedDumpSha256='+$restoreSha),
  ('EncryptedDumpSha256='+$encryptedSha),
  'SourceRows=3','SecondContainerRows=3',
  'SourceToDestinationFingerprint=IDENTICAL',
  'DifferentPostgreSQL17Containers=TRUE',
  'Network=NONE','Volumes=NONE',
  'OriginalDatabase=NOT_TOUCHED',
  'SecondPhysicalComputer=NOT_TESTED',
  'RealClinicBackup=NOT_TESTED',
  'ProductionRecoveryApproval=NO_GO'
 )
 $lines | Set-Content -LiteralPath (Join-Path $evidence 'SUMMARY.txt') -Encoding ASCII
 Write-Host 'WIN10 ENCRYPTED NONEMPTY PG17 SECOND CONTAINER RESTORE QA PASSED' -ForegroundColor Green
 Write-Host ('EvidenceFolder='+$evidence)
 Write-Host ('SourceCommit='+$sha)
 Write-Host 'Fictional encrypted sample retained. Plaintext will be deleted.'
} finally {
 if(Test-Path -LiteralPath $tmp -PathType Container){
  Remove-Item -LiteralPath $tmp -Force -Recurse -ErrorAction SilentlyContinue
 }
 foreach($pair in @(@{Id=$srcId;Name=$srcName},@{Id=$dstId;Name=$dstName})){
  if($pair.Id -match '^[0-9a-f]{64}$'){
   try {
    $null=Confirm-Container -Id $pair.Id -Name $pair.Name
    & $docker stop $pair.Id | Out-Null
    if($LASTEXITCODE -ne 0){
     Write-Warning ('WIN10 cleanup failed for '+$pair.Id)
    }
   }catch{
    Write-Warning ('WIN10 cleanup refused; manually review exact container '+$pair.Id)
   }
  }
 }
}
