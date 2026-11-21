package com.p2p.peer.client;

import java.util.BitSet;

/**
 * Callback interface between the download engine (Member 5) and the GUI (Member 6).
 * Callbacks may arrive on any worker thread — GUI code must use SwingUtilities.invokeLater.
 * All methods are default no-ops so the GUI can override only what it needs.
 */
public interface DownloadListener {
    /** A verified piece was saved. */
    default void onPieceCompleted(int pieceIndex, String peerAddress, int completed, int total) {}
    /** A piece request failed or was rejected (will be re-queued). */
    default void onPieceFailed(int pieceIndex, String peerAddress, String reason) {}
    /** Peer state changed: "CONNECTING", "LEECHING", "SEEDING", "FAILED", "DISCONNECTED". */
    default void onPeerStatus(String peerAddress, String status, BitSet bitfield) {}
    /** Payload bytes received (for speed gauge). */
    default void onBytesDownloaded(long bytes) {}
    /** One-line wire event for the packet console, e.g. "[TCP] REQUEST_PIECE #3 sent to ...". */
    default void onLog(String line) {}
    /** Whole file downloaded, verified and reassembled. */
    default void onDownloadComplete(java.nio.file.Path outputFile) {}
    /** Fatal engine error. */
    default void onError(String message) {}
}
