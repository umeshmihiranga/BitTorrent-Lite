package com.p2p.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Stitches verified pieces into the final file, then verifies the MASTER hash. */
public final class FileReassembler {
    private FileReassembler() {}

    public static void reassemble(PieceStorage storage, Path output) throws IOException {
        if (!storage.isComplete())
            throw new IllegalStateException("Missing pieces: " + storage.completedCount()
                    + "/" + storage.pieceCount());
        PieceMetadata meta = storage.getMetadata();
        Path tmp = output.resolveSibling(output.getFileName() + ".part");

        try (FileChannel dest = FileChannel.open(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long pos = 0;
            for (int i = 0; i < meta.pieceCount(); i++) {
                try (FileChannel src = FileChannel.open(storage.pieceFile(i), StandardOpenOption.READ)) {
                    long len = src.size(), done = 0;
                    while (done < len) {
                        long n = dest.transferFrom(src, pos + done, len - done); // zero-copy
                        if (n <= 0) throw new IOException("transferFrom made no progress");
                        done += n;
                    }
                    pos += len;
                }
            }
        }

        if (Files.size(tmp) != meta.getFileSize()
                || !HashVerifier.sha256File(tmp).equals(meta.getMasterHash())) {
            Files.deleteIfExists(tmp);
            throw new IOException("Master hash mismatch - reassembled file is corrupt");
        }
        Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("[STORAGE] File verified and saved: " + output.toAbsolutePath());
    }
}