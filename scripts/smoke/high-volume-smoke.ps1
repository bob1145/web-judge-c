[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [ValidateRange(100, 200000)]
    [int]$Cases = 100000
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$mavenWrapper = Join-Path $root "mvnw.cmd"
$maven = if (Test-Path $mavenWrapper) { $mavenWrapper } else { "mvn" }
$arguments = @(
    "-Dtest=ProductionHighVolumeIntegrationTest",
    "-DhighVolumeSmokeCases=$Cases",
    "test"
)

Write-Host "Running high-volume smoke for $Cases cases"
Write-Host "Usage: powershell -ExecutionPolicy Bypass -File scripts/smoke/high-volume-smoke.ps1 -Cases $Cases"
Write-Host "Expected test output includes HIGH_VOLUME_SMOKE with payloadBytes, schedulerTasks, pollCount, and throughputCasesPerSec."

if ($PSCmdlet.ShouldProcess($root, "Run ProductionHighVolumeIntegrationTest")) {
    & $maven @arguments
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
