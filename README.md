# Log Stream - complete project (Weeks 1-4)

Distributed log analytics & alerting platform (Axlero Solutions - Advanced Full-Stack Java Engineering, Project 2).

| Week | What it covers | Where |
|---|---|---|
| 1 | protobuf schema, Java gRPC ingestion server, mock log producer, React dashboard scaffold | `log_service.proto`, `LogIngestServiceImpl`, `MockLogProducer`, `frontend/` |
| 2 | Apache Lucene indexing + full-text/filter search, query UI | `LuceneIndexer.search`, `SearchHandler`, `SearchBar.jsx` |
| 3 | Aggregations with Lucene's Facets module (`LongRangeFacetCounts`) + interactive ECharts histogram | `LuceneIndexer.histogram`, `HistogramHandler`, `Histogram.jsx` |
| 4 | Alert rule engine with simulated webhook, Live Tail over WebSockets | `alert/AlertEngine`, `live/LiveTailServer`, `AlertsPanel.jsx`, `LiveTail.jsx` |

## Architecture
```
MockLogProducer / LiveLogGenerator --gRPC :9090--> LogIngestServiceImpl --+--> LuceneIndexer (index on disk)
                                                                          +--> LiveTailServer --WebSocket :8082--> browser (Live Tail tab)
React dashboard --HTTP :8081--> /search /histogram /stats /alerts/*  --> LuceneIndexer / AlertEngine
AlertEngine (runs every 60 s) --> counts matching logs in the rule's window --> simulated webhook when above threshold
```

## 1. Run the backend (Java 17+ and Maven)
```bash
cd backend
mvn clean package
java -jar target/logstream-backend-1.0.0.jar
```
Expected output ends with `Log Stream is ready.` Keep this terminal open.
(If you ran the Week 1-2 version before, delete the old `log-index` folder first for a clean start.)

## 2. Run the frontend (Node 18+)
```bash
cd frontend
npm install
npm run dev
```
Open the printed URL (http://localhost:5173).

## 3. Put data in (new terminal, inside `backend`)
```bash
# 20,000 logs spread over the last hour -> fills the chart and search
java -cp target/logstream-backend-1.0.0.jar com.axlero.logstream.client.MockLogProducer localhost 9090 20000 60
```
Arguments: `host port count spreadMinutes`. It prints the throughput (logs/sec) - use this number in the review.
For the mid-review benchmark run it with `100000`, then search a keyword: the dashboard shows "took N ms".

## 4. Demo scenarios
* **Search**: type `timeout`, pick service/level, set "Min resp. ms" to 1000, change the time range. Histogram and results update together.
* **Live Tail**: open the Live Tail tab, then in another terminal:
  `java -cp target/logstream-backend-1.0.0.jar com.axlero.logstream.client.LiveLogGenerator localhost 9090 20 5`
  (20 logs/sec, 5% errors). Ctrl+C to stop.
* **Trigger an alert**: run the generator with many errors: `... LiveLogGenerator localhost 9090 50 80`
  (about 40 ERRORs/sec). Open the Alerts tab and press "Evaluate now" (or wait up to 60 s): the default rule
  "High ERROR rate" (more than 100 ERRORs in 5 min) goes FIRING, the backend terminal prints `[WEBHOOK SIMULATED] ...`
  and the alert appears in the history table. Create your own rules in the "New alert rule" form.

## Search syntax (typed in the search box)
Plain words search the log message. You can also type the query from the project brief:
```
level:ERROR AND service:billing-api AND response_time > 1000
```
Fields: `level`, `service` (quote values with spaces: `service:"my service"`), `response_time` with `>` `>=` `<` `<=` `=`.
Conditions are combined with AND, and may be mixed with plain words, e.g. `timeout AND level:ERROR`.
The same syntax works in the "Message contains" box of an alert rule. (OR / NOT are not supported.)
The dashboard shows the newest 50 matching lines plus the exact total number of matches.

## Performance (measured while developing: 1,000,000 logs on a single machine)
* Ingestion through the ingestion service: about 130,000 logs/sec (target: 10,000).
* Keyword search over 1,000,000 logs: about 15-45 ms once the JVM is warmed up (target: under 50 ms).
  The very first search after the server starts is slower (JVM warm-up), so **run one search before the demo**.
* Your numbers will differ with your hardware - always quote the numbers you measure yourself with
  `MockLogProducer ... 1000000` and the "took N ms" shown in the dashboard.

## Settings (all optional, no code changes needed)
`java -Dhttp.port=8085 -Dws.port=8086 -Dgrpc.port=9091 -Dindex.path=./my-index -Dalert.interval.seconds=30 -jar target/logstream-backend-1.0.0.jar`
If you change a port, create `frontend/.env` with `VITE_API_BASE=http://localhost:8085` and `VITE_WS_URL=ws://localhost:8086`.
`-Dindex.directory=nio` makes Lucene use plain file I/O if memory-mapped files ever cause trouble.

## Troubleshooting
* "port already in use": the server prints which ports it needs; stop the other program or use the `-D` options above.
* "Lock held by another program": another Log Stream server is already running with the same `log-index` folder.
* Live Tail shows "Cannot connect": the page retries every 3 s. In the backend console look for
  `Live tail WebSocket server listening on port 8082`. If the server printed "could not start", port 8082 is taken:
  start with `-Dws.port=8083` and create `frontend/.env` containing `VITE_WS_URL=ws://localhost:8083`.
  (Check a port on Windows with `netstat -ano | findstr :8082` - only the java process should be listed.)
* Search needs a warm-up: the first query after a big ingest can take 50-200 ms; repeat it and it drops to ~10-30 ms.
* Dashboard shows "Cannot reach the backend": the Java server is not running (or the port differs).
* Java 21 may print one Lucene warning about `--enable-preview`; it is harmless.

## REST API (used by the dashboard)
`GET /search?q=&service=&level=&minRt=&minutes=&max=` - `GET /histogram?...&minutes=60&bucket=1` - `GET /stats`
`GET|POST|DELETE /alerts/rules` - `POST /alerts/evaluate` - `GET /alerts/history`
