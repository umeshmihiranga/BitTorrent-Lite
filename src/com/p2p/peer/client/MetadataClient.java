package com.p2p.peer.client;

import com.p2p.protocol.Message;
import com.p2p.protocol.MessageSerializer;
import com.p2p.protocol.MessageSerializer.MetadataContent;
import com.p2p.protocol.MessageSerializer.PeerEntry;
import com.p2p.protocol.MessageType;
import com.p2p.protocol.ProtocolException;
import com.p2p.protocol.ProtocolHandler;
import com.p2p.storage.PieceMetadata;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Fetches {@link PieceMetadata} directly from an active Seeder in the swarm
 * using only the 64-character {@code fileHash} (magnet-style metadata exchange).
 */
public final class MetadataClient {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS    = 8000;

    private MetadataClient() {}

    /**
     * Queries the tracker for peers holding {@code fileHash}, connects to the first
     * reachable peer, and requests its metadata over TCP.
     */
    public static PieceMetadata fetchFromSwarm(TrackerClient tracker, String fileHash,
                                             String myPeerId, DownloadListener listener)
            throws IOException, ProtocolException {
        List<PeerEntry> peers = tracker.getPeers(fileHash);
        if (peers.isEmpty()) {
            throw new IOException("No active seeders found in tracker for hash: " + fileHash);
        }

        ProtocolHandler protocol = new ProtocolHandler();
        IOException lastError = null;

        for (PeerEntry peer : peers) {
            try (Socket s = connectWithFallback(peer.ip, peer.port)) {
                s.setSoTimeout(READ_TIMEOUT_MS);
                DataOutputStream out = ProtocolHandler.wrapOutput(s);
                DataInputStream in = ProtocolHandler.wrapInput(s);

                protocol.sendMessage(out, MessageSerializer.createHandshake(fileHash, myPeerId));
                out.flush();

                // Read HANDSHAKE + BITFIELD
                boolean gotBitfield = false;
                for (int i = 0; i < 4 && !gotBitfield; i++) {
                    Message m = protocol.readMessage(in);
                    if (m.getType() == MessageType.BITFIELD) {
                        gotBitfield = true;
                    } else if (m.getType() == MessageType.ERROR) {
                        throw new ProtocolException(MessageSerializer.parseError(m.getPayload()).errorMessage);
                    }
                }

                protocol.sendMessage(out, MessageSerializer.createGetMetadata(fileHash));
                out.flush();

                Message reply = protocol.readMessage(in);
                if (reply.getType() == MessageType.ERROR) {
                    throw new ProtocolException(MessageSerializer.parseError(reply.getPayload()).errorMessage);
                }
                if (reply.getType() != MessageType.METADATA_RESPONSE) {
                    throw new ProtocolException("Unexpected reply to GET_METADATA: " + reply.getType());
                }

                MetadataContent mc = MessageSerializer.parseMetadataResponse(reply.getPayload());
                if (!fileHash.equals(mc.masterHash)) {
                    throw new ProtocolException("Metadata masterHash mismatch from peer");
                }
                if (listener != null) {
                    listener.onLog("[TCP] METADATA_RESPONSE received from " + peer.ip + ":" + peer.port
                            + " (" + mc.fileName + ", " + mc.pieceHashes.size() + " pieces)");
                }
                return new PieceMetadata(mc.fileName, mc.fileSize, mc.pieceSize,
                        mc.masterHash, mc.pieceHashes);
            } catch (IOException | ProtocolException e) {
                lastError = (e instanceof IOException) ? (IOException) e : new IOException(e.getMessage(), e);
            }
        }

        throw new IOException("Could not fetch metadata from any peer: "
                + (lastError != null ? lastError.getMessage() : "unknown error"), lastError);
    }

    static Socket connectWithFallback(String host, int port) throws IOException {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return s;
        } catch (IOException primary) {
            if ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)) {
                throw primary;
            }
            Socket local = new Socket();
            try {
                local.connect(new InetSocketAddress("127.0.0.1", port), 1500);
                return local;
            } catch (IOException fallback) {
                try { local.close(); } catch (IOException ignored) {}
                throw primary;
            }
        }
    }
}
