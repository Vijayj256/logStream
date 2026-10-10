import { useCallback, useEffect, useState } from "react";
import {
  LEVELS, SERVICES, createAlertRule, deleteAlertRule, evaluateAlerts, getAlertHistory, getAlertRules,
} from "./api";

const EMPTY_FORM = {
  name: "", q: "", service: "", level: "ERROR", minResponseTime: "", windowMinutes: 5, threshold: 100,
  webhookUrl: "http://localhost:9000/webhook",
};

function describe(rule) {
  const q = rule.query || {};
  const parts = [];
  if (q.q) parts.push(`text "${q.q}"`);
  if (q.service) parts.push(`service ${q.service}`);
  if (q.level) parts.push(`level ${q.level}`);
  if (q.minResponseTime) parts.push(`response >= ${q.minResponseTime} ms`);
  return `${parts.length ? parts.join(", ") : "all logs"} - more than ${rule.threshold} in ${rule.windowMinutes} min`;
}

// Week 4: alert rules (saved queries), their live status, and the history of fired (simulated) webhooks.
function AlertsPanel() {
  const [rules, setRules] = useState([]);
  const [history, setHistory] = useState([]);
  const [form, setForm] = useState(EMPTY_FORM);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const [r, h] = await Promise.all([getAlertRules(), getAlertHistory()]);
      setRules(r);
      setHistory(h);
      setError("");
    } catch (e) {
      setError(e.message);
    }
  }, []);

  useEffect(() => {
    refresh();
    const id = setInterval(refresh, 5000);
    return () => clearInterval(id);
  }, [refresh]);

  function setField(name, value) {
    setForm((f) => ({ ...f, [name]: value }));
  }

  async function handleCreate(e) {
    e.preventDefault();
    setBusy(true);
    try {
      await createAlertRule({
        name: form.name,
        windowMinutes: Number(form.windowMinutes),
        threshold: Number(form.threshold),
        webhookUrl: form.webhookUrl,
        query: { q: form.q, service: form.service, level: form.level, minResponseTime: Number(form.minResponseTime) || 0 },
      });
      setForm(EMPTY_FORM);
      await refresh();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(id) {
    if (!window.confirm("Delete this alert rule?")) return;
    try {
      await deleteAlertRule(id);
      await refresh();
    } catch (err) {
      setError(err.message);
    }
  }

  async function handleEvaluate() {
    setBusy(true);
    try {
      await evaluateAlerts();
      await refresh();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {error && <div className="error-box">{error}</div>}

      <div className="card">
        <div className="card-title">
          <span>Alert rules</span>
          <button onClick={handleEvaluate} disabled={busy}>Evaluate now</button>
        </div>
        <p className="muted pad">
          The server checks every rule automatically once a minute. A rule fires its webhook once when it goes from
          OK to FIRING, and re-arms when the count drops back below the threshold.
        </p>
        {rules.length === 0 ? (
          <p className="muted pad">No rules yet - create one below.</p>
        ) : (
          <div className="table-wrap">
            <table className="results-table">
              <thead>
                <tr><th>Status</th><th>Rule</th><th>Condition</th><th>Last count</th><th>Last checked</th><th /></tr>
              </thead>
              <tbody>
                {rules.map((r) => (
                  <tr key={r.id}>
                    <td><span className={r.firing ? "badge error" : "badge ok"}>{r.firing ? "FIRING" : "OK"}</span></td>
                    <td><b>{r.name}</b></td>
                    <td>{describe(r)}</td>
                    <td>{r.lastCount < 0 ? "-" : r.lastCount.toLocaleString()}</td>
                    <td>{r.lastEvaluated ? new Date(r.lastEvaluated).toLocaleTimeString() : "never"}</td>
                    <td><button onClick={() => handleDelete(r.id)}>Delete</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card">
        <div className="card-title"><span>New alert rule</span></div>
        <form className="rule-form" onSubmit={handleCreate}>
          <label>Name
            <input required value={form.name} onChange={(e) => setField("name", e.target.value)} placeholder="e.g. Billing errors" />
          </label>
          <label>Message contains
            <input value={form.q} onChange={(e) => setField("q", e.target.value)} placeholder="optional text" />
          </label>
          <label>Service
            <select value={form.service} onChange={(e) => setField("service", e.target.value)}>
              <option value="">Any</option>
              {SERVICES.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </label>
          <label>Level
            <select value={form.level} onChange={(e) => setField("level", e.target.value)}>
              <option value="">Any</option>
              {LEVELS.map((l) => <option key={l} value={l}>{l}</option>)}
            </select>
          </label>
          <label>Min response (ms)
            <input type="number" min="0" value={form.minResponseTime} onChange={(e) => setField("minResponseTime", e.target.value)} />
          </label>
          <label>Window (minutes)
            <input type="number" min="1" max="1440" required value={form.windowMinutes} onChange={(e) => setField("windowMinutes", e.target.value)} />
          </label>
          <label>Fire when count is more than
            <input type="number" min="0" required value={form.threshold} onChange={(e) => setField("threshold", e.target.value)} />
          </label>
          <label>Webhook URL (simulated)
            <input value={form.webhookUrl} onChange={(e) => setField("webhookUrl", e.target.value)} />
          </label>
          <button className="primary" type="submit" disabled={busy}>Create rule</button>
        </form>
      </div>

      <div className="card">
        <div className="card-title"><span>Alert history</span><span className="muted">simulated webhooks that were triggered</span></div>
        {history.length === 0 ? (
          <p className="muted pad">No alert has fired yet.</p>
        ) : (
          <div className="table-wrap">
            <table className="results-table">
              <thead><tr><th>Fired at</th><th>Rule</th><th>Count</th><th>Webhook payload</th></tr></thead>
              <tbody>
                {history.map((h) => (
                  <tr key={h.id}>
                    <td>{new Date(h.firedAt).toLocaleString()}</td>
                    <td><b>{h.ruleName}</b></td>
                    <td>{h.count.toLocaleString()} / {h.threshold}</td>
                    <td><code className="payload">POST {h.webhookUrl} {h.payload}</code></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
}

export default AlertsPanel;
