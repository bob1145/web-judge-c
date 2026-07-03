[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$Image = "cpp-judge-runner-windows:latest",
    [int]$MemoryBytes = 268435456,
    [switch]$SkipMaven,
    [switch]$RequireDocker
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$mavenWrapper = Join-Path $root "mvnw.cmd"
$maven = if (Test-Path $mavenWrapper) { $mavenWrapper } else { "mvn" }

Write-Host "Windows capability smoke for windows-prod"
Write-Host "Checks: WindowsHyperVContainerRunnerTest, Hyper-V --isolation hyperv, --network none, Job Object process-tree kill, default access code rejection, wildcard origin rejection."

function Invoke-Step {
    param(
        [string]$Target,
        [string]$Action,
        [scriptblock]$Command
    )

    Write-Host "Step: $Action"
    if ($PSCmdlet.ShouldProcess($Target, $Action)) {
        & $Command
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }
}

if (-not $SkipMaven) {
    Invoke-Step $root "Run Windows sandbox and startup validation tests" {
        & $maven "-Dtest=WindowsHyperVContainerRunnerTest,ProductionSecurityStartupValidatorTest" test
    }
}

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) {
    $message = "Docker CLI unavailable; cannot collect live Hyper-V container evidence."
    if ($RequireDocker) {
        throw $message
    }
    Write-Warning "$message Local -WhatIf or unit-test smoke is not release evidence."
    return
}

$containerName = "cpp-judge-win-capability-$([guid]::NewGuid().ToString('N'))"
try {
    Invoke-Step $Image "Run Hyper-V container probe with --isolation hyperv and --network none" {
        & docker run -d `
            --name $containerName `
            --isolation hyperv `
            --network none `
            --memory $MemoryBytes `
            --cpus 1 `
            $Image `
            C:\judge-runner\probe.cmd
    }

    Invoke-Step $containerName "Inspect Hyper-V isolation, network none, memory resource limits, and process status" {
        & docker inspect --format "{{.HostConfig.Isolation}}|{{.HostConfig.NetworkMode}}|{{.HostConfig.Memory}}|{{.State.ExitCode}}" $containerName
    }

    Invoke-Step $containerName "Read probe logs for Job Object, process-tree kill, path isolation, and output limit evidence" {
        & docker logs $containerName
    }
}
finally {
    if ($PSCmdlet.ShouldProcess($containerName, "Cleanup probe container")) {
        & docker rm -f $containerName 2>$null
    }
}
