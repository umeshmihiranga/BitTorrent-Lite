package com.p2p.peer.server;

import com.p2p.storage.PieceStorage;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.net.StandardSocketOptions;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background NIO TCP listener for incoming peer upload connections.
 *
 * @author Member 3 - Peer Upload Server
 */
public class PeerServer {

    public static final int DEFAULT_QUEUE_BACKLOG = 50;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final UploadManager uploadManager;
    private final String peerId;
    private final int configuredPort;
    private final InetAddress bindAddress;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    private volatile ServerSocketChannel serverChannel;
    private volatile Thread acceptThread;
    private volatile int boundPort;

    public PeerServer(int listenPort, PieceStorage storage) {
        this(listenPort, InetAddress.getLoopbackAddress(), storage,
                "peer-seeder");
    }

    public PeerServer(int listenPort, PieceStorage storage, String peerId) {
        this(listenPort, InetAddress.getLoopbackAddress(), storage, peerId);
    }

    public static PeerServer forLocalhost(int listenPort,
                                          PieceStorage storage,
                                          String peerId) {
        return new PeerServer(listenPort,
                InetAddress.getLoopbackAddress(), storage, peerId);
    }

    public static PeerServer forLan(int listenPort,
                                    PieceStorage storage,
                                    String peerId) {
        try {
            return new PeerServer(listenPort,
                    InetAddress.getByName("0.0.0.0"), storage, peerId);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Could not configure wildcard bind address", e);
        }
    }

    public PeerServer(int listenPort, InetAddress bindAddress,
                      PieceStorage storage, String peerId) {
        if (storage == null) {
            throw new IllegalArgumentException("PieceStorage must not be null");
        }
        if (listenPort < 0 || listenPort > 65535) {
            throw new IllegalArgumentException("Invalid listening port: "
                    + listenPort);
        }
        if (bindAddress == null) {
            throw new IllegalArgumentException("Bind address must not be null");
        }
        this.configuredPort = listenPort;
        this.bindAddress = bindAddress;
        this.peerId = peerId == null ? "peer-seeder" : peerId;
        this.uploadManager = new UploadManager(storage, this.peerId);
    }

    public PeerServer(int listenPort, InetAddress bindAddress,
                      UploadManager uploadManager, String peerId) {
        if (listenPort < 0 || listenPort > 65535) {
            throw new IllegalArgumentException("Invalid listening port: "
                    + listenPort);
        }
        if (bindAddress == null) {
            throw new IllegalArgumentException("Bind address must not be null");
        }
        if (uploadManager == null) {
            throw new IllegalArgumentException("UploadManager must not be null");
        }
        this.configuredPort = listenPort;
        this.bindAddress = bindAddress;
        this.peerId = peerId == null ? "peer-seeder" : peerId;
        this.uploadManager = uploadManager;
    }

    /**
     * Opens the NIO listener and starts its accept loop on a daemon thread.
     * This method never blocks the calling GUI/application thread.
     */
    public void start() {
        synchronized (lifecycleLock) {
            if (started.get()) {
                return;
            }
            if (shutdown.get()) {
                throw new IllegalStateException(
                        "PeerServer has already been shut down");
            }

            ServerSocketChannel channel = null;
            try {
                channel = ServerSocketChannel.open();
                channel.setOption(StandardSocketOptions.SO_REUSEADDR, Boolean.TRUE);
                channel.bind(new InetSocketAddress(bindAddress, configuredPort),
                        DEFAULT_QUEUE_BACKLOG);

                serverChannel = channel;
                boundPort = channel.socket().getLocalPort();

                Thread accept = new Thread(this::acceptLoop,
                        "peer-seeder-listener-" + boundPort);
                accept.setDaemon(true);

                if (shutdown.get()) {
                    closeChannel(channel);
                    serverChannel = null;
                    started.set(false);
                    throw new IllegalStateException(
                            "PeerServer shut down during startup");
                }

                acceptThread = accept;
                started.set(true);
                accept.start();

                System.out.println("[UPLOAD] Peer server listening on "
                        + bindAddress.getHostAddress() + ":" + boundPort);
            } catch (IOException e) {
                if (channel != null) {
                    closeChannel(channel);
                }
                serverChannel = null;
                acceptThread = null;
                started.set(false);
                throw new IllegalStateException(
                        "Failed to start peer server on port " + configuredPort
                                + ": " + e.getMessage(), e);
            } catch (RuntimeException e) {
                if (channel != null) {
                    closeChannel(channel);
                }
                serverChannel = null;
                acceptThread = null;
                started.set(false);
                throw e;
            }
        }
    }

    private void closeChannel(ServerSocketChannel channel) {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (IOException e) {
            System.err.println("[UPLOAD] Failed to close partially opened listener: "
                    + e.getMessage());
        }
    }

    private void acceptLoop() {
        while (!shutdown.get()) {
            ServerSocketChannel listener = serverChannel;
            if (listener == null || !listener.isOpen()) {
                break;
            }

            try {
                SocketChannel peerChannel = listener.accept();
                if (peerChannel == null) {
                    continue;
                }

                peerChannel.setOption(StandardSocketOptions.SO_KEEPALIVE,
                        Boolean.TRUE);
                peerChannel.setOption(StandardSocketOptions.TCP_NODELAY,
                        Boolean.TRUE);

                peerChannel.socket().setSoTimeout(
                        PeerUploadHandler.SOCKET_TIMEOUT_MS);
                uploadManager.dispatch(peerChannel);
            } catch (SocketException e) {
                if (!shutdown.get()) {
                    System.err.println("[UPLOAD] Peer accept socket error: "
                            + e.getMessage());
                }
            } catch (IOException e) {
                if (!shutdown.get()) {
                    System.err.println("[UPLOAD] Peer accept failed: "
                            + e.getMessage());
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Closes the accept channel and shuts down all upload worker resources.
     */
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }

        synchronized (lifecycleLock) {
            ServerSocketChannel listener = serverChannel;
            if (listener != null) {
                try {
                    listener.close();
                } catch (IOException e) {
                    System.err.println("[UPLOAD] Failed to close listener: "
                            + e.getMessage());
                }
            }

            uploadManager.shutdown();

            Thread accept = acceptThread;
            if (accept != null && accept != Thread.currentThread()) {
                try {
                    accept.join(java.util.concurrent.TimeUnit
                            .SECONDS.toMillis(SHUTDOWN_TIMEOUT_SECONDS));
                } catch (InterruptedException e) {
                    accept.interrupt();
                    Thread.currentThread().interrupt();
                }
            }
        }

        System.out.println("[UPLOAD] Peer server stopped");
    }

    public boolean isRunning() {
        return started.get() && !shutdown.get();
    }

    public int getBoundPort() {
        return boundPort;
    }

    public int getConfiguredPort() {
        return configuredPort;
    }

    public UploadManager getUploadManager() {
        return uploadManager;
    }
}
