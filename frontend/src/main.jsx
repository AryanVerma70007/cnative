import React, { useCallback, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";
import "./operations.css";

const api = async (path, options = {}) => {
  const token = localStorage.getItem("token");
  const response = await fetch(path, {
    ...options,
    headers: {
      "content-type": "application/json",
      ...(options.headers || {}),
      ...(token ? { authorization: `Bearer ${token}` } : {})
    }
  });
  const text = await response.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { message: text };
    }
  }
  if (!response.ok) {
    throw new Error(data?.error || data?.message || `Request failed (${response.status})`);
  }
  return data;
};

const labels = {
  CREATED: "Created",
  PICKED_UP: "Picked up",
  PENDING_INVENTORY: "Pending inventory",
  READY_FOR_SHIPMENT: "Ready for shipment",
  IN_TRANSIT: "In transit",
  OUT_FOR_DELIVERY: "Out for delivery",
  DELIVERED: "Delivered",
  DELAYED: "Delayed"
};
const canManageOperations = user => ["WAREHOUSE_OPERATOR", "ADMIN"].includes(user?.role);

function Login({ onLogin }) {
  const [form, setForm] = useState({ email: "customer@logistics.local", password: "Customer123!" });
  const [error, setError] = useState("");

  async function submit(event) {
    event.preventDefault();
    try {
      const result = await api("/api/auth/login", { method: "POST", body: JSON.stringify(form) });
      localStorage.setItem("token", result.token);
      localStorage.setItem("user", JSON.stringify(result.user));
      onLogin(result.user);
    } catch (e) {
      setError(e.message);
    }
  }

  return <main className="login-shell"><section className="login-card">
    <p className="eyebrow">NORTHSTAR LOGISTICS</p>
    <h1>Move every order with confidence.</h1>
    <p className="muted">Operations control for orders, inventory, delivery and customer communication.</p>
    <form onSubmit={submit}>
      <label>Email<input type="email" required value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} /></label>
      <label>Password<input type="password" required value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} /></label>
      {error && <p className="error">{error}</p>}
      <button>Sign in</button>
    </form>
    <small>Demo: customer@logistics.local / Customer123!</small>
  </section></main>;
}

function App() {
  const [user, setUser] = useState(() => {
    try { return JSON.parse(localStorage.getItem("user") || "null"); } catch { return null; }
  });
  const [data, setData] = useState({ orders: [], inventory: [], shipments: [], notifications: [] });
  const [page, setPage] = useState("Overview");
  const [error, setError] = useState("");

  const refresh = useCallback(async () => {
    try {
      const [orders, inventory, shipments, notifications] = await Promise.all([
        api("/api/orders"),
        api("/api/inventory"),
        api("/api/shipments"),
        api("/api/notifications")
      ]);
      setData({ orders, inventory, shipments, notifications });
      setError("");
    } catch (e) {
      setError(e.message);
      if (e.message.includes("(401)") || e.message.includes("(403)")) {
        localStorage.removeItem("token");
        localStorage.removeItem("user");
        setUser(null);
      }
    }
  }, []);

  useEffect(() => {
    if (localStorage.getItem("token")) refresh();
  }, [refresh]);

  useEffect(() => {
    if (!localStorage.getItem("token")) return undefined;
    const timer = window.setInterval(refresh, 5000);
    return () => window.clearInterval(timer);
  }, [refresh]);

  async function perform(action) {
    try {
      await action();
      await refresh();
      return true;
    } catch (e) {
      setError(e.message);
      return false;
    }
  }

  if (!user || !localStorage.getItem("token")) {
    return <Login onLogin={signedInUser => { setUser(signedInUser); refresh(); }} />;
  }

  const sortedOrders = [...data.orders].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
  const sortedShipments = [...data.shipments].sort((a, b) =>
    new Date(b.tracking?.at(-1)?.at || 0) - new Date(a.tracking?.at(-1)?.at || 0));
  const dashboard = {
    totalOrders: data.orders.length,
    pendingOrders: data.orders.filter(order => order.status?.includes("PENDING")).length,
    activeShipments: data.shipments.filter(shipment => shipment.status !== "DELIVERED").length,
    lowStock: data.inventory.filter(item => item.available < 10).length
  };
  const logout = () => {
    localStorage.removeItem("token");
    localStorage.removeItem("user");
    setUser(null);
  };

  return <div className="app">
    <aside>
      <div className="brand"><span>NS</span><strong>Northstar</strong></div>
      <p className="nav-label">WORKSPACE</p>
      {["Overview", "Orders", "Inventory", "Shipments", "Notifications"].map(item =>
        <button className={page === item ? "nav active" : "nav"} onClick={() => setPage(item)} key={item}>{item}</button>)}
      <div className="sidebar-bottom">
        <p className="nav-label">SIGNED IN AS</p>
        <strong>{user.name || "Operations user"}</strong>
        <small>{user.role || "CUSTOMER"}</small>
        <button className="nav" onClick={logout}>Sign out</button>
      </div>
    </aside>
    <main className="content">
      <header>
        <div><p className="eyebrow">OPERATIONS / {page.toUpperCase()}</p><h1>{page}</h1></div>
        <button className="ghost" onClick={refresh}>Refresh data</button>
      </header>
      {error && <div className="alert">{error}</div>}
      {page === "Overview" && <Overview stats={dashboard} orders={sortedOrders.slice(0, 5)} shipments={sortedShipments.slice(0, 5)} />}
      {page === "Orders" && <Orders orders={sortedOrders} onCreate={body =>
        perform(() => api("/api/orders", {
          method: "POST",
          headers: { "Idempotency-Key": crypto.randomUUID() },
          body: JSON.stringify(body)
        }))} />}
      {page === "Inventory" && <Inventory items={data.inventory} canManage={canManageOperations(user)} onSave={(sku, available) =>
        perform(() => api(`/api/inventory/${encodeURIComponent(sku)}?available=${encodeURIComponent(available)}`, { method: "PUT" }))} />}
      {page === "Shipments" && <Shipments shipments={sortedShipments} canManage={canManageOperations(user)} onDeliver={id =>
        perform(() => api(`/api/shipments/${encodeURIComponent(id)}/deliver`, { method: "POST" }))} />}
      {page === "Notifications" && <Notifications items={data.notifications} onMarkRead={id =>
        perform(() => api(`/api/notifications/${encodeURIComponent(id)}/read`, { method: "PATCH" }))} />}
    </main>
  </div>;
}

