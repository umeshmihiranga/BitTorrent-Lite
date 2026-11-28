package com.p2p.ui;

import com.p2p.peer.client.DownloadListener;
import com.p2p.peer.client.DownloadManager;
import com.p2p.peer.client.MetadataClient;
import com.p2p.peer.client.SeederService;
import com.p2p.peer.client.TrackerClient;
import com.p2p.peer.server.PeerServer;
import com.p2p.peer.server.UploadListener;
import com.p2p.peer.server.UpnpPortMapper;
import com.p2p.storage.FileChunker;
import com.p2p.storage.HashVerifier;
import com.p2p.storage.PieceMetadata;
import com.p2p.storage.PieceStorage;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.BitSet;
import java.util.Date;
import java.util.UUID;

/**
 * Main Desktop GUI & Real-Time Swarm Dashboard (Member 6).
 * Launch: {@code java -cp out com.p2p.ui.MainGuiWindow}
 */
public class MainGuiWindow extends JFrame {

    private static final int MAX_LOG_LINES = 500;

    private final JTextField trackerHostField = new JTextField("13.127.166.143", 12);
    private final JTextField trackerTcpField  = new JTextField("8000", 5);
    private final JTextField trackerUdpField  = new JTextField("8001", 5);
    private final JTextField listenPortField  = new JTextField(String.valueOf(findAvailablePort(5001)), 5);
    private final JTextField peerIdField      = new JTextField(
            "peer-" + UUID.randomUUID().toString().substring(0, 6), 10);
    private final JCheckBox lanBindCheck      = new JCheckBox("LAN (0.0.0.0)", false);

    private final JButton seedBtn     = new JButton("Seed File...");
    private final JButton downloadBtn = new JButton("Download (.p2pmeta)...");
    private final JButton hashBtn     = new JButton("Download by Hash...");
    private final JButton copyHashBtn = new JButton("Copy Hash");
    private final JButton stopBtn     = new JButton("Stop");
    private final JLabel  fileInfoLbl = new JLabel("No active file");

    private volatile String activeFileHash;

    private final NetworkStatsPanel statsPanel = new NetworkStatsPanel();
    private final ChunkGridPanel    chunkGrid  = new ChunkGridPanel();
    private final PeerTablePanel    peerTable  = new PeerTablePanel();
    private final JTextArea         logConsole = new JTextArea(8, 80);

    private PeerServer      peerServer;
    private SeederService   seederService;
    private DownloadManager downloadManager;

