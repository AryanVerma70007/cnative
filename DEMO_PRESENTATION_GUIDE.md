# Logistics Platform Demo Guide

## Quick start

Use **PowerShell** for the commands below. Start Docker Desktop first, then open PowerShell in the project folder:

```powershell
cd C:\Users\gitav\Documents\cnative
minikube start --profile logistics
kubectl config use-context logistics
kubectl get deployments,pods -n logistics
```

Wait for the deployments to show `1/1` and pods to show `Running`.

In separate PowerShell windows, start a browser route for the frontend and Grafana:

```powershell
minikube service frontend -n logistics --url --profile logistics
```

```powershell
minikube service grafana -n logistics --url --profile logistics
```

Open each URL printed in its terminal. **Leave both terminals open** while using the URLs.

### Demo application credentials

| Role | Username (email) | Password |
|---|---|---|
| Customer | `customer@logistics.local` | `Customer123!` |
| Warehouse operator | `warehouse@logistics.local` | `Warehouse123!` |
| Administrator | `admin@logistics.local` | `Admin123!` |

### Grafana credentials

- **Username:** `admin`
- **Password:** Generated for the local Kubernetes cluster. Retrieve it in PowerShell; do not use the application demo passwords:

```powershell
$encoded = kubectl get secret logistics-secrets -n logistics -o 'jsonpath={.data.GRAFANA_ADMIN_PASSWORD}'
[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))
```

After signing in, open **Dashboards → Logistics → Logistics Service Health**.

## Suggested presentation flow

1. **Introduce the purpose:** a local cloud-native logistics application that coordinates orders, inventory, shipments, tracking, and notifications.
2. **Show the deployed architecture:** run `kubectl get deployments,pods,services -n logistics -o wide`. Point out the frontend, API gateway, four business services, databases, message broker, Prometheus, and Grafana.
3. **Demonstrate the customer workflow:** sign in as the customer, open **Orders**, and create an order using the default SKU `SCN-100`.
4. **Show the event-driven workflow:** inventory is reserved; a shipment is created; order and shipment records update as events are processed.
5. **Show tracking:** open **Shipments**. The simulator advances through `CREATED`, `PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, and `DELIVERED`, normally one milestone every 10 seconds. The frontend refreshes records every five seconds.
6. **Show delivery and notifications:** wait for delivery, confirm the order becomes `DELIVERED`, and open **Notifications**. A warehouse operator or administrator can use **Mark delivered** to demonstrate the manual delivery action.
7. **Show monitoring:** open the Grafana dashboard and explain its request-rate, p95-latency, 5xx-rate, and JVM-heap panels. Open Prometheus targets at the optional route below to show scrape health.
8. **Optionally discuss load testing:** the capped local k6 scenario uses up to 25 virtual users for 60 seconds and at most one order per user. The latest run did not meet its thresholds; its measured results and implications are documented in the project report below.

## Detailed presentation notes

### Verify the cluster

```powershell
minikube status --profile logistics
kubectl get nodes
kubectl get deployments,pods,services -n logistics -o wide
kubectl get pvc -n logistics
```

The local demo uses one Minikube node. The PostgreSQL, MongoDB, and RabbitMQ persistent volume claims should be bound.

### Inspect service logs

```powershell
kubectl logs deployment/order-service -n logistics --tail=30
kubectl logs deployment/inventory-service -n logistics --tail=30
kubectl logs deployment/shipment-service -n logistics --tail=30
kubectl logs deployment/notification-service -n logistics --tail=30
kubectl logs deployment/rabbitmq -n logistics --tail=30
```

### Optional monitoring and broker routes

Run each command in its own PowerShell window; open the URL printed and keep the window open:

```powershell
minikube service prometheus -n logistics --url --profile logistics
minikube service rabbitmq -n logistics --url --profile logistics
```

Prometheus scrapes the gateway and the four business services every five seconds. RabbitMQ's management interface is served on port `15672`; use credentials configured for the local deployment if you need to sign in.

### Optional bounded traffic simulation

Install k6 once if it is not already installed:

```powershell
winget install --id GrafanaLabs.k6 --exact
```

Keep the frontend route window running. In another PowerShell window, use the exact localhost URL printed by Minikube:

```powershell
.\load-tests\run-local-load-test.ps1 -BaseUrl "http://127.0.0.1:<port>/"
```

This is a demo workload, not an unrestricted public load test: the script only accepts `localhost` or `127.0.0.1`, runs at most 25 virtual users for 60 seconds, and attempts at most one order per user. It also makes authenticated read requests. It can add up to 25 test orders to the local database. Review the current measured results in [PROJECT_REPORT.md](./PROJECT_REPORT.md) before presenting performance claims.

### Stop the demo

Press **Ctrl+C** in each terminal running `minikube service`, then stop the cluster:

```powershell
minikube stop --profile logistics
```

This stops Minikube without deleting its persistent volumes.

### Rebuild and redeploy after code changes

From the project directory, run:

```powershell
.\scripts\deploy-kubernetes.ps1
```

The script builds and tests the backend, builds and loads images, applies Kubernetes manifests, and waits for rollouts. It stops this project's Compose containers to free memory for Minikube but preserves Compose volumes.
