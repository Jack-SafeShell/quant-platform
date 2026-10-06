function Assert-CandidateSnapshot($detail,$performance,[long]$now,[long]$previousSnapshot) {
 if($detail.status -ne 'RUNNING' -or $detail.accountId -ne 'binance-primary' -or $detail.exchangeName -ne 'binance' -or !$detail.selectedAccount){throw 'SESSION_OR_ACCOUNT_CHANGED'}
 if(@($detail.alerts|Where-Object {$_.status -ne 'RESOLVED'}).Count){throw 'UNRESOLVED_ALERT'}
 if(!$detail.lastHeartbeatAt -or $now-[long]$detail.lastHeartbeatAt -gt 180000 -or [long]$detail.lastHeartbeatAt -gt $now){throw 'HEARTBEAT_STALE'}
 if(!$detail.lastCandleAt -or $now-[long]$detail.lastCandleAt -gt 7800000 -or [long]$detail.lastCandleAt -gt $now-3600000){throw 'CLOSED_CANDLE_STALE'}
 $snapshots=@($detail.reconciliations);if(!$snapshots.Count){throw 'NO_RECONCILIATION'}
 if(@($snapshots|Where-Object {$_.reconciliationStatus -ne 'PASSED' -or $_.exchangeOpenOrders -ne $_.platformOpenOrders -or [decimal]$_.sessionLoss -ge 5}).Count){throw 'RECONCILIATION_OR_LOSS_FAILURE'}
 $latest=$snapshots[0];$snapshotAt=[long]$latest.reconciledAt;$snapshotAge=$now-$snapshotAt
 if($snapshotAt -le 0 -or $snapshotAt -gt $now){throw 'SNAPSHOT_TIME_INVALID'}
 if($snapshotAge -gt 180000){throw 'SNAPSHOT_STALE'}
 if($previousSnapshot -gt 0 -and $snapshotAt -lt $previousSnapshot){throw 'SNAPSHOT_TIME_REGRESSED'}
 $progress=if($previousSnapshot -gt 0 -and $snapshotAt -eq $previousSnapshot){'WAITING_FOR_NEXT_SAMPLE'}else{'ADVANCING'}
 if(@($detail.signals|Group-Object candleAt|Where-Object {$_.Count -gt 1}).Count){throw 'DUPLICATE_CANDLE_SIGNAL'}
 if(!$performance.costsComplete -or !$performance.valuationComplete -or @($performance.executionTrace|Where-Object {$_.status -eq 'ORDER_SUBMITTED' -and $_.linkState -ne 'LINKED'}).Count){throw 'COST_OR_ORDER_TRACE_INCOMPLETE'}
 return @{snapshotProgress=$progress;snapshotAgeMillis=$snapshotAge;latestSnapshotAt=$snapshotAt;lastHeartbeatAt=[long]$detail.lastHeartbeatAt;lastCandleAt=[long]$detail.lastCandleAt;signalCount=$performance.signalCount;orderCount=$performance.orderCount;filledOrderCount=$performance.filledOrderCount;platformOpenOrders=$latest.platformOpenOrders;exchangeOpenOrders=$latest.exchangeOpenOrders;sessionLoss=$latest.sessionLoss;accountEquityChange=$performance.accountEquityChange;netContribution=$performance.netContribution;netPositionBtc=$performance.netPositionBtc;settlementState=$performance.settlementState;cycleObserved=([decimal]$performance.buyTurnover -gt 0 -and [decimal]$performance.sellTurnover -gt 0 -and $performance.activeOrderCount -eq 0)}
}
