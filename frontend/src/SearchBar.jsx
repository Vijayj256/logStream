import { useState } from "react";
import { LEVELS, SERVICES } from "./api";

const RANGES = [
  { value: 15, label: "Last 15 minutes" },
  { value: 60, label: "Last hour" },
  { value: 360, label: "Last 6 hours" },
  { value: 1440, label: "Last 24 hours" },
];

function SearchBar({ initial, onSearch }) {
  const [query, setQuery] = useState(initial.query);
  const [service, setService] = useState(initial.service);
  const [level, setLevel] = useState(initial.level);
  const [minRt, setMinRt] = useState(initial.minRt || "");
  const [minutes, setMinutes] = useState(initial.minutes);

  function handleSubmit(e) {
    e.preventDefault();
    onSearch({ query: query.trim(), service, level, minRt: Number(minRt) || 0, minutes });
  }

  return (
    <>
    <form className="search-bar" onSubmit={handleSubmit}>
      <input
        className="grow"
        type="text"
        placeholder="Search logs, e.g.  timeout   or   level:ERROR AND response_time > 1000"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      <select value={service} onChange={(e) => setService(e.target.value)}>
        <option value="">All services</option>
        {SERVICES.map((s) => (
          <option key={s} value={s}>{s}</option>
        ))}
      </select>
      <select value={level} onChange={(e) => setLevel(e.target.value)}>
        <option value="">All levels</option>
        {LEVELS.map((l) => (
          <option key={l} value={l}>{l}</option>
        ))}
      </select>
      <input
        className="narrow"
        type="number"
        min="0"
        placeholder="Min resp. ms"
        value={minRt}
        onChange={(e) => setMinRt(e.target.value)}
      />
      <select value={minutes} onChange={(e) => setMinutes(Number(e.target.value))}>
        {RANGES.map((r) => (
          <option key={r.value} value={r.value}>{r.label}</option>
        ))}
      </select>
      <button type="submit" className="primary">Search</button>
    </form>
    <p className="hint">
      Type plain words, or a query like{" "}
      <code>level:ERROR AND service:billing-api AND response_time &gt; 1000</code>{" "}
      (fields: level, service, response_time; operators &gt; &gt;= &lt; &lt;= =; conditions are combined with AND).
    </p>
    </>
  );
}

export default SearchBar;
