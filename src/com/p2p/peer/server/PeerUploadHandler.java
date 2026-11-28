package com.p2p.peer.server;

import com.p2p.protocol.Message;
import com.p2p.protocol.MessageSerializer;
import com.p2p.protocol.MessageType;
import com.p2p.protocol.ProtocolException;
import com.p2p.protocol.ProtocolHandler;
import com.p2p.storage.PieceStorage;
import com.p2p.storage.HashVerifier;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.BitSet;

/**
 * Handles one incoming leecher connection from HANDSHAKE through PIECE_DATA requests.
 *
 * @author Member 3 - Peer Upload Server
 */
public class PeerUploadHandler implements Runnable {

    public static final int SOCKET_TIMEOUT_MS = 10000;
    public static final int ERROR_INVALID_HANDSHAKE = 400;
    public static final int ERROR_UNKNOWN_MESSAGE = 401;
    public static final int ERROR_INVALID_FILE = 402;
    public static final int ERROR_INVALID_PIECE = 403;
    public static final int ERROR_MISSING_PIECE = 404;
    public static final int ERROR_INTERNAL = 500;

    private final PieceStorage storage;
    private final String peerId;
    private final Socket socket;
    private final UploadListener listener;
    private final ProtocolHandler protocol = new ProtocolHandler();
    private volatile boolean closed;

    public PeerUploadHandler(PieceStorage storage, String peerId, Socket socket) {
        this(storage, peerId, socket, null);
    }

    public PeerUploadHandler(PieceStorage storage, String peerId, Socket socket,
                             UploadListener listener) {
        if (storage == null) {
            throw new IllegalArgumentException("PieceStorage must not be null");
        }
        if (socket == null) {
            throw new IllegalArgumentException("Socket must not be null");
        }
        this.storage = storage;
        this.peerId = peerId == null ? "peer-seeder" : peerId;
        this.socket = socket;
        this.listener = listener != null ? listener : new UploadListener() {};
    }

    @Override
    public void run() {
        String remote = socketAddress();
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            DataInputStream in = ProtocolHandler.wrapInput(socket);
            DataOutputStream out = ProtocolHandler.wrapOutput(socket);

            String fileHash = performHandshake(in, out, remote);
            if (fileHash != null) {
                serveRequests(in, out, fileHash, remote);
            }
        } catch (EOFException e) {
            System.out.println("[UPLOAD] " + remote + " disconnected");
        } catch (SocketTimeoutException e) {
            System.out.println("[UPLOAD] " + remote + " timed out");
        } catch (SocketException e) {
            if (!closed) {
                System.out.println("[UPLOAD] " + remote + " socket closed: "
                        + e.getMessage());
            }
        } catch (ProtocolException e) {
            System.err.println("[UPLOAD] Protocol error from " + remote + ": "
                    + e.getMessage());
        } catch (IOException e) {
            System.err.println("[UPLOAD] I/O error with " + remote + ": "
                    + e.getMessage());
        } catch (RuntimeException e) {
            System.err.println("[UPLOAD] Unexpected error with " + remote + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            listener.onPeerDisconnected(remote);
            close();
        }
    }

    private String performHandshake(DataInputStream in, DataOutputStream out,
                                     String remote)
            throws IOException, ProtocolException {
        Message handshakeMessage;
        try {
            handshakeMessage = protocol.readMessage(in);
        } catch (EOFException e) {
            return null;
        } catch (ProtocolException e) {
            sendError(out, ERROR_INVALID_HANDSHAKE,
                    "Malformed handshake: " + e.getMessage());
            return null;
        }

        if (handshakeMessage.getType() != MessageType.HANDSHAKE) {
            sendError(out, ERROR_INVALID_HANDSHAKE,
                    "No valid HANDSHAKE message received");
            return null;
        }

        MessageSerializer.HandshakeData handshake;
        try {
            handshake =
                    MessageSerializer.parseHandshake(handshakeMessage.getPayload());
        } catch (ProtocolException e) {
            sendError(out, ERROR_INVALID_HANDSHAKE,
                    "Malformed HANDSHAKE payload");
            return null;
        }

        if (!HashVerifier.isValidHash(handshake.fileHash)) {
            sendError(out, ERROR_INVALID_FILE, "Invalid file hash");
            return null;
        }

        String localHash = storage.getMetadata().getMasterHash();
        if (!localHash.equals(handshake.fileHash)) {
            sendError(out, ERROR_INVALID_FILE,
                    "Requested file is not available from this seeder");
            return null;
        }
        if (handshake.peerId == null || handshake.peerId.trim().isEmpty()) {
            sendError(out, ERROR_INVALID_HANDSHAKE, "Peer identity is missing");
            return null;
        }

        // Member 5 expects a HANDSHAKE reply and then a BITFIELD.
        protocol.sendMessage(out,
                MessageSerializer.createHandshake(localHash, peerId));
        out.flush();

        protocol.sendMessage(out,
                MessageSerializer.createBitfield(localHash, toBitfieldBytes()));
        out.flush();

        System.out.println("[UPLOAD] Handshake accepted: " + handshake.peerId
                + " from " + remote);
        listener.onPeerConnected(remote, handshake.peerId);
        listener.onUploadLog("[TCP] HANDSHAKE + BITFIELD sent to " + remote + " (" + handshake.peerId + ")");
        return localHash;
    }

