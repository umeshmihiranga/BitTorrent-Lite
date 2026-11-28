package com.p2p.ui;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Network Statistics & Speed Gauges panel (Member 6).
 * Tracks real-time download/upload throughput (KB/s), piece progress, elapsed time, and ETA.
 */
public class NetworkStatsPanel extends JPanel {

    private final JLabel statusLabel   = new JLabel("Idle");
    private final JLabel downSpeedLbl  = new JLabel("0.0 KB/s");
    private final JLabel upSpeedLbl    = new JLabel("0.0 KB/s");
    private final JLabel piecesLbl     = new JLabel("0 / 0");
    private final JLabel elapsedLbl    = new JLabel("00:00");
    private final JLabel etaLbl        = new JLabel("--:--");
    private final JProgressBar progressBar = new JProgressBar(0, 100);

    private final AtomicLong windowDownBytes = new AtomicLong();
    private final AtomicLong windowUpBytes   = new AtomicLong();
    private final AtomicLong totalDownBytes  = new AtomicLong();

    private long totalFileBytes;
    private int totalPieces;
    private int completedPieces;
    private long startTimeMs;
    private long lastTickMs;
    private boolean active;

    private final Timer refreshTimer;

    public NetworkStatsPanel() {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createTitledBorder("Network Statistics & Progress"));

        progressBar.setStringPainted(true);
        progressBar.setString("0%");

        JPanel grid = new JPanel(new GridLayout(2, 6, 8, 4));
        grid.add(boldLabel("State:"));
        grid.add(statusLabel);
        grid.add(boldLabel("Download:"));
        grid.add(downSpeedLbl);
        grid.add(boldLabel("Upload:"));
        grid.add(upSpeedLbl);

        grid.add(boldLabel("Pieces:"));
        grid.add(piecesLbl);
        grid.add(boldLabel("Elapsed:"));
        grid.add(elapsedLbl);
        grid.add(boldLabel("ETA:"));
        grid.add(etaLbl);

        add(grid, BorderLayout.CENTER);
        add(progressBar, BorderLayout.SOUTH);

        this.refreshTimer = new Timer(500, e -> tick());
        this.refreshTimer.start();
    }

    public void startSession(String stateText, int totalPieces, long totalFileBytes, int initialCompleted) {
        this.totalPieces = Math.max(0, totalPieces);
        this.completedPieces = Math.max(0, Math.min(initialCompleted, this.totalPieces));
        this.totalFileBytes = Math.max(0, totalFileBytes);
        this.windowDownBytes.set(0);
        this.windowUpBytes.set(0);
        this.totalDownBytes.set(0);
        this.startTimeMs = System.currentTimeMillis();
        this.lastTickMs = this.startTimeMs;
        this.active = true;

        statusLabel.setText(stateText);
        updateProgress(initialCompleted, this.totalPieces);
    }

    public void setStateText(String text) {
        statusLabel.setText(text);
    }

    public void markStopped(String stateText) {
        active = false;
        statusLabel.setText(stateText);
        downSpeedLbl.setText("0.0 KB/s");
        upSpeedLbl.setText("0.0 KB/s");
        etaLbl.setText("--:--");
    }

    public void recordDownloadedBytes(long bytes) {
        if (bytes > 0) {
            windowDownBytes.addAndGet(bytes);
            totalDownBytes.addAndGet(bytes);
        }
    }

    public void recordUploadedBytes(long bytes) {
        if (bytes > 0) {
            windowUpBytes.addAndGet(bytes);
        }
    }

    public void updateProgress(int completed, int total) {
        int safeTotal = Math.max(0, total);
        int safeCompleted = Math.max(0, Math.min(completed, safeTotal));
        completedPieces = safeCompleted;
        piecesLbl.setText(safeCompleted + " / " + safeTotal);
        int pct = safeTotal > 0 ? (int) Math.round((safeCompleted * 100.0) / safeTotal) : 0;
        progressBar.setValue(pct);
        progressBar.setString(pct + "%");
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long dtMs = Math.max(1, now - lastTickMs);
        lastTickMs = now;

        double downKBs = (windowDownBytes.getAndSet(0) / 1024.0) / (dtMs / 1000.0);
        double upKBs   = (windowUpBytes.getAndSet(0) / 1024.0) / (dtMs / 1000.0);

        downSpeedLbl.setText(String.format("%.1f KB/s", downKBs));
        upSpeedLbl.setText(String.format("%.1f KB/s", upKBs));

        if (!active || startTimeMs == 0) {
            return;
        }

        long elapsedSec = Math.max(0, (now - startTimeMs) / 1000);
        elapsedLbl.setText(formatTime(elapsedSec));

        long downloaded = totalDownBytes.get();
        if (totalPieces > 0 && completedPieces >= totalPieces) {
            etaLbl.setText("00:00");
        } else if (totalPieces > 0 && totalFileBytes > 0 && downloaded > 0 && elapsedSec > 0) {
            double avgBytesPerSec = (double) downloaded / elapsedSec;
            if (avgBytesPerSec > 1.0) {
                long remainingBytes = Math.round(totalFileBytes
                        * (totalPieces - completedPieces) / (double) totalPieces);
                long remainingSec = (long) (remainingBytes / avgBytesPerSec);
                etaLbl.setText(formatTime(remainingSec));
                return;
            }
        }
        etaLbl.setText("--:--");
    }

    private static String formatTime(long totalSeconds) {
        long mins = totalSeconds / 60;
        long secs = totalSeconds % 60;
        return String.format("%02d:%02d", mins, secs);
    }

    private static JLabel boldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }
}
