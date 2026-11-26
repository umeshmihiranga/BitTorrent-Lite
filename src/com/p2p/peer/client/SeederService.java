package com.p2p.peer.client;

import com.p2p.protocol.UdpPacketHelper;
import com.p2p.storage.PieceMetadata;
import com.p2p.storage.PieceStorage;

/**
 * Manages tracker registration and periodic UDP heartbeats for a pure Seeder
 * (a peer that already holds the complete file and does not run DownloadManager).
 */
public class SeederService {

    private final PieceStorage storage;
    private final TrackerClient tracker;
    private final String peerId;
    private final int listenPort;
    private final int trackerUdpPort;
    private final DownloadListener listener;

    private HeartbeatEmitter heartbeat;
    private volatile boolean started;

    public SeederService(PieceStorage storage, TrackerClient tracker,
                         String peerId, int listenPort, DownloadListener listener) {
        this(storage, tracker, peerId, listenPort,
                UdpPacketHelper.DEFAULT_TRACKER_UDP_PORT, listener);
    }

    public SeederService(PieceStorage storage, TrackerClient tracker,
                         String peerId, int listenPort, int trackerUdpPort,
                         DownloadListener listener) {
        this.storage = storage;
        this.tracker = tracker;
        this.peerId = peerId;
        this.listenPort = listenPort;
        this.trackerUdpPort = trackerUdpPort;
        this.listener = listener != null ? listener : new DownloadListener() {};
    }

    /** Registers the shared file with the tracker and begins UDP heartbeats. */
    public synchronized void start() throws Exception {
        if (started) return;
        PieceMetadata meta = storage.getMetadata();
        String fileHash = meta.getMasterHash();
        tracker.registerFile(meta.getFileName(), fileHash, meta.getFileSize(),
                storage.pieceCount(), listenPort);
        listener.onLog("[TCP] REGISTER_FILE sent to tracker -> REGISTER_SUCCESS (seeding "
                + meta.getFileName() + ")");

        heartbeat = new HeartbeatEmitter(peerId, fileHash, listenPort,
                tracker.getTrackerHost(), trackerUdpPort, listener);
        heartbeat.start();
        started = true;
    }

    /** Stops heartbeats and unregisters from the tracker. */
    public synchronized void stop() {
        if (!started) return;
        started = false;
        if (heartbeat != null) {
            heartbeat.stop();
            heartbeat = null;
        }
        try {
            tracker.unregisterFile(storage.getMetadata().getMasterHash(), listenPort);
            listener.onLog("[TCP] UNREGISTER_FILE sent to tracker");
        } catch (Exception ignored) {
        }
    }

    public boolean isRunning() {
        return started;
    }
}
