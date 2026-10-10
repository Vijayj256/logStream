package com.axlero.logstream.grpc;

import com.axlero.logstream.index.LuceneIndexer;
import com.axlero.logstream.live.LiveTailServer;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles the client-streaming IngestLogs RPC. For every LogMessage received it
 *   1. pushes the log to the live-tail WebSocket clients (a no-op when nobody is watching), and
 *   2. writes it into the shared Lucene index.
 * When the client finishes its stream, it makes the new logs searchable and replies with a count.
 */
public class LogIngestServiceImpl extends LogIngestServiceGrpc.LogIngestServiceImplBase {

    private final LuceneIndexer indexer;
    private final LiveTailServer liveTail;

    public LogIngestServiceImpl(LuceneIndexer indexer, LiveTailServer liveTail) {
        this.indexer = indexer;
        this.liveTail = liveTail;
    }

    @Override
    public StreamObserver<LogMessage> ingestLogs(StreamObserver<IngestSummary> responseObserver) {
        AtomicInteger count = new AtomicInteger(0);

        return new StreamObserver<LogMessage>() {
            @Override
            public void onNext(LogMessage log) {
                liveTail.publish(log.getTimestamp(), log.getLevel(), log.getService(),
                        log.getMessage(), log.getResponseTime());
                try {
                    indexer.indexLog(
                            log.getTimestamp(),
                            log.getLevel(),
                            log.getService(),
                            log.getMessage(),
                            log.getResponseTime());
                    count.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("Failed to index log: " + e.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("Client stream error: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                try {
                    indexer.refresh();
                } catch (Exception ignored) {
                    // the next search refreshes anyway
                }
                IngestSummary summary = IngestSummary.newBuilder()
                        .setCount(count.get())
                        .build();
                responseObserver.onNext(summary);
                responseObserver.onCompleted();
                System.out.println("Ingested " + count.get() + " logs in this stream.");
            }
        };
    }
}
