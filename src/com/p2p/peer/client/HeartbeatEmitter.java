package com.p2p.peer.client;

import com.p2p.protocol.UdpPacketHelper;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends a UDP_HEARTBEAT to the tracker every few seconds so the tracker's pruner
 * (stale after 15 s) keeps this peer in the swarm. UDP is lossy by design:
 * a dropped heartbeat is simply replaced by the next one.
 *
 * @author Member 5 — Download Engine
 */
public class HeartbeatEmitter {

    public static final int INTERVAL_SECONDS = 5;

    private final String peerId;
    private final String fileHash;
    private final int    listenPort;
    private final String trackerHost;
    private final int    trackerUdpPort;
    private final DownloadListener listener;

    private ScheduledExecutorService scheduler;
    private DatagramSocket socket;

    public HeartbeatEmitter(String peerId, String fileHash, int listenPort,
                            String trackerHost, int trackerUdpPort, DownloadListener listener) {
        this.peerId = peerId;
        this.fileHash = fileHash;
        this.listenPort = listenPort;
        this.trackerHost = trackerHost;
        this.trackerUdpPort = trackerUdpPort;
        this.listener = listener;
    }

    public synchronized void start() throws Exception {
        if (scheduler != null) return;
        socket = new DatagramSocket();
        socket.setSoTimeout(1000);
        final InetAddress tracker = InetAddress.getByName(trackerHost);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-emitter");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> beat(tracker), 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void beat(InetAddress tracker) {
        try {
            DatagramPacket p = UdpPacketHelper.buildHeartbeat(
                    peerId, fileHash, listenPort, tracker, trackerUdpPort);
            socket.send(p);
            listener.onLog("[UDP] UDP_HEARTBEAT sent to " + tracker.getHostAddress() + ":" + trackerUdpPort);
            try {
                DatagramPacket ack = new DatagramPacket(new byte[UdpPacketHelper.MAX_UDP_PACKET_SIZE],
                        UdpPacketHelper.MAX_UDP_PACKET_SIZE);
                socket.receive(ack);
                UdpPacketHelper.parsePacket(ack);
                listener.onLog("[UDP] UDP_HEARTBEAT_ACK received");
            } catch (java.net.SocketTimeoutException lost) {
                listener.onLog("[UDP] heartbeat ack timed out (packet lost?)");
            }
        } catch (Exception e) {
            // Never let an exception kill the scheduled task.
            listener.onLog("[UDP] heartbeat error: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        if (socket != null) { socket.close(); socket = null; }
    }
}
