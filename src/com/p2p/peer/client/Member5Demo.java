package com.p2p.peer.client;

import com.p2p.protocol.*;
import com.p2p.protocol.MessageSerializer.*;
import com.p2p.storage.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Standalone end-to-end demo of the Member 5 download engine.
 * Spins up a MOCK tracker (TCP + UDP) and two MOCK seeders on localhost:
 *   - an honest seeder
 *   - a malicious seeder that corrupts every piece it sends
 * Then downloads a random 3 MB file and proves the result is byte-identical,
 * and that every poisoned piece was rejected by SHA-256 and re-fetched elsewhere.
 *
 * Mocks are for testing only; the real tracker/seeder are Members 1 and 3.
 *
 * Run: java -cp out com.p2p.peer.client.Member5Demo
 */
public class Member5Demo {

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("member5-demo");
        Path source = work.resolve("sample.bin");
        byte[] content = new byte[3 * 1024 * 1024 + 12345];
        new Random(42).nextBytes(content);
        Files.write(source, content);

        PieceStorage seedStore = FileChunker.chunk(source, work.resolve("seed-store"));
        PieceMetadata meta = seedStore.getMetadata();
        System.out.println("Source: " + meta.getFileName() + ", " + meta.pieceCount()
                + " pieces, hash " + meta.getMasterHash().substring(0, 16) + "...");

        // seeders
        MockSeeder honest = new MockSeeder(seedStore, false);
        MockSeeder evil   = new MockSeeder(seedStore, true);
        // tracker
        MockTracker tracker = new MockTracker(List.of(
                new PeerEntry("127.0.0.1", evil.port(), new byte[0]),
                new PeerEntry("127.0.0.1", honest.port(), new byte[0])));

        PieceStorage leechStore = new PieceStorage(work.resolve("leech-store"), meta);
        Path output = work.resolve("downloaded-" + meta.getFileName());
        CountDownLatch done = new CountDownLatch(1);
        int[] rejected = {0};

        DownloadListener listener = new DownloadListener() {
            public void onLog(String l) { System.out.println("  " + l); }
            public void onPieceCompleted(int i, String p, int c, int t) {
                System.out.printf("  [OK] piece #%d from %s (%d/%d)%n", i, p, c, t);
            }
            public void onPieceFailed(int i, String p, String r) { synchronized (rejected) { rejected[0]++; } }
            public void onDownloadComplete(Path f) { done.countDown(); }
            public void onError(String m) { System.err.println("  [FATAL] " + m); done.countDown(); }
        };

        DownloadManager dm = new DownloadManager(leechStore,
                new TrackerClient("127.0.0.1", tracker.tcpPort()), "leecher-1",
                6999, output, listener, 5, tracker.udpPort());
        long t0 = System.currentTimeMillis();
        dm.start();

        boolean finished = done.await(90, TimeUnit.SECONDS);
        dm.stop();
        honest.close(); evil.close(); tracker.close();

        System.out.println();
        boolean match = finished && Files.exists(output)
                && HashVerifier.sha256File(output).equals(HashVerifier.sha256File(source));
        System.out.println("Finished in       : " + (System.currentTimeMillis() - t0) + " ms");
        System.out.println("Poisoned rejected : " + rejected[0]);
        System.out.println("Heartbeats seen   : " + tracker.heartbeats);
        System.out.println("Hashes match      : " + match);
        System.out.println(match ? "DEMO PASSED" : "DEMO FAILED");
        System.exit(match ? 0 : 1);
    }

    // ───────────────────────── mock seeder ─────────────────────────
    static class MockSeeder implements Closeable {
        final ServerSocket server = new ServerSocket(0);
        final PieceStorage store;
        final boolean corrupt;
        final ProtocolHandler ph = new ProtocolHandler();

        MockSeeder(PieceStorage store, boolean corrupt) throws IOException {
            this.store = store; this.corrupt = corrupt;
            Thread t = new Thread(this::acceptLoop, "mock-seeder-" + port());
            t.setDaemon(true); t.start();
        }
        int port() { return server.getLocalPort(); }

        void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    Thread t = new Thread(() -> serve(s)); t.setDaemon(true); t.start();
                } catch (IOException e) { return; }
            }
        }

        void serve(Socket s) {
            String hash = store.getMetadata().getMasterHash();
            try (s) {
                DataInputStream in = ProtocolHandler.wrapInput(s);
                DataOutputStream out = ProtocolHandler.wrapOutput(s);
                ph.readMessage(in);                                        // HANDSHAKE
                ph.sendMessage(out, MessageSerializer.createBitfield(hash, toBytes(store)));
                out.flush();
                while (true) {
                    Message m = ph.readMessage(in);
                    if (m.getType() != MessageType.REQUEST_PIECE) continue;
                    int idx = MessageSerializer.parseRequestPiece(m.getPayload()).pieceIndex;
                    byte[] data = store.readPiece(idx);
                    if (corrupt) data[0] ^= 0x5A;                            // poison!
                    ph.sendMessage(out, MessageSerializer.createPieceData(hash, idx, data));
                    out.flush();
                }
            } catch (Exception ignored) { }
        }

        static byte[] toBytes(PieceStorage st) {
            byte[] b = new byte[(st.pieceCount() + 7) / 8];
            for (int i = 0; i < st.pieceCount(); i++)
                if (st.hasPiece(i)) b[i / 8] |= (byte) (1 << (7 - i % 8));   // MSB-first
            return b;
        }
        public void close() throws IOException { server.close(); }
    }

    // ───────────────────────── mock tracker ─────────────────────────
    static class MockTracker implements Closeable {
        final ServerSocket tcp = new ServerSocket(0);
        final DatagramSocket udp = new DatagramSocket(0);
        final List<PeerEntry> peers;
        final ProtocolHandler ph = new ProtocolHandler();
        volatile int heartbeats;

        MockTracker(List<PeerEntry> peers) throws IOException {
            this.peers = peers;
            start(this::tcpLoop); start(this::udpLoop);
        }
        int tcpPort() { return tcp.getLocalPort(); }
        int udpPort() { return udp.getLocalPort(); }
        static void start(Runnable r) { Thread t = new Thread(r); t.setDaemon(true); t.start(); }

        void tcpLoop() {
            while (!tcp.isClosed()) {
                try (Socket s = tcp.accept()) {
                    DataInputStream in = ProtocolHandler.wrapInput(s);
                    DataOutputStream out = ProtocolHandler.wrapOutput(s);
                    Message m = ph.readMessage(in);
                    Message reply = switch (m.getType()) {
                        case REGISTER_FILE -> MessageSerializer.createRegisterSuccess(
                                MessageSerializer.parseRegisterFile(m.getPayload()).fileHash, "ok");
                        case GET_PEERS -> MessageSerializer.createPeerListResponse(
                                MessageSerializer.parseGetPeers(m.getPayload()), peers);
                        default -> null;
                    };
                    if (reply != null) { ph.sendMessage(out, reply); out.flush(); }
                } catch (Exception e) { if (tcp.isClosed()) return; }
            }
        }

        void udpLoop() {
            byte[] buf = new byte[UdpPacketHelper.MAX_UDP_PACKET_SIZE];
            while (!udp.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    udp.receive(p);
                    UdpPacketHelper.parsePacket(p);
                    heartbeats++;
                    udp.send(UdpPacketHelper.buildHeartbeatAck(p.getAddress(), p.getPort()));
                } catch (Exception e) { if (udp.isClosed()) return; }
            }
        }
        public void close() throws IOException { tcp.close(); udp.close(); }
    }
}
