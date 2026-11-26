package com.p2p.ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live Swarm Peer Table panel (Member 6).
 * Displays connected peers, their current role/status, and piece availability.
 */
public class PeerTablePanel extends JPanel {

    private final PeerTableModel model = new PeerTableModel();
    private int totalPieces;

    public PeerTablePanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Live Swarm Peers"));
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
        add(new JScrollPane(table), BorderLayout.CENTER);
        setPreferredSize(new Dimension(360, 220));
    }

    public void reset(int totalPieces) {
        this.totalPieces = Math.max(0, totalPieces);
        model.clear();
    }

    public void updatePeer(String peerAddress, String status, BitSet bitfield) {
        int owned = bitfield != null ? bitfield.cardinality() : -1;
        model.upsert(peerAddress, status, owned, totalPieces);
    }

    private static final class PeerRow {
        final String address;
        String status;
        String progress;

        PeerRow(String address, String status, String progress) {
            this.address = address;
            this.status = status;
            this.progress = progress;
        }
    }

    private static final class PeerTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Peer Address", "Status", "Pieces"};
        private final Map<String, PeerRow> rowsByAddr = new LinkedHashMap<>();
        private final List<PeerRow> rows = new ArrayList<>();

        void clear() {
            rowsByAddr.clear();
            rows.clear();
            fireTableDataChanged();
        }

        void upsert(String address, String status, int owned, int total) {
            String progress = owned >= 0 && total > 0
                    ? String.format("%d / %d (%.0f%%)", owned, total, (owned * 100.0) / total)
                    : (owned >= 0 ? String.valueOf(owned) : "-");
            PeerRow existing = rowsByAddr.get(address);
            if (existing == null) {
                PeerRow row = new PeerRow(address, status, progress);
                rowsByAddr.put(address, row);
                rows.add(row);
                int idx = rows.size() - 1;
                fireTableRowsInserted(idx, idx);
            } else {
                existing.status = status;
                if (owned >= 0) {
                    existing.progress = progress;
                }
                int idx = rows.indexOf(existing);
                fireTableRowsUpdated(idx, idx);
            }
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            PeerRow r = rows.get(rowIndex);
            switch (columnIndex) {
                case 0: return r.address;
                case 1: return r.status;
                case 2: return r.progress;
                default: return "";
            }
        }
    }
}
