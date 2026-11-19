package com.p2p.protocol;

import java.io.*;
import java.net.DatagramPacket;
import java.net.InetAddress;

/**
 * UDP datagram serialization and parsing helper for BitTorrent-Lite signaling.
 *
 * <h3>UDP Datagram Wire Format:</h3>
 * <pre>
 *   [1 byte: Opcode]  [4 bytes: peerPort (int)]  [UTF: peerId]  [UTF: fileHash]
 * </pre>
 *
 * <p>UDP is used exclusively for lightweight, low-overhead signaling:</p>
 * <ul>
 *   <li>{@code UDP_HEARTBEAT (0x20)}: Peer → Tracker keep-alive ping</li>
 *   <li>{@code UDP_HEARTBEAT_ACK (0x21)}: Tracker → Peer acknowledgment</li>
 * </ul>
 *
 * <p>Dropping an occasional heartbeat packet is acceptable — the tracker
 * simply prunes peers whose heartbeats are missing for &gt;15 seconds.</p>
 *
 * @author Member 2 — Wire Protocol, Network Framing &amp; Security
 */
public class UdpPacketHelper {

    /**
     * Maximum safe UDP datagram payload size.
     * Kept well under the typical MTU (~1500 bytes) to avoid IP fragmentation.
     */
    public static final int MAX_UDP_PACKET_SIZE = 1024;

    /** Default UDP port the Tracker listens on for heartbeats. */
    public static final int DEFAULT_TRACKER_UDP_PORT = 8001;

    private UdpPacketHelper() {
        // Utility class — not instantiable
    }

    // ═══════════════════════════════════════════════════════════════
    //  BUILD OUTGOING UDP DATAGRAMS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Builds a UDP_HEARTBEAT datagram to send to the Tracker.
     *
     * @param peerId    this peer's unique identifier string
     * @param fileHash  the file hash for the swarm to stay registered in
     * @param peerPort  the TCP port this peer is listening on for uploads
     * @param trackerAddress the tracker's IP address
     * @param trackerUdpPort the tracker's UDP heartbeat port
     * @return a ready-to-send DatagramPacket
     * @throws IOException       if serialization fails
     * @throws ProtocolException if the resulting packet exceeds MAX_UDP_PACKET_SIZE
     */
    public static DatagramPacket buildHeartbeat(String peerId, String fileHash,
                                                 int peerPort,
                                                 InetAddress trackerAddress,
                                                 int trackerUdpPort)
            throws IOException, ProtocolException {

        byte[] data = serializeUdpPayload(MessageType.UDP_HEARTBEAT, peerPort, peerId, fileHash);
        return new DatagramPacket(data, data.length, trackerAddress, trackerUdpPort);
    }

    /**
     * Builds a UDP_HEARTBEAT_ACK datagram as a reply to a peer's heartbeat.
     *
     * @param peerAddress the peer's IP address (from the received packet)
     * @param peerUdpPort the peer's source UDP port (from the received packet)
     * @return a ready-to-send DatagramPacket
     * @throws IOException       if serialization fails
     * @throws ProtocolException if the resulting packet exceeds MAX_UDP_PACKET_SIZE
     */
    public static DatagramPacket buildHeartbeatAck(InetAddress peerAddress,
                                                    int peerUdpPort)
            throws IOException, ProtocolException {

        byte[] data = serializeUdpPayload(MessageType.UDP_HEARTBEAT_ACK, 0, "", "");
        return new DatagramPacket(data, data.length, peerAddress, peerUdpPort);
    }

