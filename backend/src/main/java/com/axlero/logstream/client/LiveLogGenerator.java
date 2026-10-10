package com.axlero.logstream.client;

import com.axlero.logstream.grpc.IngestSummary;
import com.axlero.logstream.grpc.LogIngestServiceGrpc;
import com.axlero.logstream.grpc.LogMessage;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simulates real services continuously writing logs, with timestamp = "now".
 * Use it to demo the Live Tail tab, the auto-refreshing chart, and to trigger alerts.
 * Press Ctrl+C to stop.
 *
 * Usage:  LiveLogGenerator [host] [port] [logsPerSecond] [errorPercent]
 *
 * Normal traffic:        ... LiveLogGenerator localhost 9090 20 5
 * Trigger the alert:     ... LiveLogGenerator localhost 9090 50 80   (about 40 ERRORs per second)
 */
public class LiveLogGenerator {

    public static void main(String[] args) throws InterruptedException {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9090;
        int perSecond = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        int errorPercent = args.length > 3 ? Integer.parseInt(args[3]) : 5;

        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        LogIngestServiceGrpc.LogIngestServiceStub stub = LogIngestServiceGrpc.newStub(channel);
        AtomicBoolean failed = new AtomicBoolean(false);

        StreamObserver<LogMessage> requestObserver = stub.ingestLogs(new StreamObserver<IngestSummary>() {
            @Override
            public void onNext(IngestSummary summary) {
                System.out.println("Stopped. Server indexed " + summary.getCount() + " logs from this run.");
            }

            @Override
            public void onError(Throwable t) {
                failed.set(true);
                System.err.println("Stream failed: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                // nothing to do
            }
        });
        ClientCallStreamObserver<LogMessage> call = (ClientCallStreamObserver<LogMessage>) requestObserver;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                call.onCompleted();
                Thread.sleep(300);
                channel.shutdown();
            } catch (Exception ignored) {
                // exiting anyway
            }
        }));

        System.out.println("Sending ~" + perSecond + " logs/sec (" + errorPercent + "% ERROR) to "
                + host + ":" + port + "  -  press Ctrl+C to stop");

        Random random = new Random();
        double perTick = perSecond / 10.0;   // we send 10 small batches per second
        double carry = 0;
        while (!failed.get()) {
            carry += perTick;
            int n = (int) carry;
            carry -= n;
            for (int i = 0; i < n && !failed.get(); i++) {
                call.onNext(nextLog(random, errorPercent));
            }
            Thread.sleep(100);
        }
        channel.shutdown();
    }

    private static LogMessage nextLog(Random random, int errorPercent) {
        String level;
        if (random.nextInt(100) < errorPercent) {
            level = "ERROR";
        } else {
            level = random.nextInt(100) < 25 ? "WARN" : "INFO";
        }
        int responseTime = level.equals("ERROR") ? 800 + random.nextInt(2200) : random.nextInt(900);
        return LogMessage.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setLevel(level)
                .setService(MockLogProducer.SERVICES[random.nextInt(MockLogProducer.SERVICES.length)])
                .setMessage(MockLogProducer.MESSAGES[random.nextInt(MockLogProducer.MESSAGES.length)])
                .setResponseTime(responseTime)
                .build();
    }
}
