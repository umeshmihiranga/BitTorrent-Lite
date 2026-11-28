package com.p2p.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Contents of a ".p2pmeta" file: everything a leecher needs to verify a download. */
public final class PieceMetadata {
    private final String fileName;
    private final long fileSize;
    private final int pieceSize;
    private final String masterHash;
    private final List<String> pieceHashes;

    public PieceMetadata(String fileName, long fileSize, int pieceSize,
                         String masterHash, List<String> pieceHashes) {
        if (!HashVerifier.isValidHash(masterHash))
            throw new IllegalArgumentException("Invalid master hash");
        if (fileSize < 0 || pieceSize <= 0)
            throw new IllegalArgumentException("Invalid size");
        // keep only the bare file name (blocks "../" tricks)
        this.fileName = Paths.get(fileName).getFileName().toString();
        this.fileSize = fileSize;
        this.pieceSize = pieceSize;
        this.masterHash = masterHash;
        this.pieceHashes = Collections.unmodifiableList(new ArrayList<>(pieceHashes));
        if (this.pieceHashes.size() != pieceCount())
            throw new IllegalArgumentException("Piece hash count does not match file size");
        for (String h : this.pieceHashes)
            if (!HashVerifier.isValidHash(h)) throw new IllegalArgumentException("Invalid piece hash");
    }

    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public int getPieceSize() { return pieceSize; }
    public String getMasterHash() { return masterHash; }
    public String pieceHash(int i) { return pieceHashes.get(i); }

    public int pieceCount() {
        return (int) ((fileSize + pieceSize - 1) / pieceSize);
    }

    /** Length of piece i. The LAST piece is usually smaller. */
    public int pieceLength(int i) {
        if (i < 0 || i >= pieceCount()) throw new IndexOutOfBoundsException("piece " + i);
        return (i == pieceCount() - 1) ? (int) (fileSize - (long) i * pieceSize) : pieceSize;
    }

    public void save(Path path) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("name=" + fileName);
        lines.add("size=" + fileSize);
        lines.add("pieceSize=" + pieceSize);
        lines.add("masterHash=" + masterHash);
        for (String h : pieceHashes) lines.add("piece=" + h);
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    public static PieceMetadata load(Path path) throws IOException {
        String name = null, master = null;
        long size = -1;
        int pSize = -1;
        List<String> hashes = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq), v = line.substring(eq + 1).trim();
            switch (k) {
                case "name": name = v; break;
                case "size": size = Long.parseLong(v); break;
                case "pieceSize": pSize = Integer.parseInt(v); break;
                case "masterHash": master = v; break;
                case "piece": hashes.add(v); break;
                default: break;
            }
        }
        if (name == null || master == null) throw new IOException("Corrupt metadata file");
        return new PieceMetadata(name, size, pSize, master, hashes);
    }
}