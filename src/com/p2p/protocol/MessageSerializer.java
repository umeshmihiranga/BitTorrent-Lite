package com.p2p.protocol;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary payload serializers and deserializers for every BitTorrent-Lite command.
 *
 * <p>Provides clean {@code createXxx()} / {@code parseXxx()} methods so that
 * other members never need to manually manipulate raw byte arrays. Each method
 * pair handles the full round-trip: Java objects → wire bytes → Java objects.</p>
 *
 * <h3>Encoding conventions:</h3>
 * <ul>
 *   <li>All multi-byte integers are <b>big-endian</b> (Java's default DataStream order)</li>
 *   <li>Strings use {@code writeUTF/readUTF} → [2-byte UTF length prefix][UTF-8 bytes]</li>
 *   <li>Raw binary blobs (piece data, bitfields) are written as trailing bytes
 *       with their length derivable from the total payload length</li>
 * </ul>
 *
 * @author Member 2 — Wire Protocol, Network Framing &amp; Security
 */
public class MessageSerializer {

    private MessageSerializer() {
        // Utility class — not instantiable
    }

    // ═══════════════════════════════════════════════════════════════
    //  0x01  REGISTER_FILE  (Peer → Tracker)
    //  Payload: fileName(UTF) + fileHash(UTF) + fileSize(long)
    //         + pieceCount(int) + peerPort(int)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Creates a REGISTER_FILE message announcing a shared file to the Tracker.
     *
     * @param fileName   the human-readable file name (e.g. "lecture.pdf")
     * @param fileHash   the master SHA-256 hash of the entire file
     * @param fileSize   the total file size in bytes
     * @param pieceCount total number of 256 KB pieces
     * @param peerPort   the TCP port this peer is listening on for uploads
     * @return a framed Message ready to send
     * @throws IOException if serialization fails
     */
    public static Message createRegisterFile(String fileName, String fileHash,
                                              long fileSize, int pieceCount,
                                              int peerPort) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(fileName);
        dos.writeUTF(fileHash);
        dos.writeLong(fileSize);
        dos.writeInt(pieceCount);
        dos.writeInt(peerPort);
        dos.flush();
        return new Message(MessageType.REGISTER_FILE, baos.toByteArray());
    }

    /** Parsed data from a REGISTER_FILE message payload. */
    public static class RegisterFileData {
        public final String fileName;
        public final String fileHash;
        public final long   fileSize;
        public final int    pieceCount;
        public final int    peerPort;

        public RegisterFileData(String fileName, String fileHash,
                                long fileSize, int pieceCount, int peerPort) {
            this.fileName   = fileName;
            this.fileHash   = fileHash;
            this.fileSize   = fileSize;
            this.pieceCount = pieceCount;
            this.peerPort   = peerPort;
        }

        @Override
        public String toString() {
            return String.format("RegisterFile{name='%s', hash='%s', size=%d, pieces=%d, port=%d}",
                    fileName, fileHash, fileSize, pieceCount, peerPort);
        }
    }

    /**
     * Parses a REGISTER_FILE payload into structured data.
     *
     * @param payload the raw payload bytes from a Message
     * @return parsed RegisterFileData
     * @throws ProtocolException if the payload is malformed
     */
    public static RegisterFileData parseRegisterFile(byte[] payload) throws ProtocolException {
        try {
            DataInputStream dis = wrapPayload(payload);
            String fileName  = dis.readUTF();
            String fileHash  = dis.readUTF();
            long   fileSize  = dis.readLong();
            int    pieceCount = dis.readInt();
            int    peerPort  = dis.readInt();
            return new RegisterFileData(fileName, fileHash, fileSize, pieceCount, peerPort);
        } catch (IOException e) {
            throw new ProtocolException("Malformed REGISTER_FILE payload: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  0x02  REGISTER_SUCCESS  (Tracker → Peer)
    //  Payload: fileHash(UTF) + statusMessage(UTF)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Creates a REGISTER_SUCCESS confirmation message.
     *
     * @param fileHash      the file hash that was registered
     * @param statusMessage a human-readable confirmation string
     * @return a framed Message ready to send
     * @throws IOException if serialization fails
     */
    public static Message createRegisterSuccess(String fileHash, String statusMessage)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(fileHash);
        dos.writeUTF(statusMessage);
        dos.flush();
        return new Message(MessageType.REGISTER_SUCCESS, baos.toByteArray());
    }

    /** Parsed data from a REGISTER_SUCCESS message payload. */
    public static class RegisterSuccessData {
        public final String fileHash;
        public final String statusMessage;

        public RegisterSuccessData(String fileHash, String statusMessage) {
            this.fileHash      = fileHash;
            this.statusMessage = statusMessage;
        }
    }

    /**
     * Parses a REGISTER_SUCCESS payload.
     */
    public static RegisterSuccessData parseRegisterSuccess(byte[] payload) throws ProtocolException {
        try {
            DataInputStream dis = wrapPayload(payload);
            return new RegisterSuccessData(dis.readUTF(), dis.readUTF());
        } catch (IOException e) {
            throw new ProtocolException("Malformed REGISTER_SUCCESS payload: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  0x03  GET_PEERS  (Peer → Tracker)
    //  Payload: fileHash(UTF)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Creates a GET_PEERS query message.
     *
     * @param fileHash the file hash to find peers for
     * @return a framed Message ready to send
     * @throws IOException if serialization fails
     */
    public static Message createGetPeers(String fileHash) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(fileHash);
        dos.flush();
        return new Message(MessageType.GET_PEERS, baos.toByteArray());
    }

    /**
     * Parses a GET_PEERS payload to extract the file hash.
     *
     * @param payload the raw payload bytes
     * @return the requested file hash string
     * @throws ProtocolException if the payload is malformed
     */
    public static String parseGetPeers(byte[] payload) throws ProtocolException {
        try {
            return wrapPayload(payload).readUTF();
        } catch (IOException e) {
            throw new ProtocolException("Malformed GET_PEERS payload: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  0x04  PEER_LIST_RESPONSE  (Tracker → Peer)
    //  Payload: fileHash(UTF) + peerCount(int)
    //         + [ ip(UTF) + port(int) + bitfieldLen(int) + bitfield(bytes) ] * N
    // ═══════════════════════════════════════════════════════════════

    /** Describes a single peer entry in a PEER_LIST_RESPONSE. */
    public static class PeerEntry {
        public final String ip;
        public final int    port;
        public final byte[] bitfield;

        public PeerEntry(String ip, int port, byte[] bitfield) {
            this.ip       = ip;
            this.port     = port;
            this.bitfield = (bitfield != null) ? bitfield : new byte[0];
        }

        @Override
        public String toString() {
            return String.format("Peer{%s:%d, bitfieldLen=%d}", ip, port, bitfield.length);
        }
    }

    /**
     * Creates a PEER_LIST_RESPONSE message.
     *
     * @param fileHash the file hash this peer list corresponds to
     * @param peers    list of active peer entries
     * @return a framed Message ready to send
     * @throws IOException if serialization fails
     */
    public static Message createPeerListResponse(String fileHash, List<PeerEntry> peers)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(fileHash);
        dos.writeInt(peers.size());
        for (PeerEntry peer : peers) {
            dos.writeUTF(peer.ip);
            dos.writeInt(peer.port);
            dos.writeInt(peer.bitfield.length);
            if (peer.bitfield.length > 0) {
                dos.write(peer.bitfield);
            }
        }
        dos.flush();
        return new Message(MessageType.PEER_LIST_RESPONSE, baos.toByteArray());
    }

    /** Parsed data from a PEER_LIST_RESPONSE message. */
    public static class PeerListResponseData {
        public final String          fileHash;
        public final List<PeerEntry> peers;

        public PeerListResponseData(String fileHash, List<PeerEntry> peers) {
            this.fileHash = fileHash;
            this.peers    = peers;
        }
    }

    /**
     * Parses a PEER_LIST_RESPONSE payload.
     */
    public static PeerListResponseData parsePeerListResponse(byte[] payload)
            throws ProtocolException {
        try {
            DataInputStream dis = wrapPayload(payload);
            String fileHash = dis.readUTF();
            int peerCount   = dis.readInt();
            List<PeerEntry> peers = new ArrayList<>(peerCount);
            for (int i = 0; i < peerCount; i++) {
                String ip       = dis.readUTF();
                int    port     = dis.readInt();
                int    bfLen    = dis.readInt();
                byte[] bitfield = new byte[bfLen];
                if (bfLen > 0) {
                    dis.readFully(bitfield);
                }
                peers.add(new PeerEntry(ip, port, bitfield));
            }
            return new PeerListResponseData(fileHash, peers);
        } catch (IOException e) {
            throw new ProtocolException(
                    "Malformed PEER_LIST_RESPONSE payload: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  0x05  UNREGISTER_FILE  (Peer → Tracker)
    //  Payload: fileHash(UTF) + peerPort(int)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Creates an UNREGISTER_FILE message to leave a swarm.
     *
     * @param fileHash the file hash to unregister from
     * @param peerPort the TCP port of the departing peer
     * @return a framed Message ready to send
     * @throws IOException if serialization fails
     */
    public static Message createUnregisterFile(String fileHash, int peerPort)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(fileHash);
        dos.writeInt(peerPort);
        dos.flush();
        return new Message(MessageType.UNREGISTER_FILE, baos.toByteArray());
    }

    /** Parsed data from an UNREGISTER_FILE message. */
    public static class UnregisterFileData {
        public final String fileHash;
        public final int    peerPort;

        public UnregisterFileData(String fileHash, int peerPort) {
            this.fileHash = fileHash;
            this.peerPort = peerPort;
        }
    }

    /**
     * Parses an UNREGISTER_FILE payload.
     */
    public static UnregisterFileData parseUnregisterFile(byte[] payload) throws ProtocolException {
        try {
            DataInputStream dis = wrapPayload(payload);
            return new UnregisterFileData(dis.readUTF(), dis.readInt());
        } catch (IOException e) {
            throw new ProtocolException("Malformed UNREGISTER_FILE payload: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  0x10  HANDSHAKE  (Peer ↔ Peer, TCP)
    //  Payload: fileHash(UTF) + peerId(UTF)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Creates a HANDSHAKE message to initiate a peer-to-peer TCP connection.
     *
     * @param fileHash the file hash both peers must agree on
     * @param peerId   this peer's unique identifier
     * @return a framed Message ready to send
     * @throws IOException if serialization fails
     */
    public static Message createHandshake(String fileHash, String peerId) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(fileHash);
        dos.writeUTF(peerId);
        dos.flush();
        return new Message(MessageType.HANDSHAKE, baos.toByteArray());
    }

    /** Parsed data from a HANDSHAKE message. */
    public static class HandshakeData {
        public final String fileHash;
        public final String peerId;

        public HandshakeData(String fileHash, String peerId) {
            this.fileHash = fileHash;
            this.peerId   = peerId;
        }
    }

    /**
     * Parses a HANDSHAKE payload.
     */
    public static HandshakeData parseHandshake(byte[] payload) throws ProtocolException {
        try {
            DataInputStream dis = wrapPayload(payload);
            return new HandshakeData(dis.readUTF(), dis.readUTF());
        } catch (IOException e) {
            throw new ProtocolException("Malformed HANDSHAKE payload: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  0x11  BITFIELD  (Peer ↔ Peer)
    //  Payload: fileHash(UTF) + bitfieldLength(int) + bitfield(bytes)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Creates a BITFIELD message sharing the bitmask of owned pieces.
     *
     * @param fileHash the file hash this bitfield refers to
     * @param bitfield the bitmask byte array (1 bit per piece; bit=1 means owned)
     * @return a framed Message ready to send
     * @throws IOException if serialization fails
     */
    public static Message createBitfield(String fileHash, byte[] bitfield) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(fileHash);
        dos.writeInt(bitfield.length);
        dos.write(bitfield);
        dos.flush();
        return new Message(MessageType.BITFIELD, baos.toByteArray());
    }

    /** Parsed data from a BITFIELD message. */
    public static class BitfieldData {
        public final String fileHash;
        public final byte[] bitfield;

        public BitfieldData(String fileHash, byte[] bitfield) {
            this.fileHash = fileHash;
            this.bitfield = bitfield;
        }

        /** Checks whether the given piece index is marked as owned in this bitfield. */
        public boolean hasPiece(int pieceIndex) {
            int byteIndex = pieceIndex / 8;
            int bitIndex  = 7 - (pieceIndex % 8); // MSB-first within each byte
            if (byteIndex >= bitfield.length) return false;
            return ((bitfield[byteIndex] >> bitIndex) & 1) == 1;
        }
    }

    /**
     * Parses a BITFIELD payload.
     */
    public static BitfieldData parseBitfield(byte[] payload) throws ProtocolException {
        try {
            DataInputStream dis = wrapPayload(payload);
            String fileHash = dis.readUTF();
            int    bfLen    = dis.readInt();
            byte[] bitfield = new byte[bfLen];
            if (bfLen > 0) {
                dis.readFully(bitfield);
            }
            return new BitfieldData(fileHash, bitfield);
        } catch (IOException e) {
            throw new ProtocolException("Malformed BITFIELD payload: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  0x12  REQUEST_PIECE  (Leecher → Seeder)
    //  Payload: fileHash(UTF) + pieceIndex(int)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Creates a REQUEST_PIECE message.
     *
     * @param fileHash   the file hash identifying the shared file
     * @param pieceIndex the zero-based index of the requested piece
     * @return a framed Message ready to send
     * @throws IOException if serialization fails
     */
    public static Message createRequestPiece(String fileHash, int pieceIndex) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(fileHash);
        dos.writeInt(pieceIndex);
        dos.flush();
        return new Message(MessageType.REQUEST_PIECE, baos.toByteArray());
    }

    /** Parsed data from a REQUEST_PIECE message. */
    public static class RequestPieceData {
        public final String fileHash;
        public final int    pieceIndex;

        public RequestPieceData(String fileHash, int pieceIndex) {
            this.fileHash   = fileHash;
            this.pieceIndex = pieceIndex;
        }
    }

    /**
     * Parses a REQUEST_PIECE payload.
     */
    public static RequestPieceData parseRequestPiece(byte[] payload) throws ProtocolException {
        try {
            DataInputStream dis = wrapPayload(payload);
            return new RequestPieceData(dis.readUTF(), dis.readInt());
        } catch (IOException e) {
            throw new ProtocolException("Malformed REQUEST_PIECE payload: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  0x13  PIECE_DATA  (Seeder → Leecher)
    //  Payload: fileHash(UTF) + pieceIndex(int) + data(remaining bytes)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Creates a PIECE_DATA message containing the raw binary chunk.
     *
     * @param fileHash   the file hash identifying the shared file
     * @param pieceIndex the zero-based index of this piece
     * @param data       the raw binary chunk bytes (up to 256 KB)
     * @return a framed Message ready to send
     * @throws IOException if serialization fails
     */
    public static Message createPieceData(String fileHash, int pieceIndex, byte[] data)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(fileHash);
        dos.writeInt(pieceIndex);
        dos.write(data);
        dos.flush();
        return new Message(MessageType.PIECE_DATA, baos.toByteArray());
    }

    /** Parsed data from a PIECE_DATA message. */
    public static class PieceDataContent {
        public final String fileHash;
        public final int    pieceIndex;
        public final byte[] data;

        public PieceDataContent(String fileHash, int pieceIndex, byte[] data) {
            this.fileHash   = fileHash;
            this.pieceIndex = pieceIndex;
            this.data       = data;
        }
    }

    /**
     * Parses a PIECE_DATA payload.
     */
    public static PieceDataContent parsePieceData(byte[] payload) throws ProtocolException {
        try {
            DataInputStream dis = wrapPayload(payload);
            String fileHash  = dis.readUTF();
            int    pieceIndex = dis.readInt();

            // Remaining bytes = raw chunk data
            int headerBytes = getUtfByteCount(fileHash) + 4; // UTF overhead + int
            int dataLen     = payload.length - headerBytes;
            byte[] data     = new byte[dataLen];
            if (dataLen > 0) {
                dis.readFully(data);
            }
            return new PieceDataContent(fileHash, pieceIndex, data);
        } catch (IOException e) {
            throw new ProtocolException("Malformed PIECE_DATA payload: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  0x14  GET_METADATA  (Leecher → Seeder)
    //  Payload: fileHash(UTF)
    // ═══════════════════════════════════════════════════════════════

    public static Message createGetMetadata(String fileHash) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(fileHash);
        dos.flush();
        return new Message(MessageType.GET_METADATA, baos.toByteArray());
    }

    public static String parseGetMetadata(byte[] payload) throws ProtocolException {
        try {
            return wrapPayload(payload).readUTF();
        } catch (IOException e) {
            throw new ProtocolException("Malformed GET_METADATA payload: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  0x15  METADATA_RESPONSE  (Seeder → Leecher)
    //  Payload: fileName(UTF) + fileSize(long) + pieceSize(int)
    //           + masterHash(UTF) + pieceCount(int) + [pieceHash(UTF)...]
    // ═══════════════════════════════════════════════════════════════

    public static Message createMetadataResponse(String fileName, long fileSize, int pieceSize,
                                                 String masterHash, List<String> pieceHashes)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(fileName);
        dos.writeLong(fileSize);
        dos.writeInt(pieceSize);
        dos.writeUTF(masterHash);
        dos.writeInt(pieceHashes.size());
        for (String h : pieceHashes) {
            dos.writeUTF(h);
        }
        dos.flush();
        return new Message(MessageType.METADATA_RESPONSE, baos.toByteArray());
    }

    public static class MetadataContent {
        public final String fileName;
        public final long fileSize;
        public final int pieceSize;
        public final String masterHash;
        public final List<String> pieceHashes;

        public MetadataContent(String fileName, long fileSize, int pieceSize,
                               String masterHash, List<String> pieceHashes) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.pieceSize = pieceSize;
            this.masterHash = masterHash;
            this.pieceHashes = pieceHashes;
        }
    }

    public static MetadataContent parseMetadataResponse(byte[] payload) throws ProtocolException {
        try {
            DataInputStream dis = wrapPayload(payload);
            String fileName   = dis.readUTF();
            long   fileSize   = dis.readLong();
            int    pieceSize  = dis.readInt();
            String masterHash = dis.readUTF();
            int    count      = dis.readInt();
            if (count < 0 || count > 100_000) {
                throw new ProtocolException("Invalid piece count in METADATA_RESPONSE: " + count);
            }
            List<String> hashes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                hashes.add(dis.readUTF());
            }
            return new MetadataContent(fileName, fileSize, pieceSize, masterHash, hashes);
        } catch (IOException e) {
            throw new ProtocolException("Malformed METADATA_RESPONSE payload: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  0xFF  ERROR
    //  Payload: errorCode(int) + errorMessage(UTF)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Creates an ERROR notification message.
     *
     * @param errorCode    application-defined error code
     * @param errorMessage human-readable error description
     * @return a framed Message ready to send
     * @throws IOException if serialization fails
     */
    public static Message createError(int errorCode, String errorMessage) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(errorCode);
        dos.writeUTF(errorMessage);
        dos.flush();
        return new Message(MessageType.ERROR, baos.toByteArray());
    }

    /** Parsed data from an ERROR message. */
    public static class ErrorData {
        public final int    errorCode;
        public final String errorMessage;

        public ErrorData(int errorCode, String errorMessage) {
            this.errorCode    = errorCode;
            this.errorMessage = errorMessage;
        }

        @Override
        public String toString() {
            return String.format("Error{code=%d, msg='%s'}", errorCode, errorMessage);
        }
    }

    /**
     * Parses an ERROR payload.
     */
    public static ErrorData parseError(byte[] payload) throws ProtocolException {
        try {
            DataInputStream dis = wrapPayload(payload);
            return new ErrorData(dis.readInt(), dis.readUTF());
        } catch (IOException e) {
            throw new ProtocolException("Malformed ERROR payload: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════════

    /** Wraps a raw byte array into a DataInputStream for convenient reading. */
    private static DataInputStream wrapPayload(byte[] payload) {
        return new DataInputStream(new ByteArrayInputStream(payload));
    }

    /**
     * Calculates the byte count of a UTF string as written by DataOutputStream.writeUTF().
     * Format: 2-byte length prefix + modified UTF-8 encoded bytes.
     */
    private static int getUtfByteCount(String s) {
        int utfLen = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0001 && c <= 0x007F) {
                utfLen += 1;
            } else if (c <= 0x07FF) {
                utfLen += 2;
            } else {
                utfLen += 3;
            }
        }
        return 2 + utfLen; // 2-byte length prefix + encoded bytes
    }
}
