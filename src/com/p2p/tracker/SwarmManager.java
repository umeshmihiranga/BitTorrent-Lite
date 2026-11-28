package com.p2p.tracker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Maintains the thread-safe registry structure for file swarms. */
public class SwarmManager {

    private static final long PEER_TIMEOUT_MS = 15_000L;

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

    public boolean updateHeartbeat(String fileHash, String ipAddress, int tcpPort) {
        String peerKey = ipAddress + ":" + tcpPort;
        AtomicBoolean updated = new AtomicBoolean(false);
        registry.computeIfPresent(fileHash, (key, peers) -> {
            PeerInfo peerInfo = peers.get(peerKey);
            if (peerInfo != null) {
                peerInfo.updateLastSeen();
                updated.set(true);
            }
            return peers;
        });
        return updated.get();
    }

    public void removeStalePeers() {
        long currentTime = System.currentTimeMillis();
        for (String fileHash : registry.keySet()) {
            registry.computeIfPresent(fileHash, (key, peers) -> {
                peers.entrySet().removeIf(entry ->
                        currentTime - entry.getValue().getLastSeen() > PEER_TIMEOUT_MS);
                return peers.isEmpty() ? null : peers;
            });
        }
    }
}
