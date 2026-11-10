param(
  [Parameter(Mandatory = $true)]
  [string]$BaseUrl,
  [string]$Email = "customer@logistics.local",
  [string]$Password = "Customer123!"
)

$ErrorActionPreference = "Stop"
$uri = $null
if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$uri) -or
    $uri.Scheme -ne "http" -or
    $uri.Host -notin @("localhost", "127.0.0.1") -or
    $uri.AbsolutePath -ne "/" -or $uri.Query -or $uri.Fragment) {
  throw "BaseUrl must be a local URL such as http://127.0.0.1:53224/"
}

$k6Command = Get-Command k6 -ErrorAction SilentlyContinue
$k6Executable = if ($k6Command) { $k6Command.Source } else { Join-Path $env:ProgramFiles "k6\k6.exe" }
if (-not (Test-Path $k6Executable)) {
  throw "k6 is required. Install Grafana k6, reopen PowerShell, and run this script again."
}

$env:BASE_URL = $uri.AbsoluteUri
$env:TEST_EMAIL = $Email
$env:TEST_PASSWORD = $Password
& $k6Executable run (Join-Path $PSScriptRoot "orders.js")
if ($LASTEXITCODE -ne 0) {
  throw "The k6 run failed or one of its performance thresholds was exceeded."
}
