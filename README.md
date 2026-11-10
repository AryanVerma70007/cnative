# Cloud-Native Logistics and Shipment Management Platform

This repository contains a runnable local logistics operations application using Spring Cloud Gateway, four Spring Boot business services, React/Vite, PostgreSQL, MongoDB, and RabbitMQ.

## Quick start

```powershell
docker compose up --build -d
```

Open http://localhost:5173. The gateway health endpoint is http://localhost:8090/actuator/health.

Demo accounts:

| Role | Email | Password |
|---|---|---|
| Customer | customer@logistics.local | Customer123! |
| Warehouse operator | warehouse@logistics.local | Warehouse123! |
| Administrator | admin@logistics.local | Admin123! |

## Implemented workflow

The API persists orders, inventory, and notifications in PostgreSQL, shipment tracking in MongoDB, and publishes workflow events through RabbitMQ. A verified live workflow creates an order, reserves inventory, creates a shipment, delivers it, updates the order, and generates notifications.

The operations console loads records from the individual services. Customers can create and view their orders; warehouse operators and administrators can update stock and mark shipments delivered. Notifications show the message and can be marked read.

The repository is organized for the target architecture:

```text
backend/api-gateway/       Spring Cloud Gateway
backend/{order,inventory,shipment,notification}-service/ Spring Boot services
frontend/                   React/Vite operations console
infrastructure/             Docker Compose and Kubernetes deployment configuration
docs/                       architecture and user documentation
tests/                      automated verification
```

## Verification

```powershell
mvn -f backend\pom.xml clean verify
npm run build --workspace frontend
```

Container deployment is also available:

```powershell
docker compose up --build -d
docker compose ps
```

The containerized frontend is at http://localhost:5173 and the gateway is at http://localhost:8090. `docker compose down` stops the application. Prometheus is at http://localhost:9090 and Grafana is at http://localhost:3000.

## Kubernetes / Minikube

On Windows, install Minikube if needed, then run the deployment script from PowerShell:

```powershell
winget install --id Kubernetes.minikube --exact
# Close and reopen PowerShell after installation.
.\scripts\deploy-kubernetes.ps1
```

The script builds the backend and frontend images, starts a `logistics` Minikube profile, loads the images into the cluster, creates local-only credentials as a Kubernetes Secret, and deploys PostgreSQL, MongoDB, RabbitMQ, all application services, Prometheus, and Grafana. It stops this project's Compose containers first to free memory; their named data volumes are preserved.

The Kubernetes deployment was verified on Minikube: all 11 application, infrastructure, and monitoring pods became ready; all three database/broker PVCs bound; gateway health returned `UP`; and Prometheus scraped the gateway successfully. A live order workflow completed through inventory reservation and shipment delivery, updating the order to `DELIVERED` and creating notifications.

For a step-by-step Windows demo, including the browser workflow and commands to show the Kubernetes services and logs, see [DEMO_GUIDE.md](./DEMO_GUIDE.md).

Shipments now simulate pickup, transit, out-for-delivery, and delivery at 10-second intervals, with location and event details shown in tracking. Prometheus scrapes all five services, and Grafana provisions a service-health dashboard. A local-only k6 script exercises read APIs at up to 25 virtual users for 60 seconds and creates at most one test order per user; see [DEMO_GUIDE.md](./DEMO_GUIDE.md) for running it.

Check deployment status with `kubectl get pods,services -n logistics`. To open the frontend, run this in a separate PowerShell window and visit http://localhost:5173:

```powershell
kubectl port-forward -n logistics svc/frontend 5173:80
```

Port-forward the gateway, Prometheus, or Grafana similarly with `svc/api-gateway 8090:8080`, `svc/prometheus 9090:9090`, or `svc/grafana 3000:3000`. The Grafana admin password is generated once and stored in the `logistics-secrets` Kubernetes Secret; retrieve it in PowerShell with:

```powershell
$encoded = kubectl get secret logistics-secrets -n logistics -o 'jsonpath={.data.GRAFANA_ADMIN_PASSWORD}'
[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))
```

The deployment script stops Compose to free memory and preserves its data volumes. To return to Compose, stop the port-forward processes, stop Minikube with `minikube stop --profile logistics`, then run `docker compose up -d`.

See [USER_GUIDE.md](./USER_GUIDE.md), [TECHNICAL_DOCUMENTATION.md](./TECHNICAL_DOCUMENTATION.md), and [PROJECT_COMPLETION_REPORT.md](./PROJECT_COMPLETION_REPORT.md) for operating details and known limitations.