    // ═══════════════════════════════════════════════════════════════
    //  PARSE INCOMING UDP DATAGRAMS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parses a received UDP datagram into a structured {@link UdpMessage}.
     *
     * @param packet the received DatagramPacket
     * @return parsed UdpMessage with all extracted fields
     * @throws ProtocolException if the datagram is malformed or the opcode is invalid
     */
    public static UdpMessage parsePacket(DatagramPacket packet) throws ProtocolException {
        byte[] data   = packet.getData();
        int    offset = packet.getOffset();
        int    length = packet.getLength();

        if (length < 1) {
            throw new ProtocolException("UDP datagram too short: " + length + " bytes (minimum 1)");
        }

        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data, offset, length);
            DataInputStream dis = new DataInputStream(bais);

            // 1 byte: opcode
            byte opcodeByte = dis.readByte();
            MessageType type = MessageType.fromByte(opcodeByte); // whitelist check

            if (!type.isUdp()) {
                throw new ProtocolException(
                        "Non-UDP opcode " + type + " received on UDP channel — rejected");
            }

            // 4 bytes: peer TCP port
            int peerPort = dis.readInt();

            // UTF strings: peerId, fileHash
            String peerId   = dis.readUTF();
            String fileHash = dis.readUTF();

            return new UdpMessage(type, peerPort, peerId, fileHash,
                                  packet.getAddress(), packet.getPort());

        } catch (EOFException e) {
            // Heartbeat ACKs may have minimal payload — handle gracefully
            try {
                byte opcodeByte = data[offset];
                MessageType type = MessageType.fromByte(opcodeByte);
                if (type == MessageType.UDP_HEARTBEAT_ACK) {
                    return new UdpMessage(type, 0, "", "",
                                          packet.getAddress(), packet.getPort());
                }
            } catch (ProtocolException ignore) { /* fall through */ }
            throw new ProtocolException("Truncated UDP datagram: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ProtocolException("Failed to parse UDP datagram: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  INTERNAL SERIALIZATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Serializes a UDP datagram payload to bytes.
     * Format: [1-byte Opcode] [4-byte peerPort] [UTF peerId] [UTF fileHash]
     */
    private static byte[] serializeUdpPayload(MessageType type, int peerPort,
                                               String peerId, String fileHash)
            throws IOException, ProtocolException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeByte(type.getOpcode());  // 1 byte
        dos.writeInt(peerPort);           // 4 bytes
        dos.writeUTF(peerId);             // 2 + N bytes
        dos.writeUTF(fileHash);           // 2 + N bytes
        dos.flush();

        byte[] result = baos.toByteArray();
        if (result.length > MAX_UDP_PACKET_SIZE) {
            throw new ProtocolException(
                    "UDP packet size " + result.length
                    + " exceeds MAX_UDP_PACKET_SIZE (" + MAX_UDP_PACKET_SIZE + ")");
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    //  UdpMessage — parsed datagram DTO
    // ═══════════════════════════════════════════════════════════════

    /**
     * Immutable data object holding the parsed contents of a UDP datagram.
     */
    public static class UdpMessage {
        private final MessageType type;
        private final int         peerPort;
        private final String      peerId;
        private final String      fileHash;
        private final InetAddress  senderAddress;
        private final int         senderUdpPort;

        public UdpMessage(MessageType type, int peerPort, String peerId, String fileHash,
                          InetAddress senderAddress, int senderUdpPort) {
            this.type          = type;
            this.peerPort      = peerPort;
            this.peerId        = peerId;
            this.fileHash      = fileHash;
            this.senderAddress = senderAddress;
            this.senderUdpPort = senderUdpPort;
        }

        public MessageType getType()          { return type; }
        public int         getPeerPort()      { return peerPort; }
        public String      getPeerId()        { return peerId; }
        public String      getFileHash()      { return fileHash; }
        public InetAddress getSenderAddress() { return senderAddress; }
        public int         getSenderUdpPort() { return senderUdpPort; }

        @Override
        public String toString() {
            return String.format("UdpMessage{type=%s, peerId='%s', fileHash='%s', peerPort=%d, from=%s:%d}",
                    type, peerId, fileHash, peerPort, senderAddress, senderUdpPort);
        }
    }
}
