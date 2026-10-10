package com.axlero.logstream.client;

import com.axlero.logstream.grpc.IngestSummary;
import com.axlero.logstream.grpc.LogIngestServiceGrpc;
import com.axlero.logstream.grpc.LogMessage;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Sends a batch of fake logs to the ingestion server as fast as possible and reports the throughput.
 * This is the tool for the review benchmark ("ingest and index 10,000 logs per second").
 *
 * Usage:  MockLogProducer [host] [port] [count] [spreadMinutes]
 *   count          how many logs to send                         (default 1000)
 *   spreadMinutes  timestamps are spread randomly over the last N minutes, so the
 *                  histogram chart has something to show         (default 60)
 *
 * Example: java -cp target/logstream-backend-1.0.0.jar com.axlero.logstream.client.MockLogProducer localhost 9090 100000 60
 */
public class MockLogProducer {

    static final String[] SERVICES = {"billing-api", "checkout-api", "auth-service", "inventory-service"};
    static final String[] MESSAGES = {
            "Request processed successfully",
            "Database connection pool exhausted",
            "Timeout while calling downstream service",
            "Payment authorization failed",
            "Cache miss, falling back to DB",
            "Slow query detected",
            "User login succeeded",
            "Invalid token received"
    };

    public static void main(String[] args) throws InterruptedException {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9090;
        int count = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
        int spreadMinutes = args.length > 3 ? Integer.parseInt(args[3]) : 60;

        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        LogIngestServiceGrpc.LogIngestServiceStub stub = LogIngestServiceGrpc.newStub(channel);

        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean(false);
        long start = System.nanoTime();

        StreamObserver<LogMessage> requestObserver = stub.ingestLogs(new StreamObserver<IngestSummary>() {
            @Override
            public void onNext(IngestSummary summary) {
                double seconds = Math.max(0.001, (System.nanoTime() - start) / 1_000_000_000.0);
                System.out.printf("Server acked %d logs in %.2f s  ->  %.0f logs/sec%n",
                        summary.getCount(), seconds, summary.getCount() / seconds);
            }

            @Override
            public void onError(Throwable t) {
                failed.set(true);
                System.err.println("Stream failed: " + t.getMessage());
                System.err.println("Is the backend running on " + host + ":" + port + " ?");
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        });

        // flow control: only send when gRPC says its buffer has room, so 1M logs can't exhaust memory
        ClientCallStreamObserver<LogMessage> call = (ClientCallStreamObserver<LogMessage>) requestObserver;
        Random random = new Random();
        long now = System.currentTimeMillis();
        long spreadMs = Math.max(0, spreadMinutes) * 60_000L;

        try {
            for (int i = 0; i < count && !failed.get(); i++) {
                while (!call.isReady() && !failed.get()) {
                    LockSupport.parkNanos(50_000L);
                }
                if (failed.get()) {
                    break;
                }
                call.onNext(randomLog(random, now - (long) (random.nextDouble() * spreadMs)));
                if ((i + 1) % 100_000 == 0) {
                    System.out.println("Sent " + (i + 1) + " logs...");
                }
            }
            if (!failed.get()) {
                call.onCompleted();
            }
        } catch (IllegalStateException e) {
            failed.set(true);
            System.err.println("Connection to the server was lost: " + e.getMessage());
            done.countDown();
        }

        done.await(10, TimeUnit.MINUTES);
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    static LogMessage randomLog(Random random, long timestamp) {
        int roll = random.nextInt(100);
        String level = roll < 70 ? "INFO" : (roll < 88 ? "WARN" : "ERROR");
        // errors are slower, so "response_time >= 1000" is a meaningful filter
        int responseTime = level.equals("ERROR") ? 800 + random.nextInt(2200) : random.nextInt(900);
        return LogMessage.newBuilder()
                .setTimestamp(timestamp)
                .setLevel(level)
                .setService(SERVICES[random.nextInt(SERVICES.length)])
                .setMessage(MESSAGES[random.nextInt(MESSAGES.length)])
                .setResponseTime(responseTime)
                .build();
    }
}
