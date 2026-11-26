package com.p2p.peer.client;

import com.p2p.protocol.*;
import com.p2p.protocol.MessageSerializer.*;
import com.p2p.storage.PieceStorage;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.BitSet;

/**
 * One worker = one TCP connection to one seeder. After HANDSHAKE and BITFIELD it
 * repeatedly asks {@link DownloadManager} for a piece that this peer owns, requests it,
 * verifies it, and stores it. Any failure releases the piece back to the queue so another
 * peer can fetch it, then ends this worker (the manager puts the peer on cooldown).
 *
 * @author Member 5 — Download Engine
 */
class PieceDownloadWorker implements Runnable {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS    = 10000;
    private static final int MAX_SKIPPED_MSGS   = 8;

    private final DownloadManager manager;
    private final PieceStorage storage;
    private final String fileHash;
    private final String myPeerId;
    private final String host;
    private final int port;
    private final DownloadListener listener;
    private final ProtocolHandler protocol = new ProtocolHandler();
    private volatile Socket socket;

    PieceDownloadWorker(DownloadManager manager, PieceStorage storage, String fileHash,
                        String myPeerId, String host, int port, DownloadListener listener) {
        this.manager = manager;
        this.storage = storage;
        this.fileHash = fileHash;
        this.myPeerId = myPeerId;
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    String address() { return host + ":" + port; }

    /** Interrupts blocking socket I/O (used by DownloadManager.stop()). */
    void abort() {
        Socket s = socket;
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }

    @Override
    public void run() {
        String addr = address();
        BitSet remote = null;
        int current = -1;
        boolean ok = true;
        try (Socket s = MetadataClient.connectWithFallback(host, port)) {
            socket = s;
            listener.onPeerStatus(addr, "CONNECTING", null);
            s.setSoTimeout(READ_TIMEOUT_MS);
            DataOutputStream out = ProtocolHandler.wrapOutput(s);
            DataInputStream  in  = ProtocolHandler.wrapInput(s);

            // 1. HANDSHAKE
            protocol.sendMessage(out, MessageSerializer.createHandshake(fileHash, myPeerId));
            out.flush();
            listener.onLog("[TCP] HANDSHAKE sent to " + addr);

            // 2. Wait for the seeder's BITFIELD (a HANDSHAKE reply may come first)
            BitfieldData bf = null;
            for (int i = 0; i < MAX_SKIPPED_MSGS && bf == null; i++) {
                Message m = protocol.readMessage(in);
                switch (m.getType()) {
                    case HANDSHAKE:
                        HandshakeData hs = MessageSerializer.parseHandshake(m.getPayload());
                        if (!fileHash.equals(hs.fileHash))
                            throw new ProtocolException("Peer is sharing a different file");
                        listener.onLog("[TCP] HANDSHAKE received from " + addr);
                        break;
                    case BITFIELD:
                        bf = MessageSerializer.parseBitfield(m.getPayload());
                        break;
                    case ERROR:
                        throw new ProtocolException("Peer error: "
                                + MessageSerializer.parseError(m.getPayload()));
                    default:
                        throw new ProtocolException("Unexpected " + m.getType() + " during handshake");
                }
            }
            if (bf == null) throw new ProtocolException("No BITFIELD received");
            if (!fileHash.equals(bf.fileHash)) throw new ProtocolException("BITFIELD for wrong file");

            remote = new BitSet(storage.pieceCount());
            for (int i = 0; i < storage.pieceCount(); i++) if (bf.hasPiece(i)) remote.set(i);
            manager.addAvailability(remote);
            listener.onLog("[TCP] BITFIELD received from " + addr + " (" + remote.cardinality()
                    + "/" + storage.pieceCount() + " pieces)");
            listener.onPeerStatus(addr, remote.cardinality() == storage.pieceCount() ? "SEEDING" : "LEECHING", remote);

            // 3. Piece loop
            while (!Thread.currentThread().isInterrupted() && !manager.isFinished()) {
                current = manager.claimPiece(remote);
                if (current < 0) {
                    if (manager.nothingLeftToClaim()) break;
                    Thread.sleep(300);          // others are in flight; wait for re-queues
                    continue;
                }
                protocol.sendMessage(out, MessageSerializer.createRequestPiece(fileHash, current));
                out.flush();
                listener.onLog("[TCP] REQUEST_PIECE #" + current + " sent to " + addr);

                PieceDataContent piece = awaitPieceData(in, current);
                if (!storage.verifyAndSavePiece(current, piece.data)) {
                    throw new ProtocolException("Piece #" + current + " failed SHA-256 check (poisoned?)");
                }
                listener.onBytesDownloaded(piece.data.length);
                manager.pieceDone(current, addr);
                current = -1;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            ok = false;
        } catch (Exception e) {
            ok = false;
            if (!manager.isStopped()) {
                listener.onLog("[ERR] " + addr + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (current >= 0) listener.onPieceFailed(current, addr, e.getMessage());
            }
        } finally {
            if (current >= 0) manager.releasePiece(current);   // re-queue for another peer
            if (remote != null) manager.removeAvailability(remote);
            listener.onPeerStatus(addr, ok ? "DISCONNECTED" : "FAILED", null);
            manager.workerFinished(addr, ok);
        }
    }

    private PieceDataContent awaitPieceData(DataInputStream in, int expectedIndex) throws Exception {
        for (int i = 0; i < MAX_SKIPPED_MSGS; i++) {
            Message m = protocol.readMessage(in);
            if (m.getType() == MessageType.PIECE_DATA) {
                PieceDataContent pc = MessageSerializer.parsePieceData(m.getPayload());
                if (!fileHash.equals(pc.fileHash) || pc.pieceIndex != expectedIndex)
                    throw new ProtocolException("Got piece #" + pc.pieceIndex + " but requested #" + expectedIndex);
                listener.onLog("[TCP] PIECE_DATA #" + expectedIndex + " received from " + address()
                        + " (" + pc.data.length + " bytes)");
                return pc;
            }
            if (m.getType() == MessageType.ERROR)
                throw new ProtocolException("Peer error: " + MessageSerializer.parseError(m.getPayload()));
            // ignore stray BITFIELD etc.
        }
        throw new ProtocolException("No PIECE_DATA received");
    }
}
