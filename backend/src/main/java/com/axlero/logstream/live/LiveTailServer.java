package com.axlero.logstream.live;

import com.axlero.logstream.model.LogHit;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Week 4: "Live Tail". A WebSocket server that streams incoming logs straight to the browser,
 * bypassing the Lucene index completely.
 *
 * The ingestion service calls publish() for every log. To protect the browser at high ingestion
 * rates, logs are put in a small bounded queue and flushed to all connected clients in one batch
 * every 250 ms (at most the newest 100 lines per batch). If nobody is connected, publish() does
 * nothing at all, so live tail costs nothing when it isn't being watched.
 */
public class LiveTailServer extends WebSocketServer {

    private static final int QUEUE_CAPACITY = 2000;
    private static final int MAX_LINES_PER_BATCH = 100;

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicInteger clientCount = new AtomicInteger(0);
    private final ScheduledExecutorService flusher;

    public LiveTailServer(int port) {
        super(new InetSocketAddress(port));
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "live-tail-flusher");
            t.setDaemon(true);
            return t;
        });
        this.flusher.scheduleWithFixedDelay(this::flushBatch, 250, 250, TimeUnit.MILLISECONDS);
    }

    /** Called by the gRPC ingestion service for every log line. Never blocks. */
    public void publish(long timestamp, String level, String service, String message, int responseTimeMs) {
        if (clientCount.get() == 0 || queue.remainingCapacity() == 0) {
            return; // nobody watching, or we are overloaded: drop (live tail is best-effort)
        }
        queue.offer(gson.toJson(new LogHit(timestamp, level, service, message, responseTimeMs)));
    }

    public int clientCount() {
        return clientCount.get();
    }

    private void flushBatch() {
        try {
            if (clientCount.get() == 0) {
                queue.clear();
                return;
            }
            List<String> drained = new ArrayList<>();
            queue.drainTo(drained);
            if (drained.isEmpty()) {
                return;
            }
            int from = Math.max(0, drained.size() - MAX_LINES_PER_BATCH);
            broadcast("[" + String.join(",", drained.subList(from, drained.size())) + "]");
        } catch (Exception e) {
            System.err.println("Live tail flush failed: " + e.getMessage());
        }
    }

    public void shutdown() {
        flusher.shutdownNow();
        try {
            stop(500);
        } catch (Exception ignored) {
            // shutting down anyway
        }
    }

    // ------------------------------------------------------------------ WebSocketServer callbacks

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        int n = clientCount.incrementAndGet();
        System.out.println("Live tail client connected (" + n + " connected)");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        int n = clientCount.decrementAndGet();
        System.out.println("Live tail client disconnected (" + n + " connected)");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // the browser only listens; anything it sends is ignored
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn == null) {
            // no connection involved = the server itself could not start (usually the port is taken or blocked)
            System.err.println();
            System.err.println("ERROR: live tail WebSocket server could not start on port " + getPort() + ": " + ex);
            System.err.println("       Close the program using that port, or start with e.g. -Dws.port=8083");
            System.err.println("       (and set VITE_WS_URL=ws://localhost:8083 in frontend/.env)");
            System.exit(1);
        }
        System.err.println("Live tail error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("Live tail WebSocket server listening on port " + getPort());
    }
}
