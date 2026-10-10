import { useCallback, useEffect, useRef, useState } from "react";
import { LEVELS, WS_URL } from "./api";

const MAX_LINES = 300;
const RETRY_MS = 3000;

// Week 4: live tail. Logs arrive over a WebSocket straight from the ingestion server (not from the search index).
function LiveTail() {
  const [status, setStatus] = useState("disconnected"); // disconnected | connecting | live | error
  const [lines, setLines] = useState([]);
  const [paused, setPaused] = useState(false);
  const [levelFilter, setLevelFilter] = useState("");
  const socketRef = useRef(null);
  const pausedRef = useRef(false);
  const wantRef = useRef(true);     // false once the user presses Disconnect or leaves the tab
  const retryRef = useRef(null);
  const boxRef = useRef(null);

  useEffect(() => {
    pausedRef.current = paused;
  }, [paused]);

  const connect = useCallback(() => {
    if (socketRef.current) return;
    clearTimeout(retryRef.current);
    setStatus("connecting");
    const ws = new WebSocket(WS_URL);
    socketRef.current = ws;
    // every handler ignores events from a socket that is no longer the current one
    ws.onopen = () => {
      if (socketRef.current === ws) setStatus("live");
    };
    ws.onmessage = (ev) => {
      if (socketRef.current !== ws || pausedRef.current) return;
      try {
        const batch = JSON.parse(ev.data);
        setLines((prev) => [...prev, ...batch].slice(-MAX_LINES));
      } catch {
        // ignore malformed message
      }
    };
    ws.onerror = () => {
      if (socketRef.current === ws) setStatus("error");
    };
    ws.onclose = () => {
      if (socketRef.current !== ws) return;
      socketRef.current = null;
      setStatus((s) => (s === "error" ? "error" : "disconnected"));
      if (wantRef.current) retryRef.current = setTimeout(connect, RETRY_MS); // keep trying until the server is there
    };
  }, []);

  const disconnect = useCallback(() => {
    wantRef.current = false;
    clearTimeout(retryRef.current);
    if (socketRef.current) socketRef.current.close();
  }, []);

  const reconnect = useCallback(() => {
    wantRef.current = true;
    connect();
  }, [connect]);

  // connect when the tab opens (after a tiny delay so React's development double-mount
  // doesn't open a throw-away socket), and clean everything up when it closes
  useEffect(() => {
    wantRef.current = true;
    const timer = setTimeout(connect, 50);
    return () => {
      wantRef.current = false;
      clearTimeout(timer);
      clearTimeout(retryRef.current);
      const ws = socketRef.current;
      socketRef.current = null;
      if (ws) ws.close();
    };
  }, [connect]);

  // keep the view scrolled to the newest line
  useEffect(() => {
    if (!paused && boxRef.current) boxRef.current.scrollTop = boxRef.current.scrollHeight;
  }, [lines, paused]);

  const shown = levelFilter ? lines.filter((l) => l.level === levelFilter) : lines;
  const statusText = {
    disconnected: "Disconnected",
    connecting: "Connecting...",
    live: "Live",
    error: `Cannot connect to ${WS_URL} - retrying every ${RETRY_MS / 1000} s. Check that the backend console shows "Live tail WebSocket server listening".`,
  }[status];

  return (
    <div className="card">
      <div className="card-title">
        <span>
          Live tail <span className={`dot ${status}`} /> <span className="muted">{statusText}</span>
        </span>
        <span className="toolbar">
          <select value={levelFilter} onChange={(e) => setLevelFilter(e.target.value)}>
            <option value="">All levels</option>
            {LEVELS.map((l) => (
              <option key={l} value={l}>{l}</option>
            ))}
          </select>
          <button onClick={() => setPaused((p) => !p)}>{paused ? "Resume" : "Pause"}</button>
          <button onClick={() => setLines([])}>Clear</button>
          {status === "live" || status === "connecting" ? (
            <button onClick={disconnect}>Disconnect</button>
          ) : (
            <button className="primary" onClick={reconnect}>Connect</button>
          )}
        </span>
      </div>
      <div className="tail-box" ref={boxRef}>
        {shown.length === 0 ? (
          <p className="muted pad">
            Waiting for logs... start the generator:
            <code> java -cp target/logstream-backend-1.0.0.jar com.axlero.logstream.client.LiveLogGenerator localhost 9090 20 5</code>
          </p>
        ) : (
          shown.map((l, i) => (
            <div key={i} className="tail-line">
              <span className="muted">{new Date(l.timestamp).toLocaleTimeString([], { hour12: false })}</span>{" "}
              <span className={`lvl ${l.level}`}>{l.level.padEnd(5, "\u00a0")}</span>{" "}
              <span className="svc">{l.service}</span> {l.message}{" "}
              <span className="muted">({l.responseTimeMs} ms)</span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

export default LiveTail;
