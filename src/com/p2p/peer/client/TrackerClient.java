package com.p2p.peer.client;

import com.p2p.protocol.*;
import com.p2p.protocol.MessageSerializer.PeerEntry;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Talks to the Tracker over TCP (port 8000). Each call uses a short-lived connection,
 * so the client is stateless and thread-safe.
 *
 * @author Member 5 — Download Engine
 */
public class TrackerClient {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS    = 5000;

    private final String trackerHost;
    private final int    trackerPort;
    private final ProtocolHandler protocol = new ProtocolHandler();

    public TrackerClient(String trackerHost, int trackerPort) {
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
    }

    public String getTrackerHost() { return trackerHost; }

    /** Announces this peer as a member of the file's swarm. */
    public void registerFile(String fileName, String fileHash, long fileSize,
                             int pieceCount, int listenPort)
            throws IOException, ProtocolException {
        Message reply = exchange(MessageSerializer.createRegisterFile(
                fileName, fileHash, fileSize, pieceCount, listenPort), true);
        if (reply.getType() == MessageType.ERROR) {
            throw new ProtocolException("Tracker rejected registration: "
                    + MessageSerializer.parseError(reply.getPayload()));
        }
        if (reply.getType() != MessageType.REGISTER_SUCCESS) {
            throw new ProtocolException("Unexpected reply to REGISTER_FILE: " + reply.getType());
        }
    }

    /** Asks the tracker which peers hold the given file. */
    public List<PeerEntry> getPeers(String fileHash) throws IOException, ProtocolException {
        Message reply = exchange(MessageSerializer.createGetPeers(fileHash), true);
        if (reply.getType() == MessageType.ERROR) {
            throw new ProtocolException("Tracker error: "
                    + MessageSerializer.parseError(reply.getPayload()));
        }
        if (reply.getType() != MessageType.PEER_LIST_RESPONSE) {
            throw new ProtocolException("Unexpected reply to GET_PEERS: " + reply.getType());
        }
        return MessageSerializer.parsePeerListResponse(reply.getPayload()).peers;
    }

    /** Gracefully leaves the swarm (best effort, no reply expected). */
    public void unregisterFile(String fileHash, int listenPort) throws IOException, ProtocolException {
        exchange(MessageSerializer.createUnregisterFile(fileHash, listenPort), false);
    }

    private Message exchange(Message request, boolean expectReply)
            throws IOException, ProtocolException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(trackerHost, trackerPort), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            DataOutputStream out = ProtocolHandler.wrapOutput(s);
            DataInputStream  in  = ProtocolHandler.wrapInput(s);
            protocol.sendMessage(out, request);
            out.flush();
            return expectReply ? protocol.readMessage(in) : null;
        }
    }
}
