import { useCallback, useEffect, useState } from "react";
import SearchBar from "./SearchBar";
import LogResultsList from "./LogResultsList";
import Histogram from "./Histogram";
import LiveTail from "./LiveTail";
import AlertsPanel from "./AlertsPanel";
import { fetchHistogram, fetchStats, searchLogs } from "./api";
import "./App.css";

const DEFAULT_FILTERS = { query: "", service: "", level: "", minRt: 0, minutes: 60 };

function App() {
  const [tab, setTab] = useState("search");
  const [filters, setFilters] = useState(DEFAULT_FILTERS);
  const [result, setResult] = useState(null);
  const [histogram, setHistogram] = useState(null);
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [autoRefresh, setAutoRefresh] = useState(false);

  const load = useCallback(async (f) => {
    setLoading(true);
    try {
      const [s, h, st] = await Promise.all([searchLogs(f), fetchHistogram(f), fetchStats()]);
      setResult(s);
      setHistogram(h);
      setStats(st);
      setError("");
    } catch (e) {
      setError(e.message || "Something went wrong");
    } finally {
      setLoading(false);
    }
  }, []);

  // load whenever the filters change (and once on start)
  useEffect(() => {
    if (tab === "search") load(filters);
  }, [filters, tab, load]);

  // optional auto-refresh every 5 s, handy while a generator is running
  useEffect(() => {
    if (tab !== "search" || !autoRefresh) return undefined;
    const id = setInterval(() => load(filters), 5000);
    return () => clearInterval(id);
  }, [tab, autoRefresh, filters, load]);

  return (
    <div className="app">
      <header className="app-header">
        <div>
          <h1>Log Stream</h1>
          <p>Distributed log search &amp; analytics</p>
        </div>
        <div className="stat">
          {stats ? <><b>{stats.totalDocs.toLocaleString()}</b> logs indexed</> : "connecting..."}
        </div>
      </header>

      <nav className="tabs">
        <button className={tab === "search" ? "active" : ""} onClick={() => setTab("search")}>Search &amp; Analytics</button>
        <button className={tab === "live" ? "active" : ""} onClick={() => setTab("live")}>Live Tail</button>
        <button className={tab === "alerts" ? "active" : ""} onClick={() => setTab("alerts")}>Alerts</button>
      </nav>

      {tab === "search" && (
        <>
          <SearchBar initial={filters} onSearch={setFilters} />
          <div className="row-between">
            <label className="check">
              <input type="checkbox" checked={autoRefresh} onChange={(e) => setAutoRefresh(e.target.checked)} />
              Auto-refresh every 5 s
            </label>
            {loading && <span className="muted">Loading...</span>}
          </div>
          {error && <div className="error-box">{error}</div>}
          <Histogram data={histogram} />
          <LogResultsList result={result} />
        </>
      )}
      {tab === "live" && <LiveTail />}
      {tab === "alerts" && <AlertsPanel />}
    </div>
  );
}

export default App;
