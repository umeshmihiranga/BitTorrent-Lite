package com.p2p.tracker;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.DatagramSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts the tracker TCP and UDP listeners.
 */
public class TrackerServer {

    public static final int TCP_PORT = 8000;
    public static final int UDP_PORT = 8001;

    private static final int TCP_WORKER_THREADS = 32;
    private static final int TCP_QUEUE_CAPACITY = 100;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final long STALE_PEER_CHECK_INITIAL_DELAY_SECONDS = 5;
    private static final long STALE_PEER_CHECK_INTERVAL_SECONDS = 5;

    private final SwarmManager swarmManager;
    private final AtomicBoolean started;
    private final AtomicBoolean shutdown;
    private final Object lifecycleLock;

    private volatile ServerSocket tcpSocket;
    private volatile DatagramSocket udpSocket;
    private volatile ExecutorService tcpExecutor;
    private volatile ScheduledExecutorService pruningExecutor;
    private volatile Thread heartbeatThread;

    public TrackerServer() {
        this.swarmManager = new SwarmManager();
        this.started = new AtomicBoolean(false);
        this.shutdown = new AtomicBoolean(false);
        this.lifecycleLock = new Object();
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        synchronized (lifecycleLock) {
            if (shutdown.get()) {
                return;
            }

            try {
                tcpSocket = new ServerSocket(TCP_PORT);
            } catch (IOException e) {
                System.err.println("Failed to bind TCP port " + TCP_PORT + ": " + e.getMessage());
                shutdown();
                return;
            }

            if (shutdown.get()) {
                shutdown();
                return;
            }

            try {
                udpSocket = new DatagramSocket(UDP_PORT);
            } catch (IOException e) {
                System.err.println("Failed to bind UDP port " + UDP_PORT + ": " + e.getMessage());
                shutdown();
                return;
            }

            if (shutdown.get()) {
                shutdown();
                return;
            }

            try {
                tcpExecutor = new ThreadPoolExecutor(
                    TCP_WORKER_THREADS,
                    TCP_WORKER_THREADS,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(TCP_QUEUE_CAPACITY),
                    new ThreadPoolExecutor.AbortPolicy()
                );

                if (shutdown.get()) {
                    shutdown();
                    return;
                }

                pruningExecutor = Executors.newSingleThreadScheduledExecutor();
                pruningExecutor.scheduleAtFixedRate(
                    swarmManager::removeStalePeers,
                    STALE_PEER_CHECK_INITIAL_DELAY_SECONDS,
                    STALE_PEER_CHECK_INTERVAL_SECONDS,
                    TimeUnit.SECONDS
                );

                heartbeatThread =
                    new Thread(
                        new HeartbeatListener(udpSocket, swarmManager),
                        "tracker-heartbeat-listener"
                    );
                heartbeatThread.start();

                Runtime.getRuntime().addShutdownHook(
                    new Thread(this::shutdown, "tracker-shutdown-hook")
                );
            } catch (RuntimeException e) {
                System.err.println("Failed to initialize tracker: " + e.getMessage());
                shutdown();
                return;
            }
        }

        if (shutdown.get()) {
            return;
        }

        System.out.println("[TRACKER] Listening on TCP port " + TCP_PORT
                + " and UDP port " + UDP_PORT);

        while (!tcpSocket.isClosed()) {
            try {
                Socket peerSocket = tcpSocket.accept();
                if (!tcpExecutor.isShutdown()) {
                    try {
                        tcpExecutor.submit(
                            new TrackerClientHandler(peerSocket, swarmManager)
                        );
                    } catch (RejectedExecutionException e) {
                        peerSocket.close();
                    }
                } else {
                    peerSocket.close();
                }
            } catch (SocketException e) {
                if (shutdown.get() || tcpSocket.isClosed()) {
                    break;
                }
                System.err.println("TCP accept failed: " + e.getMessage());
                break;
            } catch (IOException e) {
                if (shutdown.get() || tcpSocket.isClosed()) {
                    break;
                }
                System.err.println("TCP accept failed: " + e.getMessage());
                break;
            }
        }

        if (!shutdown.get()) {
            shutdown();
        }
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }

        synchronized (lifecycleLock) {
            closeTcpSocket();

            DatagramSocket udp = udpSocket;
            if (udp != null) {
                udp.close();
            }

            ExecutorService tcp = tcpExecutor;
            if (tcp != null) {
                tcp.shutdown();
            }

            ScheduledExecutorService pruning = pruningExecutor;
            if (pruning != null) {
                pruning.shutdownNow();
                try {
                    if (!pruning.awaitTermination(
                            SHUTDOWN_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS)) {
                        pruning.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    pruning.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            awaitHeartbeatShutdown();

            if (tcp != null) {
                awaitTcpShutdown(tcp);
            }
        }
    }

    private void closeTcpSocket() {
        ServerSocket tcp = tcpSocket;
        if (tcp != null) {
            try {
                tcp.close();
            } catch (IOException e) {
                System.err.println("Failed to close TCP socket: " + e.getMessage());
            }
        }
    }

    private void awaitHeartbeatShutdown() {
        Thread heartbeat = heartbeatThread;
        if (heartbeat != null && heartbeat != Thread.currentThread()) {
            try {
                heartbeat.join(TimeUnit.SECONDS.toMillis(SHUTDOWN_TIMEOUT_SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void awaitTcpShutdown(ExecutorService tcp) {
        try {
            if (!tcp.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                tcp.shutdownNow();
                tcp.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            tcp.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        new TrackerServer().start();
    }
}
