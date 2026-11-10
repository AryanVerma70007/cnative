# User guide

Run `docker compose up --build -d` from the repository root and open http://localhost:5173. Stop with `docker compose down`.

Use the demo credentials in [README.md](./README.md). Customers can inspect orders, inventory availability, shipments, and notifications, and create an order from the Orders page. Warehouse operators and administrators can access the same operational views and are authorized for shipment operations in the API.

The Overview page is database-backed from the API response. Refresh data after a workflow action. A low-stock item is highlighted in the Inventory page. Notification read state is changed with the Mark read action.

If the frontend cannot connect, inspect `docker compose ps` and `docker compose logs api-gateway`. The gateway is exposed on port 8090 because port 8080 is owned by an existing MySQL process.
### Security note

Development demo accounts are only for local testing. Set `JWT_SECRET` to a strong secret through environment configuration or a Kubernetes Secret before deployment. Do not use the checked-in development fallback in production.
