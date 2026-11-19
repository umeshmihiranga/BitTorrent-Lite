package com.p2p.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.util.Collections;
import java.util.Random;

/** 2-minute demo for Member 4. Run: java com.p2p.storage.Member4Demo */
public class Member4Demo {

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("member4-demo");
        Path src = work.resolve("sample.bin");
        byte[] data = new byte[1_000_000 + 123];          // not a multiple of 256 KB on purpose
        new Random(42).nextBytes(data);
        Files.write(src, data);

        System.out.println("=== 1. CHUNKING (FileChannel + ByteBuffer) ===");
        PieceStorage seeder = FileChunker.chunk(src, work.resolve("seeder-store"));
        PieceMetadata meta = seeder.getMetadata();
        System.out.println("File size   : " + meta.getFileSize() + " bytes");
        System.out.println("Pieces      : " + meta.pieceCount());
        for (int i = 0; i < meta.pieceCount(); i++)
            System.out.printf("  piece %d  len=%d  sha256=%s...%n", i, meta.pieceLength(i),
                    meta.pieceHash(i).substring(0, 16));
        System.out.println("Master hash : " + meta.getMasterHash());
        Path metaFile = work.resolve("sample.p2pmeta");
        meta.save(metaFile);

        PieceStorage leecher = new PieceStorage(work.resolve("leecher-store"), PieceMetadata.load(metaFile));

        System.out.println("\n=== 2. TAMPER TEST (anti-poisoning) ===");
        byte[] bad = seeder.readPiece(3).clone();
        bad[0] ^= 1;                                       // flip ONE bit
        System.out.println("Tampered piece accepted? " + leecher.verifyAndSavePiece(3, bad));

        System.out.println("\n=== 3. PATH TRAVERSAL TEST ===");
        try {
            new PieceMetadata("x", 10, 10, "../../evil", Collections.singletonList(meta.pieceHash(0)));
            System.out.println("NOT BLOCKED (bug!)");
        } catch (IllegalArgumentException e) {
            System.out.println("Blocked: " + e.getMessage());
        }

        System.out.println("\n=== 4. ZERO-COPY transferTo() over a SocketChannel ===");
        for (int i = 0; i < meta.pieceCount(); i++) {
            byte[] received = zeroCopyOverLoopback(seeder, i);
            boolean ok = leecher.verifyAndSavePiece(i, received);
            System.out.printf("  piece %d: %d bytes received, verified+saved = %b%n", i, received.length, ok);
        }
        System.out.println("Leecher bitfield: " + leecher.getBitfield() + "  complete=" + leecher.isComplete());

        System.out.println("\n=== 5. REASSEMBLY + MASTER HASH ===");
        Path out = work.resolve("downloaded.bin");
        FileReassembler.reassemble(leecher, out);
        System.out.println("Original : " + HashVerifier.sha256File(src));
        System.out.println("Download : " + HashVerifier.sha256File(out));
        System.out.println("Identical: " + HashVerifier.sha256File(src).equals(HashVerifier.sha256File(out)));
    }

    // Real zero-copy: FileChannel.transferTo(...) -> SocketChannel, read by a receiver thread.
    private static byte[] zeroCopyOverLoopback(PieceStorage s, int i) throws Exception {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (SocketChannel c = server.accept()) {
                    ByteBuffer b = ByteBuffer.allocate(8192);
                    while (c.read(b) != -1) { sink.write(b.array(), 0, b.position()); b.clear(); }
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
            reader.start();
            try (SocketChannel client = SocketChannel.open(server.getLocalAddress())) {
                s.streamPieceZeroCopy(i, client);
            }
            reader.join();
            return sink.toByteArray();
        }
    }
}
