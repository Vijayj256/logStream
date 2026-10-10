// All communication with the Java backend lives in this file.
export const API_BASE = import.meta.env.VITE_API_BASE || "http://localhost:8081";
export const WS_URL = import.meta.env.VITE_WS_URL || "ws://localhost:8082";

export const SERVICES = ["billing-api", "checkout-api", "auth-service", "inventory-service"];
export const LEVELS = ["INFO", "WARN", "ERROR"];

// time range (minutes) -> histogram bucket size (minutes)
export const BUCKET_FOR = { 15: 1, 60: 1, 360: 5, 1440: 15 };

async function request(path, options) {
  let res;
  try {
    res = await fetch(API_BASE + path, options);
  } catch {
    throw new Error(`Cannot reach the backend at ${API_BASE}. Is the Java server running?`);
  }
  let data = null;
  try {
    data = await res.json();
  } catch {
    // empty or non-JSON body
  }
  if (!res.ok) {
    throw new Error((data && data.error) || `Request failed (${res.status})`);
  }
  return data;
}

function filterParams(f) {
  const p = new URLSearchParams();
  if (f.query) p.set("q", f.query);
  if (f.service) p.set("service", f.service);
  if (f.level) p.set("level", f.level);
  if (f.minRt) p.set("minRt", String(f.minRt));
  return p;
}

export function searchLogs(f) {
  const p = filterParams(f);
  p.set("minutes", String(f.minutes));
  p.set("bucket", String(BUCKET_FOR[f.minutes] || 1)); // same time window as the chart
  p.set("max", "50");
  return request(`/search?${p.toString()}`);
}

export function fetchHistogram(f) {
  const p = filterParams(f);
  p.set("minutes", String(f.minutes));
  p.set("bucket", String(BUCKET_FOR[f.minutes] || 1));
  return request(`/histogram?${p.toString()}`);
}

export const fetchStats = () => request("/stats");
export const getAlertRules = () => request("/alerts/rules");
export const getAlertHistory = () => request("/alerts/history");
export const evaluateAlerts = () => request("/alerts/evaluate", { method: "POST" });
export const deleteAlertRule = (id) =>
  request(`/alerts/rules?id=${encodeURIComponent(id)}`, { method: "DELETE" });
export const createAlertRule = (rule) =>
  request("/alerts/rules", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(rule),
  });
