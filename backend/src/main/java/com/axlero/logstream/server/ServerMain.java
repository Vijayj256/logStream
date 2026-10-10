package com.axlero.logstream.server;

import com.axlero.logstream.alert.AlertEngine;
import com.axlero.logstream.api.AlertEvaluateHandler;
import com.axlero.logstream.api.AlertHistoryHandler;
import com.axlero.logstream.api.AlertRulesHandler;
import com.axlero.logstream.api.HistogramHandler;
import com.axlero.logstream.api.SearchHandler;
import com.axlero.logstream.api.StatsHandler;
import com.axlero.logstream.grpc.LogIngestServiceImpl;
import com.axlero.logstream.index.LuceneIndexer;
import com.axlero.logstream.live.LiveTailServer;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.apache.lucene.store.LockObtainFailedException;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Starts the whole Log Stream backend in one process:
 *
 *   gRPC       :9090  log ingestion (MockLogProducer / LiveLogGenerator / real services)
 *   REST       :8081  /search /histogram /stats /alerts/...   (used by the React dashboard)
 *   WebSocket  :8082  live tail
 *
 * Ports and folders can be changed without editing code, e.g.
 *   java -Dhttp.port=8085 -Dindex.path=./my-index -jar logstream-backend-1.0.0.jar
 */
public class ServerMain {

    public static void main(String[] args) {
        int grpcPort = Integer.getInteger("grpc.port", 9090);
        int httpPort = Integer.getInteger("http.port", 8081);
        int wsPort = Integer.getInteger("ws.port", 8082);
        String indexPath = System.getProperty("index.path", "./log-index");
        String rulesFile = System.getProperty("alert.rules.file", "./alert-rules.json");
        int alertIntervalSeconds = Integer.getInteger("alert.interval.seconds", 60);

        try {
            run(grpcPort, httpPort, wsPort, indexPath, rulesFile, alertIntervalSeconds);
        } catch (Exception e) {
            System.err.println();
            System.err.println("Could not start Log Stream: " + e.getMessage());
            if (e instanceof LockObtainFailedException) {
                System.err.println("-> Another Log Stream server is already using the index folder '" + indexPath + "'.");
                System.err.println("   Stop it first (Ctrl+C in its terminal), or use -Dindex.path=./other-folder");
            } else {
                System.err.println("-> A port is probably already in use (gRPC " + grpcPort + ", HTTP " + httpPort
                        + ", WebSocket " + wsPort + ").");
                System.err.println("   Close the program using it, or pick other ports, e.g.");
                System.err.println("   java -Dhttp.port=8085 -Dws.port=8086 -Dgrpc.port=9091 -jar target/logstream-backend-1.0.0.jar");
            }
            System.exit(1);
        }
    }

    private static void run(int grpcPort, int httpPort, int wsPort, String indexPath,
                            String rulesFile, int alertIntervalSeconds) throws Exception {
        // 1. the shared Lucene index
        LuceneIndexer indexer = new LuceneIndexer(indexPath);

        // 2. Week 4 pieces: alert engine + live tail
        AlertEngine alertEngine = new AlertEngine(indexer, rulesFile, alertIntervalSeconds);
        LiveTailServer liveTail = new LiveTailServer(wsPort);
        liveTail.start();

        // 3. REST API for the dashboard
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
        httpServer.createContext("/search", new SearchHandler(indexer));
        httpServer.createContext("/histogram", new HistogramHandler(indexer));
        httpServer.createContext("/stats", new StatsHandler(indexer, liveTail));
        httpServer.createContext("/alerts/rules", new AlertRulesHandler(alertEngine));
        httpServer.createContext("/alerts/evaluate", new AlertEvaluateHandler(alertEngine));
        httpServer.createContext("/alerts/history", new AlertHistoryHandler(alertEngine));
        httpServer.setExecutor(Executors.newFixedThreadPool(8));
        httpServer.start();
        System.out.println("REST API listening on port " + httpPort);

        // 4. gRPC ingestion (started last, so nothing is ingested before everything else is ready)
        Server grpcServer = ServerBuilder.forPort(grpcPort)
                .addService(new LogIngestServiceImpl(indexer, liveTail))
                .build()
                .start();
        System.out.println("gRPC ingestion server listening on port " + grpcPort);
        System.out.println("Alert rules are checked every " + alertIntervalSeconds + " seconds.");
        System.out.println("Log Stream is ready.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            grpcServer.shutdown();
            httpServer.stop(0);
            liveTail.shutdown();
            alertEngine.shutdown();
            try {
                indexer.close();
            } catch (Exception ignored) {
                // shutting down anyway
            }
        }));

        grpcServer.awaitTermination();
    }
}
