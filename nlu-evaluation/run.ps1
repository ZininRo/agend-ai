param(
    [switch]$WriteBaseline,
    [switch]$FailOnRegression,
    [int]$Limit = 0,
    [string]$Ids = "",
    [double]$MinIntent = -1,
    [double]$MinCritical = -1,
    [double]$MinSchema = -1
)

$ErrorActionPreference = "Stop"
$backend = Join-Path $PSScriptRoot "..\backend"
$cli = @()
if ($WriteBaseline) { $cli += "--write-baseline" }
if ($FailOnRegression) { $cli += "--fail-on-regression" }
if ($Limit -gt 0) { $cli += "--limit=$Limit" }
if ($Ids) { $cli += "--ids=$Ids" }
if ($MinIntent -ge 0) { $cli += "--min-intent=$MinIntent" }
if ($MinCritical -ge 0) { $cli += "--min-critical=$MinCritical" }
if ($MinSchema -ge 0) { $cli += "--min-schema=$MinSchema" }

Push-Location $backend
try {
    $joined = $cli -join " "
    & .\mvnw.cmd -q -DskipTests spring-boot:run `
        "-Dspring-boot.run.main-class=com.example.agend_ai.evaluation.NluEvaluationCli" `
        "-Dspring-boot.run.arguments=$joined"
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
