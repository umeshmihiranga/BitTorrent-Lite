package com.p2p.protocol;

/**
 * Defines all wire protocol opcodes for BitTorrent-Lite.
 * Each opcode is a unique 1-byte command identifier used in message framing.
 *
 * <p>This enum serves as the opcode <b>WHITELIST</b>. Any byte value not
 * explicitly mapped here is rejected immediately by {@link #fromByte(byte)},
 * preventing protocol confusion and command injection attacks.</p>
 *
 * <h3>Transport Mapping:</h3>
 * <ul>
 *   <li>0x01–0x13: TCP stream-framed messages (file data, peer handshakes)</li>
 *   <li>0x20–0x21: UDP datagram messages (heartbeats, signaling)</li>
 *   <li>0xFF: ERROR — may appear on either transport</li>
 * </ul>
 *
 * @author Member 2 — Wire Protocol, Network Framing &amp; Security
 */
public enum MessageType {

    // ── Peer ↔ Tracker Commands (TCP) ────────────────────────────
    REGISTER_FILE       ((byte) 0x01, "Peer→Tracker: announce a shared file"),
    REGISTER_SUCCESS    ((byte) 0x02, "Tracker→Peer: confirm registration"),
    GET_PEERS           ((byte) 0x03, "Peer→Tracker: query peers for a file"),
    PEER_LIST_RESPONSE  ((byte) 0x04, "Tracker→Peer: list of active peers"),
    UNREGISTER_FILE     ((byte) 0x05, "Peer→Tracker: leave a swarm"),

    // ── Peer ↔ Peer Commands (TCP) ───────────────────────────────
    HANDSHAKE           ((byte) 0x10, "Peer↔Peer: initiate TCP connection"),
    BITFIELD            ((byte) 0x11, "Peer↔Peer: owned-pieces bitmask"),
    REQUEST_PIECE       ((byte) 0x12, "Leecher→Seeder: request a chunk"),
    PIECE_DATA          ((byte) 0x13, "Seeder→Leecher: deliver chunk bytes"),
    GET_METADATA        ((byte) 0x14, "Leecher→Seeder: request .p2pmeta details"),
    METADATA_RESPONSE   ((byte) 0x15, "Seeder→Leecher: deliver .p2pmeta details"),

    // ── UDP Signaling Commands ───────────────────────────────────
    UDP_HEARTBEAT       ((byte) 0x20, "Peer→Tracker (UDP): keep-alive ping"),
    UDP_HEARTBEAT_ACK   ((byte) 0x21, "Tracker→Peer (UDP): heartbeat ack"),

    // ── Utility ──────────────────────────────────────────────────
    ERROR               ((byte) 0xFF, "Error notification frame");

    // ─────────────────────────────────────────────────────────────

    private final byte   opcode;
    private final String description;

    MessageType(byte opcode, String description) {
        this.opcode      = opcode;
        this.description = description;
    }

    /** Returns the raw 1-byte opcode written to the wire. */
    public byte getOpcode() {
        return opcode;
    }

    /** Human-readable description for logging and debugging. */
    public String getDescription() {
        return description;
    }

    /**
     * Checks whether this message type is transported over UDP.
     *
     * @return {@code true} for UDP_HEARTBEAT and UDP_HEARTBEAT_ACK
     */
    public boolean isUdp() {
        return this == UDP_HEARTBEAT || this == UDP_HEARTBEAT_ACK;
    }

    /**
     * Resolves a raw byte opcode to a validated {@code MessageType}.
     * This method IS the opcode whitelist — unknown codes are rejected instantly.
     *
     * @param opcode the raw byte read from the wire
     * @return the matching {@code MessageType}
     * @throws ProtocolException if the opcode is not in the whitelist
     */
    public static MessageType fromByte(byte opcode) throws ProtocolException {
        for (MessageType type : values()) {
            if (type.opcode == opcode) {
                return type;
            }
        }
        throw new ProtocolException(String.format(
                "Unknown opcode 0x%02X rejected by whitelist — possible corruption or attack",
                opcode & 0xFF));
    }

    @Override
    public String toString() {
        return String.format("%s(0x%02X)", name(), opcode & 0xFF);
    }
}
