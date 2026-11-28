package com.p2p.peer.server;

/**
 * Callback interface between the peer upload server (Member 3) and the GUI (Member 6).
 * Callbacks may arrive on any upload worker thread — GUI code must marshal updates
 * onto the UI thread (e.g. {@code SwingUtilities.invokeLater}).
 */
public interface UploadListener {

    /** A remote leecher completed handshake and connected. */
    default void onPeerConnected(String peerAddress, String remotePeerId) {}

    /** A remote leecher disconnected or timed out. */
    default void onPeerDisconnected(String peerAddress) {}

    /** A piece was successfully sent to a leecher. */
    default void onPieceServed(int pieceIndex, String peerAddress, int bytes) {}

    /** Raw piece payload bytes sent (for upload speed gauge). */
    default void onBytesUploaded(long bytes) {}

    /** One-line wire event for the packet console. */
    default void onUploadLog(String line) {}
}
