import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "";
if (!/^http:\/\/(localhost|127\.0\.0\.1):\d+\/?$/.test(baseUrl)) {
  throw new Error("BASE_URL must be a local URL such as http://127.0.0.1:53224/");
}
const target = baseUrl.replace(/\/$/, "");

const email = __ENV.TEST_EMAIL || "customer@logistics.local";
const password = __ENV.TEST_PASSWORD || "Customer123!";
const orderFailures = new Rate("order_creation_failure_rate");
const orderLatency = new Trend("order_creation_duration", true);
const ordersCreated = new Counter("orders_created");

export const options = {
  scenarios: {
    default: {
      executor: "constant-vus",
      vus: 25,
      duration: "60s",
      gracefulStop: "5s"
    }
  },
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: ["p(95)<2000"],
    order_creation_failure_rate: ["rate<0.05"]
  }
};

let token;
let attemptedOrder = false;

function requestParams(authenticated = true, extraHeaders = {}) {
  return {
    headers: {
      "Content-Type": "application/json",
      ...(authenticated && token ? { Authorization: `Bearer ${token}` } : {}),
      ...extraHeaders
    },
    timeout: "10s"
  };
}

export default function () {
  if (!token) {
    const login = http.post(
      `${target}/api/auth/login`,
      JSON.stringify({ email, password }),
      requestParams(false)
    );
    const loginOk = check(login, {
      "login succeeds": response => response.status === 200
    });
    if (!loginOk) {
      throw new Error(`Local login failed with HTTP ${login.status}`);
    }
    token = login.json("token");
  }

  if (!attemptedOrder) {
    attemptedOrder = true;
    const started = Date.now();
    const response = http.post(
      `${target}/api/orders`,
      JSON.stringify({ sku: "SCN-100", quantity: 1, total: 2500 }),
      requestParams(true, { "Idempotency-Key": `load-${__VU}-${Date.now()}` })
    );
    const ok = check(response, {
      "order created": result => result.status === 201
    });
    orderFailures.add(!ok);
    orderLatency.add(Date.now() - started);
    if (ok) ordersCreated.add(1);
  }

  for (const path of ["/api/orders", "/api/inventory", "/api/shipments", "/api/notifications"]) {
    const response = http.get(`${target}${path}`, requestParams());
    check(response, {
      [`${path} responds successfully`]: result => result.status === 200
    });
  }
  sleep(1);
}
