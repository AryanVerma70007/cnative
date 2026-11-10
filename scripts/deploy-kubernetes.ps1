$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

foreach ($tool in @("docker", "kubectl", "mvn")) {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
    throw "Required command '$tool' was not found. Install it and reopen PowerShell."
  }
}

$minikube = Get-Command minikube -ErrorAction SilentlyContinue
if (-not $minikube) {
  $minikubePath = Join-Path $env:ProgramFiles "Kubernetes\Minikube\minikube.exe"
  if (-not (Test-Path $minikubePath)) {
    throw "Minikube was not found. Install it with winget, then reopen PowerShell."
  }
  $env:Path = "$(Split-Path -Parent $minikubePath);$env:Path"
}

docker compose stop
if ($LASTEXITCODE -ne 0) { throw "Could not stop the local Compose stack." }

mvn -f backend\pom.xml clean package
if ($LASTEXITCODE -ne 0) { throw "Backend build or tests failed." }

docker compose build api-gateway order-service inventory-service shipment-service notification-service frontend
if ($LASTEXITCODE -ne 0) { throw "Docker image build failed." }

minikube start --profile logistics --driver=docker --cpus=3 --memory=3072 --disk-size=30g
if ($LASTEXITCODE -ne 0) { throw "Minikube failed to start. Check Docker Desktop resources." }

kubectl config use-context logistics
if ($LASTEXITCODE -ne 0) { throw "Could not select the logistics Minikube context." }

$images = @(
  "cnative-api-gateway:latest",
  "cnative-order-service:latest",
  "cnative-inventory-service:latest",
  "cnative-shipment-service:latest",
  "cnative-notification-service:latest",
  "cnative-frontend:latest"
)
minikube image load --profile logistics $images
if ($LASTEXITCODE -ne 0) { throw "Could not load application images into Minikube." }

kubectl apply -f infrastructure\kubernetes\namespace.yaml
if ($LASTEXITCODE -ne 0) { throw "Could not create the logistics namespace." }

$existingGateway = kubectl get deployment api-gateway -n logistics --ignore-not-found -o name
if ($LASTEXITCODE -ne 0) { throw "Could not check for an existing API gateway deployment." }
$refreshApplicationPods = [bool]$existingGateway

$existingSecret = kubectl get secret logistics-secrets -n logistics --ignore-not-found -o name
if ($LASTEXITCODE -ne 0) { throw "Could not check for existing Kubernetes credentials." }
if (-not $existingSecret) {
  function New-RandomSecret([int]$byteCount) {
    $bytes = New-Object byte[] $byteCount
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
      $generator.GetBytes($bytes)
      return [Convert]::ToBase64String($bytes)
    } finally {
      $generator.Dispose()
    }
  }

  $postgresPassword = New-RandomSecret 32
  $jwtSecret = New-RandomSecret 48
  $grafanaPassword = New-RandomSecret 24
  kubectl create secret generic logistics-secrets -n logistics `
    "--from-literal=POSTGRES_USER=logistics" `
    "--from-literal=POSTGRES_PASSWORD=$postgresPassword" `
    "--from-literal=JWT_SECRET=$jwtSecret" `
    "--from-literal=JWT_ISSUER=logistics-platform" `
    "--from-literal=JWT_AUDIENCE=logistics-api" `
    "--from-literal=GRAFANA_ADMIN_PASSWORD=$grafanaPassword" `
    --dry-run=client -o yaml | kubectl apply -f -
  if ($LASTEXITCODE -ne 0) { throw "Could not create Kubernetes credentials." }
}

kubectl apply -f infrastructure\kubernetes\infrastructure.yaml
if ($LASTEXITCODE -ne 0) { throw "Could not apply infrastructure manifests." }
foreach ($deployment in @("postgres", "mongodb", "rabbitmq")) {
  kubectl rollout status "deployment/$deployment" -n logistics --timeout=300s
  if ($LASTEXITCODE -ne 0) { throw "Infrastructure deployment '$deployment' did not become ready." }
}

kubectl apply -f infrastructure\kubernetes\backend-services.yaml
if ($LASTEXITCODE -ne 0) { throw "Could not apply business-service manifests." }
foreach ($deployment in @("order-service", "inventory-service", "shipment-service", "notification-service")) {
  kubectl rollout status "deployment/$deployment" -n logistics --timeout=900s
  if ($LASTEXITCODE -ne 0) { throw "Backend deployment '$deployment' did not become ready." }
}

kubectl apply -f infrastructure\kubernetes\api-gateway.yaml
if ($LASTEXITCODE -ne 0) { throw "Could not apply API gateway manifest." }
kubectl rollout status deployment/api-gateway -n logistics --timeout=900s
if ($LASTEXITCODE -ne 0) { throw "API gateway did not become ready." }

kubectl apply -f infrastructure\kubernetes\frontend.yaml
if ($LASTEXITCODE -ne 0) { throw "Could not apply frontend manifest." }

$dashboardConfig = kubectl create configmap grafana-dashboards -n logistics `
  --from-file=logistics-overview.json=infrastructure\monitoring\grafana\dashboards\logistics-overview.json `
  --dry-run=client -o yaml
if ($LASTEXITCODE -ne 0) { throw "Could not create Grafana dashboard config." }
$dashboardConfig | kubectl apply -f -
if ($LASTEXITCODE -ne 0) { throw "Could not apply Grafana dashboard config." }

kubectl apply -f infrastructure\kubernetes\monitoring.yaml
if ($LASTEXITCODE -ne 0) { throw "Could not apply monitoring manifests." }
kubectl rollout restart deployment/prometheus -n logistics
if ($LASTEXITCODE -ne 0) { throw "Could not reload Prometheus scrape configuration." }
kubectl rollout restart deployment/grafana -n logistics
if ($LASTEXITCODE -ne 0) { throw "Could not reload Grafana provisioning." }
if ($refreshApplicationPods) {
  foreach ($deployment in @("order-service", "inventory-service", "shipment-service", "notification-service", "api-gateway", "frontend")) {
    kubectl rollout restart "deployment/$deployment" -n logistics
    if ($LASTEXITCODE -ne 0) { throw "Could not restart deployment '$deployment' to use the rebuilt image." }
    $timeout = 900
    kubectl rollout status "deployment/$deployment" -n logistics "--timeout=${timeout}s"
    if ($LASTEXITCODE -ne 0) { throw "Deployment '$deployment' did not become ready after restart." }
  }
}
foreach ($deployment in @("prometheus", "grafana")) {
  kubectl rollout status "deployment/$deployment" -n logistics --timeout=900s
  if ($LASTEXITCODE -ne 0) { throw "Deployment '$deployment' did not become ready." }
}

kubectl get pods,services -n logistics -o wide
Write-Host ""
Write-Host "Deployment is ready. In separate PowerShell windows, expose services with:"
Write-Host "  kubectl port-forward -n logistics svc/frontend 5173:80"
Write-Host "  kubectl port-forward -n logistics svc/api-gateway 8090:8080"
Write-Host "  kubectl port-forward -n logistics svc/prometheus 9090:9090"
Write-Host "  kubectl port-forward -n logistics svc/grafana 3000:3000"