function Overview({ stats, orders, shipments }) {
  return <>
    <section className="metrics">
      {[
        ["Total orders", stats.totalOrders, "All customer orders"],
        ["Pending orders", stats.pendingOrders, "Awaiting inventory"],
        ["Active shipments", stats.activeShipments, "Currently moving"],
        ["Low stock", stats.lowStock, "Needs attention"]
      ].map(([label, value, note]) =>
        <article className="metric" key={label}><span>{label}</span><strong>{value}</strong><small>{note}</small></article>)}
    </section>
    <section className="grid-two">
      <article className="panel">
        <div className="panel-head"><h2>Recent orders</h2><span className="muted">Live records</span></div>
        <Table headers={["Order", "Customer", "Status", "Value"]} rows={orders.map(order =>
          [order.id, order.customer, <Status value={order.status} />, `₹${(order.total || 0).toLocaleString("en-IN")}`])} />
      </article>
      <article className="panel">
        <div className="panel-head"><h2>Shipment activity</h2><span className="muted">Latest updates</span></div>
        {shipments.map(shipment => <div className="activity" key={shipment.id}>
          <span className="dot"></span>
          <div><strong>{shipment.id}</strong><p>{shipment.tracking?.at(-1)?.status || shipment.status}</p></div>
          <Status value={shipment.status} />
        </div>)}
        {!shipments.length && <p className="empty">No shipments found.</p>}
      </article>
    </section>
  </>;
}

function Orders({ orders, onCreate }) {
  const [sku, setSku] = useState("SCN-100");
  const [busy, setBusy] = useState(false);

  async function create() {
    setBusy(true);
    try { await onCreate({ sku: sku.trim(), quantity: 1, total: 2500 }); }
    finally { setBusy(false); }
  }

  return <article className="panel">
    <div className="panel-head">
      <h2>Order management</h2>
      <div><input aria-label="SKU" value={sku} onChange={e => setSku(e.target.value)} />
        <button disabled={busy || !sku.trim()} onClick={create}>{busy ? "Creating..." : "Create order"}</button></div>
    </div>
    <Table headers={["Order", "Created", "SKU", "Status", "Value"]} rows={orders.map(order =>
      [order.id, new Date(order.createdAt).toLocaleDateString(), order.sku, <Status value={order.status} />, `₹${(order.total || 0).toLocaleString("en-IN")}`])} />
  </article>;
}

