package com.p2p.peer.client;

import com.p2p.protocol.MessageSerializer.PeerEntry;
import com.p2p.storage.FileReassembler;
import com.p2p.storage.PieceStorage;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Leecher core. Periodically asks the tracker for peers, opens one
 * {@link PieceDownloadWorker} per seeder (up to {@code maxPeers}), and schedules pieces
 * rarest-first so different workers always download disjoint pieces in parallel.
 * Failed peers are put on a cooldown; their in-flight pieces are re-queued automatically.
 *
 * <p>Usage: {@code new DownloadManager(storage, tracker, peerId, listenPort, out, listener).start()}</p>
 *
 * @author Member 5 — Download Engine
 */
public class DownloadManager {

    private static final int  DEFAULT_MAX_PEERS   = 5;
    private static final long REFRESH_INTERVAL_MS = 3000;
    private static final long PEER_COOLDOWN_MS    = 15000;

    private final PieceStorage storage;
    private final TrackerClient tracker;
    private final String peerId;
    private final int listenPort;
    private final Path outputFile;
    private final DownloadListener listener;
    private final String fileHash;
    private final int maxPeers;
    private final int trackerUdpPort;

    // scheduler state — guarded by 'this'
    private final Set<Integer> inProgress = new HashSet<>();
    private final int[] availability;

    private final Map<String, PieceDownloadWorker> activeWorkers = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Set<String> localAddresses = new HashSet<>();

    private ExecutorService workerPool;
    private Thread coordinator;
    private HeartbeatEmitter heartbeat;
    private volatile boolean stopped;
    private volatile boolean finished;

    public DownloadManager(PieceStorage storage, TrackerClient tracker, String peerId,
                           int listenPort, Path outputFile, DownloadListener listener) {
        this(storage, tracker, peerId, listenPort, outputFile, listener,
                DEFAULT_MAX_PEERS, com.p2p.protocol.UdpPacketHelper.DEFAULT_TRACKER_UDP_PORT);
    }

    public DownloadManager(PieceStorage storage, TrackerClient tracker, String peerId,
                           int listenPort, Path outputFile, DownloadListener listener,
                           int maxPeers, int trackerUdpPort) {
        this.storage = storage;
        this.tracker = tracker;
        this.peerId = peerId;
        this.listenPort = listenPort;
        this.outputFile = outputFile;
        this.listener = listener != null ? listener : new DownloadListener() {};
        this.fileHash = storage.getMetadata().getMasterHash();
        this.maxPeers = maxPeers;
        this.trackerUdpPort = trackerUdpPort;
        this.availability = new int[storage.pieceCount()];
        collectLocalAddresses();
    }

    // ───────────────────────── lifecycle ─────────────────────────

    public synchronized void start() throws Exception {
        if (coordinator != null) return;
        var meta = storage.getMetadata();
        tracker.registerFile(meta.getFileName(), fileHash, meta.getFileSize(),
                storage.pieceCount(), listenPort);
        listener.onLog("[TCP] REGISTER_FILE sent to tracker -> REGISTER_SUCCESS");

        heartbeat = new HeartbeatEmitter(peerId, fileHash, listenPort,
                tracker.getTrackerHost(), trackerUdpPort, listener);
        heartbeat.start();

        workerPool = Executors.newFixedThreadPool(maxPeers, r -> {
            Thread t = new Thread(r, "piece-worker");
            t.setDaemon(true);
            return t;
        });
        coordinator = new Thread(this::coordinate, "download-coordinator");
        coordinator.setDaemon(true);
        coordinator.start();
    }

    /** Cancels the download and leaves the swarm. */
    public void stop() {
        stopped = true;
        activeWorkers.values().forEach(PieceDownloadWorker::abort);
        if (workerPool != null) workerPool.shutdownNow();
        if (coordinator != null) coordinator.interrupt();
        if (heartbeat != null) heartbeat.stop();
        try { tracker.unregisterFile(fileHash, listenPort); } catch (Exception ignored) {}
    }

    public boolean isFinished() { return finished || stopped; }
    boolean isStopped() { return stopped; }

