function levelClass(level) {
  if (level === "ERROR") return "badge error";
  if (level === "WARN") return "badge warn";
  return "badge info";
}

function LogResultsList({ result }) {
  if (!result) return null;
  const hits = result.hits || [];

  return (
    <div className="card">
      <div className="card-title">
        <span>Matching logs</span>
        <span className="muted">
          {result.total.toLocaleString()} found
          {hits.length < result.total ? `, showing newest ${hits.length}` : ""} - took {result.tookMs} ms
        </span>
      </div>
      {hits.length === 0 ? (
        <p className="muted pad">No logs matched this query in the selected time range.</p>
      ) : (
        <div className="table-wrap">
          <table className="results-table">
            <thead>
              <tr>
                <th>Time</th>
                <th>Level</th>
                <th>Service</th>
                <th>Message</th>
                <th>Resp. time</th>
              </tr>
            </thead>
            <tbody>
              {hits.map((log, idx) => (
                <tr key={idx}>
                  <td>{new Date(log.timestamp).toLocaleTimeString()}</td>
                  <td><span className={levelClass(log.level)}>{log.level}</span></td>
                  <td>{log.service}</td>
                  <td>{log.message}</td>
                  <td>{log.responseTimeMs} ms</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export default LogResultsList;
