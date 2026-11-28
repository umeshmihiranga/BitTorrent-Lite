package com.p2p.tracker;

import java.util.Arrays;

/** Registration details for one peer sharing one file. */
public class PeerInfo {

    private final String ipAddress;
    private final int tcpPort;
    private final String peerId;
    private volatile byte[] bitfield;
    private volatile long lastSeen;

    public PeerInfo(String ipAddress, int tcpPort, String peerId, byte[] bitfield) {
        this.ipAddress = ipAddress;
        this.tcpPort = tcpPort;
        this.peerId = peerId;
        this.bitfield = copyBitfield(bitfield);
        this.lastSeen = System.currentTimeMillis();
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public String getPeerId() {
        return peerId;
    }

    /** Returns a defensive copy of the peer's current bitfield. */
    public byte[] getBitfield() {
        return copyBitfield(bitfield);
    }

    /** Replaces the bitfield with a defensive copy of the supplied bytes. */
    public void updateBitfield(byte[] bitfield) {
        this.bitfield = copyBitfield(bitfield);
    }

    public long getLastSeen() {
        return lastSeen;
    }

    /** Records the time at which a heartbeat arrives. */
    public void updateLastSeen() {
        lastSeen = System.currentTimeMillis();
    }

    private static byte[] copyBitfield(byte[] bitfield) {
        return bitfield == null ? null : Arrays.copyOf(bitfield, bitfield.length);
    }
}