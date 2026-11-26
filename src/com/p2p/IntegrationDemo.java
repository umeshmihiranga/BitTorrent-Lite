package com.p2p;

import com.p2p.peer.client.DownloadListener;
import com.p2p.peer.client.DownloadManager;
import com.p2p.peer.client.SeederService;
import com.p2p.peer.client.TrackerClient;
import com.p2p.peer.server.PeerServer;
import com.p2p.storage.FileChunker;
import com.p2p.storage.HashVerifier;
import com.p2p.storage.PieceMetadata;
import com.p2p.storage.PieceStorage;
import com.p2p.tracker.TrackerServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full end-to-end integration test across all backend modules (NO mocks):
 * Member 1 (TrackerServer) + Member 2 (Protocol) + Member 3 (PeerServer)
 * + Member 4 (FileChunker / PieceStorage / FileReassembler) + Member 5 (DownloadManager / SeederService).
 *
 * <p>Run: {@code java -cp out com.p2p.IntegrationDemo}</p>
 */
public class IntegrationDemo {

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("bittorrent-lite-e2e");
        Path source = work.resolve("test-payload.bin");
        byte[] data = new byte[2 * 1024 * 1024 + 54321];
        new Random(12345).nextBytes(data);
        Files.write(source, data);

        // 1. Start real TrackerServer (Member 1)
        TrackerServer trackerServer = new TrackerServer();
        Thread trackerThread = new Thread(trackerServer::start, "integration-tracker");
        trackerThread.setDaemon(true);
        trackerThread.start();
        Thread.sleep(400);

        // 2. Chunk source file (Member 4)
        PieceStorage seedStorage = FileChunker.chunk(source, work.resolve("seeder-store"));
        Path metaFile = work.resolve("test-payload.p2pmeta");
        seedStorage.getMetadata().save(metaFile);

        // 3. Start real Seeder (Member 3 PeerServer + Member 5 SeederService)
        PeerServer seederServer = PeerServer.forLocalhost(0, seedStorage, "seeder-1", null);
        seederServer.start();
        int seederPort = seederServer.getBoundPort();

        SeederService seederService = new SeederService(
                seedStorage,
                new TrackerClient("127.0.0.1", TrackerServer.TCP_PORT),
                "seeder-1",
                seederPort,
                TrackerServer.UDP_PORT,
                new DownloadListener() {
                    @Override public void onLog(String line) { System.out.println("[SEEDER] " + line); }
                });
        seederService.start();

        // 4. Start real Leecher (Member 4 PieceStorage + Member 5 DownloadManager)
        PieceMetadata meta = PieceMetadata.load(metaFile);
        PieceStorage leechStorage = new PieceStorage(work.resolve("leecher-store"), meta);
        Path downloaded = work.resolve("downloaded.bin");

        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger completedPieces = new AtomicInteger();

        DownloadManager dm = new DownloadManager(
                leechStorage,
                new TrackerClient("127.0.0.1", TrackerServer.TCP_PORT),
                "leecher-1",
                5999,
                downloaded,
                new DownloadListener() {
                    @Override public void onLog(String line) { System.out.println("[LEECHER] " + line); }
                    @Override public void onPieceCompleted(int idx, String peer, int c, int t) {
                        completedPieces.set(c);
                        System.out.printf("[LEECHER] Verified piece #%d (%d/%d) from %s%n", idx, c, t, peer);
                    }
                    @Override public void onDownloadComplete(Path out) { done.countDown(); }
                    @Override public void onError(String msg) {
                        System.err.println("[LEECHER ERROR] " + msg);
                        done.countDown();
                    }
                },
                5,
                TrackerServer.UDP_PORT);

        long t0 = System.currentTimeMillis();
        dm.start();

        boolean finished = done.await(60, TimeUnit.SECONDS);

        // 5. Shutdown cleanly
        dm.stop();
        seederService.stop();
        seederServer.shutdown();
        trackerServer.shutdown();

        boolean hashesMatch = finished
                && Files.exists(downloaded)
                && HashVerifier.sha256File(source).equals(HashVerifier.sha256File(downloaded));

        System.out.println("\n=== INTEGRATION RESULT ===");
        System.out.println("Finished     : " + finished);
        System.out.println("Pieces       : " + completedPieces.get() + "/" + meta.pieceCount());
        System.out.println("Elapsed      : " + (System.currentTimeMillis() - t0) + " ms");
        System.out.println("SHA-256 Match: " + hashesMatch);
        System.out.println(hashesMatch ? "INTEGRATION PASSED" : "INTEGRATION FAILED");
        System.exit(hashesMatch ? 0 : 1);
    }
}