function Inventory({ items, canManage, onSave }) {
  const [drafts, setDrafts] = useState({});
  const [savingSku, setSavingSku] = useState(null);

  async function save(item) {
    const available = Number(drafts[item.sku] ?? item.available);
    if (!Number.isInteger(available) || available < 0) return;
    setSavingSku(item.sku);
    try {
      if (await onSave(item.sku, available)) {
        setDrafts(current => ({ ...current, [item.sku]: available }));
      }
    } finally {
      setSavingSku(null);
    }
  }

  return <article className="panel">
    <div className="panel-head"><h2>Inventory position</h2><span className="muted">PostgreSQL stock records</span></div>
    <Table headers={["SKU", "Product", "Warehouse", "Available", "Reorder point", ...(canManage ? ["Action"] : [])]}
      rows={items.map(item => [
        item.sku, item.name || item.sku, item.warehouse || "Central",
        canManage
          ? <input className="stock-input" aria-label={`Available stock for ${item.sku}`} type="number" min="0" step="1"
              value={drafts[item.sku] ?? item.available}
              onChange={e => setDrafts(current => ({ ...current, [item.sku]: e.target.value }))} />
          : <strong className={item.available < 10 ? "warning-text" : ""}>{item.available}</strong>,
        10,
        ...(canManage ? [<button className="ghost" disabled={savingSku === item.sku} onClick={() => save(item)}>
          {savingSku === item.sku ? "Saving..." : "Update stock"}
        </button>] : [])
      ])} />
  </article>;
}

function Shipments({ shipments, canManage, onDeliver }) {
  const [deliveringId, setDeliveringId] = useState(null);

  async function deliver(id) {
    setDeliveringId(id);
    try { await onDeliver(id); }
    finally { setDeliveringId(null); }
  }

  return <article className="panel">
    <div className="panel-head"><h2>Shipment tracking</h2><span className="muted">Current state and tracking history</span></div>
    {shipments.map(shipment => <div className="shipment-row" key={shipment.id}>
      <div><strong>{shipment.id}</strong><p>Order {shipment.orderId}</p></div>
      <div><Status value={shipment.status} /></div>
      <div className="timeline">{(shipment.tracking || []).map((event, index) =>
        <span key={`${event.at || event.status}-${index}`}>
          <strong>{labels[event.status] || event.status}</strong>
          {event.location && ` · ${event.location}`}
          {event.at && ` · ${new Date(event.at).toLocaleString()}`}
          {event.detail && <small>{event.detail}</small>}
        </span>)}</div>
      {canManage && shipment.status !== "DELIVERED" &&
        <button className="ghost" disabled={deliveringId === shipment.id} onClick={() => deliver(shipment.id)}>
          {deliveringId === shipment.id ? "Updating..." : "Mark delivered"}
        </button>}
    </div>)}
    {!shipments.length && <p className="empty">No shipments found.</p>}
  </article>;
}

function Notifications({ items, onMarkRead }) {
  const [updatingId, setUpdatingId] = useState(null);

  async function markRead(id) {
    setUpdatingId(id);
    try { await onMarkRead(id); }
    finally { setUpdatingId(null); }
  }

  return <article className="panel">
    <div className="panel-head"><h2>Notifications</h2><span className="muted">{items.filter(item => !item.read).length} unread</span></div>
    {items.map(item => <div className={item.read ? "notification read" : "notification"} key={item.id}>
      <div>
        <strong>Order {item.recipient || "update"}</strong>
        <p>{item.message}</p>
        <small>{new Date(item.createdAt).toLocaleString()}</small>
      </div>
      {!item.read && <button className="ghost" disabled={updatingId === item.id} onClick={() => markRead(item.id)}>
        {updatingId === item.id ? "Updating..." : "Mark read"}
      </button>}
    </div>)}
    {!items.length && <p className="empty">No notifications found.</p>}
  </article>;
}

function Table({ headers, rows }) {
  return <table><thead><tr>{headers.map(header => <th key={header}>{header}</th>)}</tr></thead>
    <tbody>{rows.length ? rows.map((row, i) => <tr key={i}>{row.map((cell, j) => <td key={j}>{cell}</td>)}</tr>)
      : <tr><td colSpan={headers.length} className="empty">No records found.</td></tr>}</tbody>
  </table>;
}

function Status({ value }) {
  return <span className={`status ${value?.toLowerCase()}`}>{labels[value] || value}</span>;
}

createRoot(document.getElementById("root")).render(<App />);
