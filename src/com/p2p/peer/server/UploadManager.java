package com.p2p.peer.server;

import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

import com.p2p.storage.PieceStorage;

/**
 * Bounded thread-pool manager for concurrent peer upload connections.
 *
 * @author Member 3 - Peer Upload Server
 */
public class UploadManager {

    public static final int DEFAULT_THREAD_COUNT = 16;
    public static final int DEFAULT_QUEUE_CAPACITY = 64;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final PieceStorage storage;
    private final String peerId;
    private final ExecutorService executor;
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicInteger rejectedConnections = new AtomicInteger();
    private final java.util.Set<SocketChannel> connections =
            ConcurrentHashMap.newKeySet();
    private volatile boolean shutdown;

    public UploadManager(PieceStorage storage, String peerId) {
        this(storage, peerId, DEFAULT_THREAD_COUNT, DEFAULT_QUEUE_CAPACITY);
    }

    public UploadManager(PieceStorage storage, String peerId,
                         int threadCount, int queueCapacity) {
        if (storage == null) {
            throw new IllegalArgumentException("PieceStorage must not be null");
        }
        if (threadCount <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("Thread count and queue capacity must be positive");
        }
        this.storage = storage;
        this.peerId = peerId == null ? "peer-seeder" : peerId;

        AtomicInteger threadNumber = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable,
                    "peer-upload-" + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };

        this.executor = new ThreadPoolExecutor(
                threadCount,
                threadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(queueCapacity),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Assigns one accepted socket to an independent upload handler.
     */
    public void dispatch(SocketChannel channel) {
        if (channel == null) {
            return;
        }
        Socket socket = channel.socket();
        if (shutdown || executor.isShutdown()) {
            closeQuietly(channel);
            rejectedConnections.incrementAndGet();
            return;
        }

        activeConnections.incrementAndGet();
        connections.add(channel);
        try {
            executor.execute(() -> {
                try {
                    new PeerUploadHandler(storage, peerId, socket).run();
                } finally {
                    connections.remove(channel);
                    activeConnections.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            connections.remove(channel);
            activeConnections.decrementAndGet();
            rejectedConnections.incrementAndGet();
            closeQuietly(channel);
            System.err.println("[UPLOAD] Rejected peer connection: "
                    + socketAddress(socket));
        }
    }

    public int getActiveConnectionCount() {
        return activeConnections.get();
    }

    public int getRejectedConnectionCount() {
        return rejectedConnections.get();
    }

    /**
     * Stops accepting new tasks and waits briefly for active handlers.
     */
    public void shutdown() {
        shutdown = true;
        executor.shutdown();
        for (SocketChannel channel : connections) {
            closeQuietly(channel);
        }
        connections.clear();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static String socketAddress(Socket socket) {
        if (socket.getRemoteSocketAddress() != null) {
            return socket.getRemoteSocketAddress().toString();
        }
        return "unknown-peer";
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (Exception ignored) {
            closeQuietly(channel.socket());
        }
    }
}