    private void serveRequests(DataInputStream in, DataOutputStream out,
                               String fileHash, String remote)
            throws IOException, ProtocolException {
        while (!closed && !socket.isClosed()) {
            Message message;
            try {
                message = protocol.readMessage(in);
            } catch (EOFException e) {
                break;
            } catch (ProtocolException e) {
                sendError(out, ERROR_UNKNOWN_MESSAGE,
                        "Malformed peer message: " + e.getMessage());
                continue;
            }

            if (message.getType() == MessageType.GET_METADATA) {
                handleGetMetadata(message, out, fileHash, remote);
                continue;
            }

            if (message.getType() != MessageType.REQUEST_PIECE) {
                sendError(out, ERROR_UNKNOWN_MESSAGE,
                        "Only REQUEST_PIECE or GET_METADATA is accepted after handshake");
                continue;
            }

            MessageSerializer.RequestPieceData request;
            try {
                request =
                        MessageSerializer.parseRequestPiece(message.getPayload());
            } catch (ProtocolException e) {
                sendError(out, ERROR_INVALID_PIECE,
                        "Malformed REQUEST_PIECE payload");
                continue;
            }

            if (!HashVerifier.isValidHash(request.fileHash)
                    || !fileHash.equals(request.fileHash)) {
                sendError(out, ERROR_INVALID_FILE,
                        "Requested file hash is invalid or unavailable");
                continue;
            }

            if (request.pieceIndex < 0
                    || request.pieceIndex >= storage.pieceCount()) {
                sendError(out, ERROR_INVALID_PIECE,
                        "Invalid piece index: " + request.pieceIndex);
                continue;
            }

            if (!storage.hasPiece(request.pieceIndex)) {
                sendError(out, ERROR_MISSING_PIECE,
                        "Piece is not available: " + request.pieceIndex);
                continue;
            }

            final byte[] piece;
            try {
                piece = storage.readPiece(request.pieceIndex);
            } catch (IOException e) {
                sendError(out, ERROR_MISSING_PIECE,
                        "Piece could not be read: " + request.pieceIndex);
                continue;
            }

            int expectedLength =
                    storage.getMetadata().pieceLength(request.pieceIndex);
            if (piece == null
                    || piece.length != expectedLength
                    || !HashVerifier.matches(
                            piece,
                            storage.getMetadata().pieceHash(request.pieceIndex))) {
                sendError(out, ERROR_MISSING_PIECE,
                        "Stored piece failed validation: "
                                + request.pieceIndex);
                continue;
            }

            try {
                protocol.sendMessage(out,
                        MessageSerializer.createPieceData(
                                fileHash, request.pieceIndex, piece));
                out.flush();
                System.out.println("[UPLOAD] Sent piece #" + request.pieceIndex
                        + " (" + piece.length + " bytes) to " + remote);
                listener.onBytesUploaded(piece.length);
                listener.onPieceServed(request.pieceIndex, remote, piece.length);
                listener.onUploadLog("[TCP] PIECE_DATA #" + request.pieceIndex
                        + " (" + piece.length + " B) sent to " + remote);
            } catch (IOException e) {
                System.err.println("[UPLOAD] Transmission failed for piece #"
                        + request.pieceIndex + " to " + remote
                        + ": " + e.getMessage());
                break;
            } catch (ProtocolException e) {
                System.err.println("[UPLOAD] Could not frame PIECE_DATA #" 
                        + request.pieceIndex + " for " + remote
                        + ": " + e.getMessage());
                break;
            }
        }
    }

    private void handleGetMetadata(Message message, DataOutputStream out,
                                   String fileHash, String remote)
            throws IOException, ProtocolException {
        String requestedHash;
        try {
            requestedHash = MessageSerializer.parseGetMetadata(message.getPayload());
        } catch (ProtocolException e) {
            sendError(out, ERROR_INVALID_FILE, "Malformed GET_METADATA payload");
            return;
        }
        if (!fileHash.equals(requestedHash)) {
            sendError(out, ERROR_INVALID_FILE, "Requested file hash is unavailable");
            return;
        }
        var meta = storage.getMetadata();
        java.util.List<String> hashes = new java.util.ArrayList<>(meta.pieceCount());
        for (int i = 0; i < meta.pieceCount(); i++) {
            hashes.add(meta.pieceHash(i));
        }
        protocol.sendMessage(out, MessageSerializer.createMetadataResponse(
                meta.getFileName(), meta.getFileSize(), meta.getPieceSize(),
                meta.getMasterHash(), hashes));
        out.flush();
        listener.onUploadLog("[TCP] METADATA_RESPONSE sent to " + remote);
    }

    private byte[] toBitfieldBytes() {
        BitSet have = storage.getBitfield();
        byte[] bitfield = new byte[(storage.pieceCount() + 7) / 8];
        for (int i = 0; i < storage.pieceCount(); i++) {
            if (have.get(i)) {
                bitfield[i / 8] |= (byte) (1 << (7 - (i % 8)));
            }
        }
        return bitfield;
    }

    private void sendError(DataOutputStream out, int code, String message)
            throws IOException, ProtocolException {
        protocol.sendMessage(out,
                MessageSerializer.createError(code, message));
        out.flush();
    }

    private String socketAddress() {
        if (socket.getRemoteSocketAddress() != null) {
            return socket.getRemoteSocketAddress().toString();
        }
        return "unknown-peer";
    }

    private void close() {
        closed = true;
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("[UPLOAD] Failed to close socket: "
                    + e.getMessage());
        }
    }
}
