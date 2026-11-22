package com.p2p.tracker;

import com.p2p.protocol.MessageType;
import com.p2p.protocol.ProtocolException;
import com.p2p.protocol.UdpPacketHelper;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * Receives peer heartbeats and updates registered peers.
 */
public class HeartbeatListener implements Runnable {

    private final DatagramSocket socket;
    private final SwarmManager swarmManager;

    public HeartbeatListener(DatagramSocket socket, SwarmManager swarmManager) {
        if (socket == null) {
            throw new IllegalArgumentException("DatagramSocket must not be null");
        }
        if (swarmManager == null) {
            throw new IllegalArgumentException("SwarmManager must not be null");
        }
        this.socket = socket;
        this.swarmManager = swarmManager;
    }

    @Override
    public void run() {
        byte[] buffer = new byte[UdpPacketHelper.MAX_UDP_PACKET_SIZE];

        while (!socket.isClosed()) {
            DatagramPacket packet =
                new DatagramPacket(buffer, buffer.length);

            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (socket.isClosed()) {
                    break;
                }
                continue;
            }

            try {
                UdpPacketHelper.UdpMessage message =
                    UdpPacketHelper.parsePacket(packet);

                if (message.getType() != MessageType.UDP_HEARTBEAT) {
                    continue;
                }
                if (!isValidPeerId(message.getPeerId())) {
                    continue;
                }
                if (!isValidFileHash(message.getFileHash())) {
                    continue;
                }
                if (!isValidTcpPort(message.getPeerPort())) {
                    continue;
                }

                String senderIp =
                    packet.getAddress().getHostAddress();
                boolean updated = swarmManager.updateHeartbeat(
                    message.getFileHash(),
                    senderIp,
                    message.getPeerPort()
                );

                if (updated) {
                    DatagramPacket ack = UdpPacketHelper.buildHeartbeatAck(
                        packet.getAddress(),
                        packet.getPort()
                    );
                    socket.send(ack);
                }
            } catch (ProtocolException e) {
                continue;
            } catch (IOException e) {
                if (socket.isClosed()) {
                    break;
                }
            }
        }
    }

    private static boolean isValidPeerId(String peerId) {
        return peerId != null && !peerId.trim().isEmpty();
    }

    private static boolean isValidFileHash(String fileHash) {
        return fileHash != null && !fileHash.trim().isEmpty();
    }

    private static boolean isValidTcpPort(int tcpPort) {
        return tcpPort >= 1 && tcpPort <= 65535;
    }
}
