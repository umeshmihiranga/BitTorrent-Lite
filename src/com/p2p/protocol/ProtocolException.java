package com.p2p.protocol;

/**
 * Checked exception thrown when a wire protocol violation or security breach
 * is detected during message framing, opcode validation, or payload parsing.
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>Negative payload length (corruption / integer underflow attack)</li>
 *   <li>Payload exceeds {@code MAX_FRAME_SIZE} (DoS / RAM-exhaustion)</li>
 *   <li>Unknown opcode not in the whitelist (command injection)</li>
 *   <li>Malformed payload structure during deserialization</li>
 *   <li>UDP datagram exceeds maximum safe size</li>
 * </ul>
 *
 * @author Member 2 — Wire Protocol, Network Framing &amp; Security
 */
public class ProtocolException extends Exception {

    /**
     * Constructs a ProtocolException with the given detail message.
     *
     * @param message description of the violation
     */
    public ProtocolException(String message) {
        super(message);
    }

    /**
     * Constructs a ProtocolException with a message and underlying cause.
     *
     * @param message description of the violation
     * @param cause   the original exception (e.g. a parsing failure)
     */
    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
