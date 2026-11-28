package com.p2p.ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Live swarm table showing each connected peer's endpoint, piece availability, and role.
 */
public class PeerTablePanel extends JPanel {

    private final PeerTableModel model = new PeerTableModel();
    private int totalPieces;

    public PeerTablePanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Live Swarm Peers"));

        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(3).setCellRenderer(new StatusRenderer());
        add(new JScrollPane(table), BorderLayout.CENTER);
        setPreferredSize(new Dimension(400, 240));
    }

    public void reset(int totalPieces) {
        this.totalPieces = Math.max(0, totalPieces);
        model.clear();
    }

    public void updatePeer(String peerAddress, String status, BitSet bitfield) {
        Endpoint endpoint = Endpoint.parse(peerAddress);
        int owned = bitfield == null ? -1 : bitfield.get(0, totalPieces).cardinality();
        model.upsert(peerAddress, endpoint.ip, endpoint.port, normalizeStatus(status),
                formatProgress(owned, totalPieces));
    }

    public void removePeer(String peerAddress) {
        model.remove(peerAddress);
    }

    private static String formatProgress(int owned, int total) {
        if (owned < 0) return "-";
        if (total <= 0) return owned + " pieces";
        int percentage = (int) Math.round(owned * 100.0 / total);
        return owned + " / " + total + " (" + percentage + "%)";
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.trim().isEmpty()) return "UNKNOWN";
        if ("UPLOADING".equalsIgnoreCase(status)) return "LEECHING";
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private static final class Endpoint {
        final String ip;
        final String port;

        private Endpoint(String ip, String port) {
            this.ip = ip;
            this.port = port;
        }

        static Endpoint parse(String address) {
            String endpoint = address == null ? "" : address.trim();
            int slash = endpoint.lastIndexOf('/');
            if (slash >= 0) endpoint = endpoint.substring(slash + 1);

            if (endpoint.startsWith("[")) {
                int closingBracket = endpoint.indexOf(']');
                if (closingBracket > 0) {
                    String ip = endpoint.substring(1, closingBracket);
                    String suffix = endpoint.substring(closingBracket + 1);
                    return new Endpoint(ip, parsePort(suffix));
                }
            }

            int separator = endpoint.lastIndexOf(':');
            if (separator >= 0) {
                String port = parsePort(endpoint.substring(separator));
                if (!"-".equals(port)) {
                    return new Endpoint(endpoint.substring(0, separator), port);
                }
            }
            return new Endpoint(endpoint.isEmpty() ? "Unknown" : endpoint, "-");
        }

        private static String parsePort(String suffix) {
            if (!suffix.startsWith(":")) return "-";
            try {
                int value = Integer.parseInt(suffix.substring(1));
                return value >= 0 && value <= 65535 ? String.valueOf(value) : "-";
            } catch (NumberFormatException e) {
                return "-";
            }
        }
    }

    private static final class PeerRow {
        final String ip;
        final String port;
        String progress;
        String status;

        PeerRow(String ip, String port, String progress, String status) {
            this.ip = ip;
            this.port = port;
            this.progress = progress;
            this.status = status;
        }
    }

    private static final class PeerTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Peer IP", "Port", "Piece Progress", "Status"};
        private final Map<String, PeerRow> rowsByAddress = new LinkedHashMap<>();
        private final List<PeerRow> rows = new ArrayList<>();

        void clear() {
            rowsByAddress.clear();
            rows.clear();
            fireTableDataChanged();
        }

        void upsert(String key, String ip, String port, String status, String progress) {
            PeerRow row = rowsByAddress.get(key);
            if (row == null) {
                row = new PeerRow(ip, port, progress, status);
                rowsByAddress.put(key, row);
                rows.add(row);
                int index = rows.size() - 1;
                fireTableRowsInserted(index, index);
                return;
            }

            row.progress = progress;
            row.status = status;
            int index = rows.indexOf(row);
            fireTableRowsUpdated(index, index);
        }

        void remove(String key) {
            PeerRow row = rowsByAddress.remove(key);
            if (row == null) return;
            int index = rows.indexOf(row);
            rows.remove(index);
            fireTableRowsDeleted(index, index);
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            PeerRow row = rows.get(rowIndex);
            switch (columnIndex) {
                case 0: return row.ip;
                case 1: return row.port;
                case 2: return row.progress;
                case 3: return row.status;
                default: return "";
            }
        }
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean selected, boolean focused,
                                                       int row, int column) {
            Component cell = super.getTableCellRendererComponent(
                    table, value, selected, focused, row, column);
            if (!selected) {
                String status = String.valueOf(value);
                if ("SEEDING".equals(status)) cell.setForeground(new Color(35, 130, 75));
                else if ("LEECHING".equals(status)) cell.setForeground(new Color(42, 105, 180));
                else if ("FAILED".equals(status)) cell.setForeground(new Color(190, 55, 55));
                else if ("CONNECTING".equals(status)) cell.setForeground(new Color(175, 120, 25));
                else cell.setForeground(table.getForeground());
            }
            return cell;
        }
    }
}
