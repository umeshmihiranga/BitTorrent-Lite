package com.p2p.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.*;
import java.util.BitSet;

/**
 * Disk storage for the pieces of ONE file (Member 4 core class).
 * Thread-safe: used concurrently by Member 3 (uploads) and Member 5 (downloads).
 *
 * Used by others:
 *   Member 5 : verifyAndSavePiece(index, data)
 *   Member 3 : hasPiece, getBitfield, readPiece, streamPieceZeroCopy
 *   Member 6 : getBitfield, completedCount, pieceCount, isComplete
 */
public final class PieceStorage {
    private final PieceMetadata meta;
    private final Path fileDir;
    private final BitSet have;

    public PieceStorage(Path baseDir, PieceMetadata meta) throws IOException {
        this.meta = meta;
        // masterHash is validated as 64 hex chars -> no path traversal possible
        this.fileDir = baseDir.resolve(meta.getMasterHash());
        Files.createDirectories(fileDir);
        this.have = new BitSet(meta.pieceCount());
        for (int i = 0; i < meta.pieceCount(); i++) {            // resume support
            Path p = pieceFile(i);
            if (Files.isRegularFile(p) && Files.size(p) == meta.pieceLength(i)) have.set(i);
        }
    }

    public PieceMetadata getMetadata() { return meta; }
    public int pieceCount() { return meta.pieceCount(); }

    Path pieceFile(int index) {
        checkIndex(index);
        return fileDir.resolve("piece_" + index + ".bin");
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= meta.pieceCount())
            throw new IllegalArgumentException("Invalid piece index: " + index);
    }

    // ---------- state queries ----------
    public boolean hasPiece(int index) {
        if (index < 0 || index >= meta.pieceCount()) return false;
        synchronized (have) { return have.get(index); }
    }

    /** Copy of the bitfield (safe to send in BITFIELD message / draw in GUI). */
    public BitSet getBitfield() {
        synchronized (have) { return (BitSet) have.clone(); }
    }

    public int completedCount() {
        synchronized (have) { return have.cardinality(); }
    }

    public boolean isComplete() {
        return completedCount() == meta.pieceCount();
    }

    // ---------- writing ----------
    /** Member 5 entry point: verify SHA-256 FIRST, then write. Returns false if rejected. */
    public boolean verifyAndSavePiece(int index, byte[] data) throws IOException {
        checkIndex(index);
        if (data == null || data.length != meta.pieceLength(index)
                || !HashVerifier.matches(data, meta.pieceHash(index))) {
            System.err.println("[SECURITY] Hash/size mismatch for piece #" + index + " - rejected");
            return false;
        }
        savePiece(index, data);
        return true;
    }

    /** Trusted write (used only by FileChunker for the local seeder). */
    void savePieceUnchecked(int index, byte[] data) throws IOException {
        savePiece(index, data);
    }

    // Write to a temp file, then atomically rename: a half-written piece is never visible.
    private void savePiece(int index, byte[] data) throws IOException {
        Path tmp = Files.createTempFile(fileDir, "piece_" + index + "_", ".tmp");
        try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.hasRemaining()) out.write(buf);
            out.force(false);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, pieceFile(index),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        synchronized (have) { have.set(index); }
    }

    // ---------- reading ----------
    /** NIO read into a byte[] (use if Member 3 uses blocking Socket + ProtocolHandler). */
    public byte[] readPiece(int index) throws IOException {
        if (!hasPiece(index)) throw new IOException("Piece not available: " + index);
        try (FileChannel in = FileChannel.open(pieceFile(index), StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate((int) in.size());
            while (buf.hasRemaining()) {
                if (in.read(buf) < 0) throw new java.io.EOFException("Piece file truncated");
            }
            return buf.array();
        }
    }

    /**
     * ZERO-COPY: disk -> network with FileChannel.transferTo().
     * Works only when `target` is a channel (e.g. SocketChannel).
     * NOTE: writes RAW piece bytes only. Member 3 must first write the 5-byte frame header
     * ([4-byte length][1-byte opcode = 0x13]) to the same channel; use pieceLength(index) for the length.
     */
    public long streamPieceZeroCopy(int index, WritableByteChannel target) throws IOException {
        if (!hasPiece(index)) throw new IOException("Piece not available: " + index);
        try (FileChannel in = FileChannel.open(pieceFile(index), StandardOpenOption.READ)) {
            long size = in.size(), pos = 0;
            while (pos < size) {
                pos += in.transferTo(pos, size - pos, target); // may send fewer bytes -> loop
            }
            return size;
        }
    }
}