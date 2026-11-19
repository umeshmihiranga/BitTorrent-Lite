package com.p2p.protocol;

import java.util.Arrays;

/**
 * Immutable wire-level message container.
 * Wraps a {@link MessageType} (opcode) and its raw binary payload body.
 *
 * <p>Defensive copies are made on construction and retrieval so callers
 * cannot mutate payload bytes after a Message is created.</p>
 *
 * @author Member 2 — Wire Protocol, Network Framing &amp; Security
 */
public final class Message {

    private final MessageType type;
    private final byte[]      payload;

    /**
     * Creates a message with the given type and payload.
     *
     * @param type    the command opcode (non-null)
     * @param payload the raw payload bytes (may be null → treated as empty)
     */
    public Message(MessageType type, byte[] payload) {
        if (type == null) {
            throw new IllegalArgumentException("MessageType must not be null");
        }
        this.type    = type;
        this.payload = (payload != null) ? Arrays.copyOf(payload, payload.length)
                                         : new byte[0];
    }

    /**
     * Creates a message with an empty payload (e.g. UDP_HEARTBEAT_ACK).
     *
     * @param type the command opcode
     */
    public Message(MessageType type) {
        this(type, new byte[0]);
    }

    /** Returns the message opcode / command type. */
    public MessageType getType() {
        return type;
    }

    /** Returns a defensive copy of the raw payload bytes. */
    public byte[] getPayload() {
        return Arrays.copyOf(payload, payload.length);
    }

    /** Returns the payload length in bytes (without header overhead). */
    public int getPayloadLength() {
        return payload.length;
    }

    /**
     * Returns direct reference to the internal payload array.
     * <b>Package-private</b> — only for performance-critical internal use
     * where the caller guarantees it will not mutate the array.
     */
    byte[] getPayloadDirect() {
        return payload;
    }

    @Override
    public String toString() {
        return String.format("Message{type=%s, payloadLen=%d}", type, payload.length);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Message)) return false;
        Message other = (Message) o;
        return this.type == other.type && Arrays.equals(this.payload, other.payload);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + Arrays.hashCode(payload);
    }
}
