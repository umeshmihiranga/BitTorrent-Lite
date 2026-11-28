package com.p2p.storage;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Slices a file into 256 KB pieces using FileChannel + ByteBuffer and hashes each one. */
public final class FileChunker {
    public static final int CHUNK_SIZE = 256 * 1024;

    private FileChunker() {}

    /** Pass 1: compute metadata (piece hashes + master hash) in a single read of the file. */
    public static PieceMetadata computeMetadata(Path source) throws IOException {
        List<String> hashes = new ArrayList<>();
        MessageDigest master = HashVerifier.newDigest();
        try (FileChannel ch = FileChannel.open(source, StandardOpenOption.READ)) {
            long size = ch.size();
            ByteBuffer buf = ByteBuffer.allocate(CHUNK_SIZE);
            for (long pos = 0; pos < size; pos += CHUNK_SIZE) {
                buf.clear();
                int expected = (int) Math.min(CHUNK_SIZE, size - pos);
                int n = readFully(ch, buf, pos);
                if (n != expected) throw new EOFException("File changed while reading");
                buf.flip();
                hashes.add(HashVerifier.sha256(buf));
                master.update(buf.duplicate());
            }
            return new PieceMetadata(source.getFileName().toString(), size, CHUNK_SIZE,
                    HashVerifier.toHex(master.digest()), hashes);
        }
    }

    /** Pass 2: write every piece into the storage (seeder side). */
    public static void storePieces(Path source, PieceStorage storage) throws IOException {
        PieceMetadata meta = storage.getMetadata();
        try (FileChannel ch = FileChannel.open(source, StandardOpenOption.READ)) {
            for (int i = 0; i < meta.pieceCount(); i++) {
                ByteBuffer buf = ByteBuffer.allocate(meta.pieceLength(i));
                long pos = (long) i * meta.getPieceSize();          // (long) avoids int overflow
                if (readFully(ch, buf, pos) != buf.capacity()) throw new EOFException("Short read");
                storage.savePieceUnchecked(i, buf.array());
            }
        }
    }

    /** Convenience: chunk a file and return a ready-to-seed storage. */
    public static PieceStorage chunk(Path source, Path storageDir) throws IOException {
        PieceMetadata meta = computeMetadata(source);
        PieceStorage storage = new PieceStorage(storageDir, meta);
        storePieces(source, storage);
        return storage;
    }

    // One read() call may return fewer bytes than asked, so loop until the buffer is full or EOF.
    private static int readFully(FileChannel ch, ByteBuffer buf, long pos) throws IOException {
        int total = 0;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos + total);   // positional read: thread-safe
            if (n < 0) break;
            total += n;
        }
        return total;
    }
}