    // ───────────────────────── coordinator ─────────────────────────

    private void coordinate() {
        try {
            while (!stopped && !storage.isComplete()) {
                refreshPeers();
                Thread.sleep(REFRESH_INTERVAL_MS);
            }
            if (stopped) return;
            // all pieces verified -> stitch final file
            FileReassembler.reassemble(storage, outputFile);
            finished = true;
            listener.onLog("[DONE] File reassembled: " + outputFile);
            listener.onDownloadComplete(outputFile);
            if (heartbeat != null) { /* keep heartbeating: we are now a seeder; caller may stop() */ }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            listener.onError("Download engine failed: " + e.getMessage());
        }
    }

    private void refreshPeers() {
        List<PeerEntry> peers;
        try {
            peers = tracker.getPeers(fileHash);
            listener.onLog("[TCP] GET_PEERS -> " + peers.size() + " peer(s)");
        } catch (Exception e) {
            listener.onLog("[ERR] tracker unreachable: " + e.getMessage());
            return;
        }
        long now = System.currentTimeMillis();
        for (PeerEntry p : peers) {
            if (activeWorkers.size() >= maxPeers) break;
            if (isSelf(p)) continue;
            String addr = p.ip + ":" + p.port;
            if (activeWorkers.containsKey(addr)) continue;
            if (cooldownUntil.getOrDefault(addr, 0L) > now) continue;
            PieceDownloadWorker w = new PieceDownloadWorker(
                    this, storage, fileHash, peerId, p.ip, p.port, listener);
            if (activeWorkers.putIfAbsent(addr, w) == null) {
                try { workerPool.execute(w); }
                catch (RejectedExecutionException e) { activeWorkers.remove(addr); return; }
            }
        }
    }

    // ───────────────────────── scheduling (called by workers) ─────────────────────────

    /** Claims the rarest missing piece that the given peer owns, or -1 if none. */
    synchronized int claimPiece(BitSet peerHas) {
        int best = -1;
        for (int i = peerHas.nextSetBit(0); i >= 0; i = peerHas.nextSetBit(i + 1)) {
            if (storage.hasPiece(i) || inProgress.contains(i)) continue;
            if (best < 0 || availability[i] < availability[best]) best = i;
        }
        if (best >= 0) inProgress.add(best);
        return best;
    }

    synchronized void releasePiece(int index) { inProgress.remove(index); }

    /** True when no piece is missing-and-unclaimed, or the engine is shutting down. */
    synchronized boolean nothingLeftToClaim() {
        if (isFinished()) return true;
        for (int i = 0; i < availability.length; i++)
            if (!storage.hasPiece(i) && !inProgress.contains(i)) return false;
        return true;
    }

    synchronized void pieceDone(int index, String addr) {
        inProgress.remove(index);
        listener.onPieceCompleted(index, addr, storage.completedCount(), storage.pieceCount());
    }

    synchronized void addAvailability(BitSet bits) {
        for (int i = bits.nextSetBit(0); i >= 0 && i < availability.length; i = bits.nextSetBit(i + 1))
            availability[i]++;
    }

    synchronized void removeAvailability(BitSet bits) {
        for (int i = bits.nextSetBit(0); i >= 0 && i < availability.length; i = bits.nextSetBit(i + 1))
            if (availability[i] > 0) availability[i]--;
    }

    void workerFinished(String addr, boolean ok) {
        activeWorkers.remove(addr);
        if (!ok) cooldownUntil.put(addr, System.currentTimeMillis() + PEER_COOLDOWN_MS);
    }

    // ───────────────────────── helpers ─────────────────────────

    private boolean isSelf(PeerEntry p) {
        return p.port == listenPort && localAddresses.contains(p.ip);
    }

    private void collectLocalAddresses() {
        localAddresses.add("127.0.0.1");
        localAddresses.add("0:0:0:0:0:0:0:1");
        localAddresses.add("::1");
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    localAddresses.add(a.getHostAddress());
        } catch (Exception ignored) {}
    }
}
