# BitTorrent-Lite

**Hybrid Peer-to-Peer File Sharing System**

A Java-based P2P file distribution network with dual-transport architecture (TCP + UDP) and Java NIO storage/transfer, built for the Network Programming module.

## Architecture

- **Tracker Server** — Centralized registry for peer/file discovery (TCP:8000, UDP:8001)
- **Peer Nodes** — Each peer is both a Seeder (uploader) and Leecher (downloader)
- **TCP** — Reliable binary chunk transfers between peers
- **UDP** — Lightweight heartbeat signaling to keep peers alive in the tracker
- **Java NIO** — Channel-based file chunking (`FileChannel`, `ByteBuffer`) and zero-copy disk-to-network transfers (`transferTo()` / `transferFrom()`)

## Project Structure

```
BitTorrent-Lite/
├── src/
│   └── com/p2p/
│       ├── protocol/       ← Member 2: Wire Protocol & Security (shared library)
│       ├── tracker/         ← Member 1: Tracker Server Engine (TCP + UDP)
│       ├── peer/
│       │   ├── server/      ← Member 3: Peer Upload Server (Seeder)
│       │   └── client/      ← Member 5: Download Engine & Tracker Client
│       ├── storage/         ← Member 4: Java NIO File Chunking, Hashing & Zero-Copy I/O
│       └── ui/              ← Member 6: Desktop GUI & Swarm Dashboard
├── shared/                  ← Files shared/seeded by this peer
├── downloads/               ← Downloaded files reassembled here
└── PROJECT_SPECIFICATION.md
```

## Building & Running

```bash
# Compile all sources
javac -d out src/com/p2p/**/*.java

# Run Tracker Server
java -cp out com.p2p.tracker.TrackerServer

# Run Peer (GUI mode)
java -cp out com.p2p.ui.MainGuiWindow

# Run Member 4 Standalone NIO & Zero-Copy Demo
java -cp out com.p2p.storage.Member4Demo
```

## Team

| Member | Module | Package |
|--------|--------|---------|
| 1 | Tracker Server Engine | `com.p2p.tracker` |
| 2 | Wire Protocol & Security | `com.p2p.protocol` |
| 3 | Peer Upload Server | `com.p2p.peer.server` |
| 4 | Java NIO Storage, Chunking & Crypto | `com.p2p.storage` |
| 5 | Download Engine | `com.p2p.peer.client` |
| 6 | Desktop GUI | `com.p2p.ui` |
