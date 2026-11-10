# Project completion report

## Status

**Completed with limitations.** Docker Compose and the full Kubernetes deployment were verified locally, including an end-to-end order-to-delivery workflow. This is a local demonstration, not a production-ready high-availability deployment.

## Implemented

- Spring Cloud Gateway and four independently deployable Spring Boot services: Order, Inventory, Shipment, and Notification.
- PostgreSQL persistence, MongoDB shipment tracking, and RabbitMQ event-driven service communication.
- React frontend routed through the gateway.
- Docker Compose and Minikube manifests, including persistent volumes, health probes, and resource limits.
- Prometheus and Grafana monitoring.
- Signed JWT authentication and a transactional order outbox with bounded retries and dead-letter status.
- Automatic simulated shipment stages with timestamped location and milestone details.
- Prometheus collection from all five services and a provisioned Grafana service-health dashboard.
- A local-only k6 test capped at 25 VUs, 60 seconds, and at most one order per VU.

## Verification

- `mvn -f backend\pom.xml clean verify`: passed (7 tests, 0 failures).
- `npm run build --workspace frontend`: passed.
- Docker Compose deployment was built and its services and dependencies were healthy.
- Minikube `logistics` node reached `Ready`; all 11 application, infrastructure, and monitoring pods became ready.
- PostgreSQL, MongoDB, and RabbitMQ persistent volume claims were all bound.
- Kubernetes frontend returned HTTP 200; gateway `/actuator/health` returned `UP`.
- Prometheus reported the gateway target as `up`; Grafana health returned `ok`.
- Live workflow passed: login, create order, reserve inventory, create and deliver shipment, update order to `DELIVERED`, and generate notifications.
- Updated end-to-end simulation passed: a test order advanced through `CREATED`, `PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, and `DELIVERED`; the order status became `DELIVERED`.
- All five Prometheus service scrape targets reported `up`; Grafana health returned HTTP 200 and the Logistics Service Health dashboard was provisioned.
- The 25-VU/60-second k6 test ran against the local frontend. It completed with breached thresholds: 3.96 requests/second, 9.99-second p95 latency, and 53.44% failed HTTP requests, primarily login timeouts. Seven orders were created from eight attempts. This is a useful stress-test result, not a passing performance test.
- After the run, all five Prometheus targets remained `up`, all 11 pods were ready, and a single customer login succeeded. `kubectl top` was unavailable because the cluster has no Metrics API.

The deployment script stops this project's Compose containers to free memory for Minikube, but preserves Compose volumes. Compose is currently stopped.

## Access

Kubernetes services require port forwarding; see the commands in [README.md](./README.md). The frontend, gateway, Prometheus, and Grafana can be exposed on ports 5173, 8090, 9090, and 3000 respectively.

Demo accounts are listed in [README.md](./README.md). The Grafana admin password is stored in the `logistics-secrets` Kubernetes Secret.

## Remaining limitations

- Minikube is a single-node local cluster; high availability and production scaling were not tested.
- Kubernetes Secrets and demo accounts are for local testing, not a substitute for production secret management.
- Frontend browser E2E automation was not executed.

## Verification update (2026-11-10)

This full Minikube verification supersedes the earlier incomplete result recorded on 2026-11-02. The full Spring microservices stack, infrastructure, monitoring, and live workflow were deployed and verified on the `logistics` profile. The initial capped load run also established that the current resource-limited local deployment does not meet the configured 2-second p95/5% failure thresholds at 25 concurrent users.
