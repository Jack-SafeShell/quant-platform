$ErrorActionPreference='Stop'
. "$PSScriptRoot/binance_candidate_guard.ps1"
$now=[DateTimeOffset]::Now.ToUnixTimeMilliseconds()
function Fixture {
 $d=@{status='RUNNING';accountId='binance-primary';exchangeName='binance';selectedAccount=$true;alerts=@();lastHeartbeatAt=$now-1000;lastCandleAt=$now-3600000;signals=@(@{candleAt=$now-3600000});reconciliations=@(@{reconciliationStatus='PASSED';exchangeOpenOrders=0;platformOpenOrders=0;sessionLoss=0;reconciledAt=$now-1000})}
 $p=@{costsComplete=$true;valuationComplete=$true;executionTrace=@();buyTurnover=0;sellTurnover=0;activeOrderCount=0;signalCount=1;orderCount=0;filledOrderCount=0;netContribution=0;netPositionBtc=0;settlementState='NO_FILLS'}
 return @($d,$p)
}
function Reject($d,$p,$previous=0){$rejected=$false;try{$null=Assert-CandidateSnapshot $d $p $now $previous}catch{$rejected=$true};if(!$rejected){throw 'Guard accepted unsafe fixture'}}
$f=Fixture;$s=Assert-CandidateSnapshot $f[0] $f[1] $now 0;if($s.cycleObserved){throw 'No fill marked as cycle'}
$f=Fixture;$f[0].accountId='okx-primary';Reject $f[0] $f[1]
$f=Fixture;$f[0].lastHeartbeatAt=$now-181000;Reject $f[0] $f[1]
$f=Fixture;$f[0].lastCandleAt=$now;Reject $f[0] $f[1]
$f=Fixture;$f[0].reconciliations[0].sessionLoss=5;Reject $f[0] $f[1]
$f=Fixture;$f[0].reconciliations[0].exchangeOpenOrders=1;Reject $f[0] $f[1]
$f=Fixture;$s=Assert-CandidateSnapshot $f[0] $f[1] $now ($now-1000);if($s.snapshotProgress -ne 'WAITING_FOR_NEXT_SAMPLE'){throw 'Fresh unchanged snapshot rejected'}
$f=Fixture;$f[0].signals+=@{candleAt=$now-3600000};Reject $f[0] $f[1]
$f=Fixture;$f[0].alerts=@(@{status='OPEN'});Reject $f[0] $f[1]
$f=Fixture;$f[1].costsComplete=$false;Reject $f[0] $f[1]
$f=Fixture;$f[1].executionTrace=@(@{status='ORDER_SUBMITTED';linkState='NO_LEDGER_ORDER'});Reject $f[0] $f[1]
$f=Fixture;$f[1].buyTurnover=7;$f[1].sellTurnover=6;$s=Assert-CandidateSnapshot $f[0] $f[1] $now 0;if(!$s.cycleObserved){throw 'Natural cycle not detected'}
$f=Fixture;$at=$now-1000
foreach($age in @(60000,108443,180000)){$f[0].lastHeartbeatAt=$at;$f[0].reconciliations[0].reconciledAt=$at;$s=Assert-CandidateSnapshot $f[0] $f[1] ($at+$age) $at;if($s.snapshotProgress -ne 'WAITING_FOR_NEXT_SAMPLE' -or $s.snapshotAgeMillis -ne $age){throw 'Bounded waiting failed'}}
$f[0].lastHeartbeatAt=$now;$f[0].reconciliations[0].reconciledAt=$at;Reject $f[0] $f[1] ($at+1)
$f=Fixture;$f[0].reconciliations[0].reconciledAt=$now-180001;Reject $f[0] $f[1]
$f=Fixture;$f[0].reconciliations[0].reconciledAt=$now+1;Reject $f[0] $f[1]
$f=Fixture;$f[0].reconciliations[0].Remove('reconciledAt');Reject $f[0] $f[1]
$f=Fixture;$f[0].alerts=@(@{status='OPEN'});Reject $f[0] $f[1] ($now-1000)
$f=Fixture;$f[0].reconciliations[0].sessionLoss=5;Reject $f[0] $f[1] ($now-1000)
$f=Fixture;$f[0].reconciliations[0].exchangeOpenOrders=1;Reject $f[0] $f[1] ($now-1000)
$f=Fixture;$s=Assert-CandidateSnapshot $f[0] $f[1] $now ($now-2000);if($s.snapshotProgress -ne 'ADVANCING'){throw 'Fresh progress not detected'}
'Guard verified: bounded waiting through 180 seconds, stale/regressed/future rejection, independent risk boundaries, natural cycle.'