    public MainGuiWindow() {
        super("BitTorrent-Lite — P2P Swarm Dashboard");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopActiveSession();
                dispose();
                System.exit(0);
            }
        });

        initLayout();
        pack();
        setMinimumSize(new Dimension(920, 660));
        setLocationRelativeTo(null);
    }

    private void initLayout() {
        setLayout(new BorderLayout(8, 8));

        // ── Top: Connection & Action Bar ────────────────────────────
        JPanel topContainer = new JPanel(new BorderLayout(4, 4));
        topContainer.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

        JPanel configBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        configBar.setBorder(BorderFactory.createTitledBorder("Swarm Configuration"));
        configBar.add(new JLabel("Tracker Host:"));
        configBar.add(trackerHostField);
        configBar.add(new JLabel("TCP:"));
        configBar.add(trackerTcpField);
        configBar.add(new JLabel("UDP:"));
        configBar.add(trackerUdpField);
        configBar.add(new JLabel("Peer Port:"));
        configBar.add(listenPortField);
        configBar.add(new JLabel("Peer ID:"));
        configBar.add(peerIdField);
        configBar.add(lanBindCheck);

        JPanel actionBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        stopBtn.setEnabled(false);
        copyHashBtn.setEnabled(false);
        seedBtn.addActionListener(e -> onSeedClicked());
        downloadBtn.addActionListener(e -> onDownloadClicked());
        hashBtn.addActionListener(e -> onDownloadByHashClicked());
        copyHashBtn.addActionListener(e -> onCopyHashClicked());
        stopBtn.addActionListener(e -> stopActiveSession());

        actionBar.add(seedBtn);
        actionBar.add(downloadBtn);
        actionBar.add(hashBtn);
        actionBar.add(stopBtn);
        actionBar.add(copyHashBtn);
        actionBar.add(Box.createHorizontalStrut(8));
        fileInfoLbl.setFont(fileInfoLbl.getFont().deriveFont(Font.ITALIC));
        actionBar.add(fileInfoLbl);

        topContainer.add(configBar, BorderLayout.NORTH);
        topContainer.add(actionBar, BorderLayout.SOUTH);
        add(topContainer, BorderLayout.NORTH);

        // ── Center: Stats + Chunk Matrix (left) | Peer Table (right) ─
        JPanel leftPanel = new JPanel(new BorderLayout(8, 8));
        leftPanel.add(statsPanel, BorderLayout.NORTH);
        JScrollPane gridScroll = new JScrollPane(chunkGrid);
        gridScroll.setBorder(BorderFactory.createEmptyBorder());
        leftPanel.add(gridScroll, BorderLayout.CENTER);

        JSplitPane centerSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, leftPanel, peerTable);
        centerSplit.setResizeWeight(0.60);
        centerSplit.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        add(centerSplit, BorderLayout.CENTER);

        // ── Bottom: Packet Event Console ────────────────────────────
        logConsole.setEditable(false);
        logConsole.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logConsole.setBackground(new Color(24, 26, 31));
        logConsole.setForeground(new Color(212, 218, 226));

        JPanel consolePanel = new JPanel(new BorderLayout(4, 4));
        consolePanel.setBorder(BorderFactory.createTitledBorder("Packet Event Console"));
        consolePanel.add(new JScrollPane(logConsole), BorderLayout.CENTER);

        JButton clearLogBtn = new JButton("Clear Log");
        clearLogBtn.addActionListener(e -> logConsole.setText(""));
        JPanel consoleBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        consoleBtns.add(clearLogBtn);
        consolePanel.add(consoleBtns, BorderLayout.SOUTH);
        consolePanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 8, 8, 8),
                consolePanel.getBorder()));

        add(consolePanel, BorderLayout.SOUTH);
    }

    // ───────────────────────── Actions ─────────────────────────

    private void onSeedClicked() {
        JFileChooser chooser = new JFileChooser(new File("."));
        chooser.setDialogTitle("Select File to Seed");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File sourceFile = chooser.getSelectedFile();

        final String host;
        final int tcpPort, udpPort, listenPort;
        final String peerId;
        final boolean lanMode;
        try {
            host = trackerHostField.getText().trim();
            tcpPort = Integer.parseInt(trackerTcpField.getText().trim());
            udpPort = Integer.parseInt(trackerUdpField.getText().trim());
            listenPort = Integer.parseInt(listenPortField.getText().trim());
            peerId = peerIdField.getText().trim();
            lanMode = lanBindCheck.isSelected();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Invalid port number.", "Config Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        setControlsRunning(true);
        appendLog("[UI] Chunking " + sourceFile.getName() + "...");

        new Thread(() -> {
            try {
                Path sharedDir = Paths.get("shared");
                PieceStorage storage = FileChunker.chunk(sourceFile.toPath(), sharedDir);
                PieceMetadata meta = storage.getMetadata();

                Path metaPath = sharedDir.resolve(meta.getFileName() + ".p2pmeta");
                meta.save(metaPath);

                UploadListener uploadListener = createUploadListener();
                PeerServer server = lanMode
                        ? PeerServer.forLan(listenPort, storage, peerId, uploadListener)
                        : PeerServer.forLocalhost(listenPort, storage, peerId, uploadListener);
                server.start();

                int actualPort = server.getBoundPort();
                TrackerClient tracker = new TrackerClient(host, tcpPort);
                SeederService seeder = new SeederService(
                        storage, tracker, peerId, actualPort, udpPort, createDownloadListener());
                seeder.start();

                synchronized (MainGuiWindow.this) {
                    peerServer = server;
                    seederService = seeder;
                    activeFileHash = meta.getMasterHash();
                }

                tryUpnpIfLan(lanMode, actualPort);

                SwingUtilities.invokeLater(() -> {
                    copyHashBtn.setEnabled(true);
                    fileInfoLbl.setText("Seeding: " + meta.getFileName()
                            + " (" + meta.pieceCount() + " pieces) | Hash: "
                            + meta.getMasterHash().substring(0, 12) + "...");
                    chunkGrid.reset(meta.pieceCount(), storage.getBitfield());
                    peerTable.reset(meta.pieceCount());
                    statsPanel.startSession("Seeding", meta.pieceCount(),
                            meta.getFileSize(), meta.pieceCount());
                    appendLog("[UI] Metadata saved: " + metaPath.toAbsolutePath());
                    appendLog("[UI] File Hash: " + meta.getMasterHash());
                    appendLog("[UI] Seeder listening on port " + actualPort);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("[ERR] Failed to start seeding: " + ex.getMessage());
                    JOptionPane.showMessageDialog(this, ex.getMessage(), "Seed Error",
                            JOptionPane.ERROR_MESSAGE);
                    stopActiveSession();
                });
            }
        }, "gui-seed-starter").start();
    }

    private void onDownloadClicked() {
        JFileChooser metaChooser = new JFileChooser(new File("shared"));
        metaChooser.setDialogTitle("Select .p2pmeta File");
        metaChooser.setFileFilter(new FileNameExtensionFilter("BitTorrent-Lite Metadata (*.p2pmeta)", "p2pmeta"));
        if (metaChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File metaFile = metaChooser.getSelectedFile();

        final PieceMetadata meta;
        try {
            meta = PieceMetadata.load(metaFile.toPath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not load .p2pmeta: " + ex.getMessage(),
                    "Metadata Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        JFileChooser outChooser = new JFileChooser(new File("downloads"));
        outChooser.setDialogTitle("Save Downloaded File As");
        outChooser.setSelectedFile(new File("downloads", meta.getFileName()));
        if (outChooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        startDownloadSession(meta, outChooser.getSelectedFile().toPath());
    }

    private void onDownloadByHashClicked() {
        String hash = JOptionPane.showInputDialog(this,
                "Paste the 64-character SHA-256 File Hash from the Seeder:",
                "Download by File Hash", JOptionPane.PLAIN_MESSAGE);
        if (hash == null) return;
        hash = hash.trim().toLowerCase();
        if (!HashVerifier.isValidHash(hash)) {
            JOptionPane.showMessageDialog(this,
                    "Invalid SHA-256 hash (must be 64 hex characters).",
                    "Invalid Hash", JOptionPane.ERROR_MESSAGE);
            return;
        }

        final String host;
        final int tcpPort;
        final String peerId;
        try {
            host = trackerHostField.getText().trim();
            tcpPort = Integer.parseInt(trackerTcpField.getText().trim());
            peerId = peerIdField.getText().trim();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Invalid port number.", "Config Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        final String targetHash = hash;
        setControlsRunning(true);
        appendLog("[UI] Fetching metadata from swarm for hash " + targetHash.substring(0, 12) + "...");

        new Thread(() -> {
            try {
                TrackerClient tracker = new TrackerClient(host, tcpPort);
                PieceMetadata meta = MetadataClient.fetchFromSwarm(
                        tracker, targetHash, peerId, createDownloadListener());
                Path outputPath = Paths.get("downloads", meta.getFileName());
                startDownloadSession(meta, outputPath);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("[ERR] Metadata fetch failed: " + ex.getMessage());
                    JOptionPane.showMessageDialog(this, ex.getMessage(), "Hash Download Error",
                            JOptionPane.ERROR_MESSAGE);
                    stopActiveSession();
                });
            }
        }, "gui-metadata-fetcher").start();
    }

    private void startDownloadSession(PieceMetadata meta, Path outputPath) {
        final String host;
        final int tcpPort, udpPort, listenPort;
        final String peerId;
        final boolean lanMode;
        try {
            host = trackerHostField.getText().trim();
            tcpPort = Integer.parseInt(trackerTcpField.getText().trim());
            udpPort = Integer.parseInt(trackerUdpField.getText().trim());
            listenPort = Integer.parseInt(listenPortField.getText().trim());
            peerId = peerIdField.getText().trim();
            lanMode = lanBindCheck.isSelected();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Invalid port number.", "Config Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        setControlsRunning(true);

        new Thread(() -> {
            try {
                Path workDir = Paths.get("downloads", ".pieces");
                PieceStorage storage = new PieceStorage(workDir, meta);

                UploadListener uploadListener = createUploadListener();
                PeerServer server = lanMode
                        ? PeerServer.forLan(listenPort, storage, peerId, uploadListener)
                        : PeerServer.forLocalhost(listenPort, storage, peerId, uploadListener);
                server.start();
                int actualPort = server.getBoundPort();

                TrackerClient tracker = new TrackerClient(host, tcpPort);
                DownloadManager dm = new DownloadManager(
                        storage, tracker, peerId, actualPort, outputPath,
                        createDownloadListener(), 5, udpPort);

                synchronized (MainGuiWindow.this) {
                    peerServer = server;
                    downloadManager = dm;
                    activeFileHash = meta.getMasterHash();
                }

                tryUpnpIfLan(lanMode, actualPort);

                SwingUtilities.invokeLater(() -> {
                    copyHashBtn.setEnabled(true);
                    fileInfoLbl.setText("Downloading: " + meta.getFileName()
                            + " (" + meta.pieceCount() + " pieces)");
                    chunkGrid.reset(meta.pieceCount(), storage.getBitfield());
                    peerTable.reset(meta.pieceCount());
                    statsPanel.startSession("Leeching", meta.pieceCount(),
                            meta.getFileSize(), storage.completedCount());
                });

                dm.start();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("[ERR] Failed to start download: " + ex.getMessage());
                    JOptionPane.showMessageDialog(this, ex.getMessage(), "Download Error",
                            JOptionPane.ERROR_MESSAGE);
                    stopActiveSession();
                });
            }
        }, "gui-download-starter").start();
    }

    private void onCopyHashClicked() {
        String hash = activeFileHash;
        if (hash != null && !hash.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(hash), null);
            appendLog("[UI] Copied file hash to clipboard: " + hash);
        }
    }

    private void tryUpnpIfLan(boolean lanMode, int port) {
        if (!lanMode) return;
        new Thread(() -> {
            String result = UpnpPortMapper.tryForwardTcpPort(port);
            SwingUtilities.invokeLater(() -> appendLog("[UPnP] " + result));
        }, "upnp-port-mapper").start();
    }

    private synchronized void stopActiveSession() {
        if (downloadManager != null) {
            downloadManager.stop();
            downloadManager = null;
        }
        if (seederService != null) {
            seederService.stop();
            seederService = null;
        }
        if (peerServer != null) {
            peerServer.shutdown();
            peerServer = null;
        }
        SwingUtilities.invokeLater(() -> {
            setControlsRunning(false);
            statsPanel.markStopped("Stopped");
            appendLog("[UI] Session stopped.");
        });
    }

    private void setControlsRunning(boolean running) {
        seedBtn.setEnabled(!running);
        downloadBtn.setEnabled(!running);
        hashBtn.setEnabled(!running);
        stopBtn.setEnabled(running);
        if (!running) {
            copyHashBtn.setEnabled(false);
            activeFileHash = null;
        }
        trackerHostField.setEnabled(!running);
        trackerTcpField.setEnabled(!running);
        trackerUdpField.setEnabled(!running);
        listenPortField.setEnabled(!running);
        peerIdField.setEnabled(!running);
        lanBindCheck.setEnabled(!running);
    }

    // ───────────────────────── Listeners ─────────────────────────

    private DownloadListener createDownloadListener() {
        return new DownloadListener() {
            @Override
            public void onPieceCompleted(int pieceIndex, String peerAddress, int completed, int total) {
                SwingUtilities.invokeLater(() -> {
                    chunkGrid.markCompleted(pieceIndex);
                    statsPanel.updateProgress(completed, total);
                });
            }

            @Override
            public void onPieceFailed(int pieceIndex, String peerAddress, String reason) {
                SwingUtilities.invokeLater(() -> chunkGrid.markFailed(pieceIndex));
            }

            @Override
            public void onPeerStatus(String peerAddress, String status, BitSet bitfield) {
                SwingUtilities.invokeLater(() -> {
                    if ("DISCONNECTED".equalsIgnoreCase(status)
                            || "FAILED".equalsIgnoreCase(status)) {
                        peerTable.removePeer(peerAddress);
                    } else {
                        peerTable.updatePeer(peerAddress, status, bitfield);
                    }
                });
            }

            @Override
            public void onBytesDownloaded(long bytes) {
                statsPanel.recordDownloadedBytes(bytes);
            }

            @Override
            public void onLog(String line) {
                SwingUtilities.invokeLater(() -> appendLog(line));
            }

            @Override
            public void onDownloadComplete(Path outputFile) {
                SwingUtilities.invokeLater(() -> {
                    statsPanel.setStateText("Seeding (Complete)");
                    fileInfoLbl.setText("Completed & Seeding: " + outputFile.getFileName());
                    appendLog("[DONE] Download complete -> now seeding: " + outputFile);
                });
            }

            @Override
            public void onError(String message) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("[FATAL] " + message);
                    statsPanel.markStopped("Error");
                });
            }
        };
    }

    private UploadListener createUploadListener() {
        return new UploadListener() {
            @Override
            public void onPeerConnected(String peerAddress, String remotePeerId) {
                SwingUtilities.invokeLater(() -> {
                    peerTable.updatePeer(peerAddress, "LEECHING", null);
                    appendLog("[SWARM] Leecher connected: " + remotePeerId + " at " + peerAddress);
                });
            }

            @Override
            public void onPeerDisconnected(String peerAddress) {
                SwingUtilities.invokeLater(() -> {
                    peerTable.removePeer(peerAddress);
                    appendLog("[UPLOAD] Peer disconnected: " + peerAddress);
                });
            }

            @Override
            public void onBytesUploaded(long bytes) {
                statsPanel.recordUploadedBytes(bytes);
            }

            @Override
            public void onUploadLog(String line) {
                SwingUtilities.invokeLater(() -> appendLog(line));
            }
        };
    }

    private void appendLog(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss").format(new Date());
        logConsole.append("[" + ts + "] " + line + "\n");
        if (logConsole.getLineCount() > MAX_LOG_LINES) {
            try {
                int endOffset = logConsole.getLineEndOffset(logConsole.getLineCount() - MAX_LOG_LINES - 1);
                logConsole.replaceRange("", 0, endOffset);
            } catch (Exception ignored) {
            }
        }
        logConsole.setCaretPosition(logConsole.getDocument().getLength());
    }

    private static int findAvailablePort(int startPort) {
        for (int port = startPort; port < startPort + 50; port++) {
            try (java.net.ServerSocket s = new java.net.ServerSocket(port)) {
                return port;
            } catch (java.io.IOException ignored) {
            }
        }
        return 0;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            new MainGuiWindow().setVisible(true);
        });
    }
}
