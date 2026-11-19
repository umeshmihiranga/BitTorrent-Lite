package com.p2p.protocol;

import java.io.*;
import java.net.Socket;

/**
 * Core TCP framing engine for BitTorrent-Lite wire protocol.
 *
 * <h3>Wire format per TCP message:</h3>
 * <pre>
 *   [4 bytes: payload length (int, big-endian)]
 *   [1 byte:  message type opcode]
 *   [N bytes: payload body]
 * </pre>
 *
 * <h3>Security features:</h3>
 * <ul>
 *   <li><b>MAX_FRAME_SIZE</b> enforcement — prevents RAM-exhaustion DoS</li>
 *   <li>Negative payload length rejection — defends against integer underflow</li>
 *   <li>Opcode whitelist validation — via {@link MessageType#fromByte(byte)}</li>
 * </ul>
 *
 * <p>Uses {@code DataInputStream.readFully()} to solve the TCP "sticky packet"
 * / fragmentation problem: the method blocks until the exact number of bytes
 * have been received, guaranteeing complete message boundaries.</p>
 *
 * @author Member 2 — Wire Protocol, Network Framing &amp; Security
 */
public class ProtocolHandler {

    /**
     * Maximum allowed frame payload size: 2 MB.
     * Accommodates 256 KB piece data plus generous metadata overhead.
     * Any frame exceeding this is rejected as a potential DoS attack.
     */
    public static final int MAX_FRAME_SIZE = 2 * 1024 * 1024; // 2 MB

    /** Header size in bytes: 4 (length) + 1 (opcode). */
    public static final int HEADER_SIZE = 5;

    public ProtocolHandler() {
        // Instantiable for potential future per-connection state (rate limiting, etc.)
    }

    // ═══════════════════════════════════════════════════════════════
    //  SENDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Sends a length-prefixed, opcode-tagged message over a TCP stream.
     *
     * <p>Thread safety: callers sharing the same {@code DataOutputStream}
     * must synchronize externally (e.g. {@code synchronized(out)} block).</p>
     *
     * @param out     the DataOutputStream wrapping the socket's output stream
     * @param message the Message to send
     * @throws IOException       if an I/O error occurs on the stream
     * @throws ProtocolException if the payload exceeds MAX_FRAME_SIZE
     */
    public void sendMessage(DataOutputStream out, Message message)
            throws IOException, ProtocolException {

        byte[] payload = message.getPayloadDirect(); // package-private fast path
        int payloadLength = payload.length;

        // ── Security: outbound size cap ──
        if (payloadLength > MAX_FRAME_SIZE) {
            throw new ProtocolException(
                    "Outgoing payload size " + payloadLength
                    + " bytes exceeds MAX_FRAME_SIZE (" + MAX_FRAME_SIZE + ")");
        }

        // ── Write header ──
        out.writeInt(payloadLength);                   // 4 bytes: payload length
        out.writeByte(message.getType().getOpcode());  // 1 byte:  opcode

        // ── Write payload body ──
        if (payloadLength > 0) {
            out.write(payload);
        }

        out.flush();
    }

    // ═══════════════════════════════════════════════════════════════
    //  RECEIVING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Reads and validates a single framed message from the TCP input stream.
     *
     * <p>Uses {@code readFully()} to block until the exact payload byte count
     * has been received — this completely solves TCP fragmentation and the
     * "sticky packet" problem.</p>
     *
     * @param in the DataInputStream wrapping the socket's input stream
     * @return the parsed and validated Message
     * @throws EOFException      if the remote peer closed the connection
     * @throws IOException       if an I/O error occurs on the stream
     * @throws ProtocolException if any security check fails
     */
    public Message readMessage(DataInputStream in)
            throws IOException, ProtocolException {

        // ── Read 5-byte header ──
        int  payloadLength;
        byte opcodeByte;

        try {
            payloadLength = in.readInt();   // 4 bytes
            opcodeByte    = in.readByte();  // 1 byte
        } catch (EOFException eof) {
            throw new EOFException("Remote peer closed the connection (EOF during header read)");
        }

        // ── Security Check 1: Negative length ──
        if (payloadLength < 0) {
            throw new ProtocolException(
                    "Negative payload length detected: " + payloadLength
                    + " — possible integer underflow attack or stream corruption");
        }

        // ── Security Check 2: Oversized frame ──
        if (payloadLength > MAX_FRAME_SIZE) {
            throw new ProtocolException(
                    "Payload length " + payloadLength
                    + " exceeds MAX_FRAME_SIZE (" + MAX_FRAME_SIZE
                    + ") — potential DoS / RAM-exhaustion attack");
        }

        // ── Security Check 3: Opcode whitelist ──
        MessageType type = MessageType.fromByte(opcodeByte); // throws if unknown

        // ── Read payload body ──
        byte[] payload = new byte[payloadLength];
        if (payloadLength > 0) {
            in.readFully(payload); // blocks until ALL bytes received — solves fragmentation
        }

        return new Message(type, payload);
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONVENIENCE: Wrap raw socket streams
    // ═══════════════════════════════════════════════════════════════

    /**
     * Creates a buffered {@code DataOutputStream} from a socket.
     * Suitable for use with {@link #sendMessage(DataOutputStream, Message)}.
     *
     * @param socket the connected TCP socket
     * @return a DataOutputStream wrapping the socket's output
     * @throws IOException if the socket's output stream cannot be obtained
     */
    public static DataOutputStream wrapOutput(Socket socket) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    /**
     * Creates a buffered {@code DataInputStream} from a socket.
     * Suitable for use with {@link #readMessage(DataInputStream)}.
     *
     * @param socket the connected TCP socket
     * @return a DataInputStream wrapping the socket's input
     * @throws IOException if the socket's input stream cannot be obtained
     */
    public static DataInputStream wrapInput(Socket socket) throws IOException {
        return new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    }
}
