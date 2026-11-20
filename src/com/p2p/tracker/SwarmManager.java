package com.p2p.tracker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Maintains the thread-safe registry structure for file swarms. */
public class SwarmManager {

    // Outer key: fileHash. Inner key: peerKey (ipAddress + ":" + tcpPort).
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, PeerInfo>> registry;

    public SwarmManager() {
        this.registry = new ConcurrentHashMap<>();
    }

    public void registerPeer(String fileHash, PeerInfo peerInfo) {
        String peerKey = peerInfo.getIpAddress() + ":" + peerInfo.getTcpPort();
        registry.compute(fileHash, (key, peers) -> {
            if (peers == null) {
                peers = new ConcurrentHashMap<>();
            }
            peers.put(peerKey, peerInfo);
            return peers;
        });
    }

    public List<PeerInfo> getPeers(String fileHash) {
        ConcurrentHashMap<String, PeerInfo> peers = registry.get(fileHash);
        if (peers == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(peers.values());
    }

    public void unregisterPeer(String fileHash, String ipAddress, int tcpPort) {
        String peerKey = ipAddress + ":" + tcpPort;
        registry.computeIfPresent(fileHash, (key, peers) -> {
            peers.remove(peerKey);
            return peers.isEmpty() ? null : peers;
        });
    }
}
