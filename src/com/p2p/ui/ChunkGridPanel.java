package com.p2p.ui;

import javax.swing.*;
import java.awt.*;
import java.util.BitSet;

/**
 * Visual Chunk Matrix panel (Member 6).
 * Displays a dynamic grid of piece cells that update in real time as pieces
 * are verified and saved, or marked failed/re-queued.
 */
public class ChunkGridPanel extends JPanel {

    private static final Color COLOR_MISSING = new Color(224, 228, 234);
    private static final Color COLOR_OWNED   = new Color(46, 160, 67);
    private static final Color COLOR_FAILED  = new Color(218, 139, 32);
    private static final Color COLOR_BORDER  = new Color(180, 188, 198);

    private static final int CELL_SIZE = 18;
    private static final int CELL_GAP  = 4;

    private int totalPieces;
    private BitSet owned = new BitSet();
    private BitSet failed = new BitSet();

    public ChunkGridPanel() {
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createTitledBorder("Visual Chunk Matrix"));
        setPreferredSize(new Dimension(420, 180));
    }

    /** Resets the grid for a new file transfer. */
    public void reset(int totalPieces, BitSet initialOwned) {
        this.totalPieces = Math.max(0, totalPieces);
        this.owned = initialOwned != null ? (BitSet) initialOwned.clone() : new BitSet();
        this.failed = new BitSet();
        revalidate();
        repaint();
    }

    /** Marks a piece as verified and owned. */
    public void markCompleted(int pieceIndex) {
        if (pieceIndex >= 0 && pieceIndex < totalPieces) {
            owned.set(pieceIndex);
            failed.clear(pieceIndex);
            repaint();
        }
    }

    /** Temporarily highlights a piece as failed/re-queued until another peer completes it. */
    public void markFailed(int pieceIndex) {
        if (pieceIndex >= 0 && pieceIndex < totalPieces && !owned.get(pieceIndex)) {
            failed.set(pieceIndex);
            repaint();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getParent() != null && getParent().getWidth() > 0
                ? getParent().getWidth() : 460;
        Insets in = getInsets();
        int usableW = Math.max(100, width - in.left - in.right - 16);
        int cols = Math.max(1, usableW / (CELL_SIZE + CELL_GAP));
        int rows = totalPieces == 0 ? 1 : (totalPieces + cols - 1) / cols;
        int height = in.top + in.bottom + 24 + rows * (CELL_SIZE + CELL_GAP);
        return new Dimension(usableW, Math.max(120, height));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Insets in = getInsets();
        int startX = in.left + 8;
        int startY = in.top + 8;

        if (totalPieces == 0) {
            g2.setColor(Color.GRAY);
            g2.drawString("No active file loaded. Seed or open a .p2pmeta file to begin.",
                    startX, startY + 18);
            g2.dispose();
            return;
        }

        int usableW = Math.max(100, getWidth() - in.left - in.right - 16);
        int cols = Math.max(1, usableW / (CELL_SIZE + CELL_GAP));

        for (int i = 0; i < totalPieces; i++) {
            int col = i % cols;
            int row = i / cols;
            int x = startX + col * (CELL_SIZE + CELL_GAP);
            int y = startY + row * (CELL_SIZE + CELL_GAP);

            if (owned.get(i)) {
                g2.setColor(COLOR_OWNED);
            } else if (failed.get(i)) {
                g2.setColor(COLOR_FAILED);
            } else {
                g2.setColor(COLOR_MISSING);
            }
            g2.fillRoundRect(x, y, CELL_SIZE, CELL_SIZE, 4, 4);
            g2.setColor(COLOR_BORDER);
            g2.drawRoundRect(x, y, CELL_SIZE, CELL_SIZE, 4, 4);
        }

        // Legend at bottom-right
        int legendY = getHeight() - in.bottom - 8;
        g2.setFont(getFont().deriveFont(11f));
        drawLegendItem(g2, startX, legendY, COLOR_OWNED, "Verified");
        drawLegendItem(g2, startX + 75, legendY, COLOR_FAILED, "Retrying");
        drawLegendItem(g2, startX + 150, legendY, COLOR_MISSING, "Missing");

        g2.dispose();
    }

    private void drawLegendItem(Graphics2D g2, int x, int baselineY, Color color, String label) {
        g2.setColor(color);
        g2.fillRect(x, baselineY - 9, 10, 10);
        g2.setColor(COLOR_BORDER);
        g2.drawRect(x, baselineY - 9, 10, 10);
        g2.setColor(Color.DARK_GRAY);
        g2.drawString(label, x + 14, baselineY);
    }
}
