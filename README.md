# BitTorrent-Lite

**Hybrid Peer-to-Peer File Sharing System**

A Java-based P2P file distribution network with a dual-transport architecture (TCP for reliable chunk transfer + UDP for lightweight heartbeats), Java NIO storage/zero-copy I/O, SHA-256 anti-poisoning verification, and a real-time Swing Swarm Dashboard.

---

## Architecture

- **Tracker Server (`com.p2p.tracker`)** — Centralized in-memory registry for peer/file discovery (`TCP:8000`, `UDP:8001`). No file bytes pass through the tracker.
- **Peer Nodes (`com.p2p.peer`)** — Every peer is simultaneously a **Seeder** (uploader) and **Leecher** (downloader).
- **Wire Protocol (`com.p2p.protocol`)** — Custom 5-byte TCP framing (`[4-byte length][1-byte opcode]`) with `MAX_FRAME_SIZE` (2 MB) and opcode whitelist validation, plus UDP heartbeat datagrams.
- **Storage & Crypto (`com.p2p.storage`)** — 256 KB NIO `FileChannel` chunking, `.p2pmeta` metadata, per-piece SHA-256 verification, and atomic file reassembly.
- **Desktop GUI (`com.p2p.ui`)** — Real-time Swing dashboard with Visual Chunk Matrix, Live Swarm Peer Table, Speed/ETA stats, and Packet Event Console.

```text
                    +--------------------------+
                    |      TRACKER SERVER      |
                    |  TCP :8000 | UDP :8001   |
                    +------------+-------------+
                                 ^
          REGISTER_FILE / GET_PEERS / UDP_HEARTBEAT
             +-------------------+-------------------+
             |                                       |
             v                                       v
  +---------------------+                 +---------------------+
  |   PEER 1 (Seeder)   |  TCP PIECE_DATA |  PEER 2 (Leecher)   |
  |   TCP :5001         +---------------->|  TCP :5002          |
  +---------------------+  SHA-256 check  +---------------------+
```

---

## Prerequisites

- **Java 17 or Java 21+** (`javac` and `java` on `PATH`)
- No external libraries required (100% Java Standard Library)

---

## Quick Start

### 1. Compile the Project

**Windows (PowerShell):**
```powershell
javac -d out (Get-ChildItem -Path src -Recurse -Filter *.java | ForEach-Object { $_.FullName })
```

**Linux / macOS (Bash):**
```bash
javac -d out $(find src -name "*.java")
```

### 2. Launch the Desktop GUI
```bash
java -cp out com.p2p.ui.MainGuiWindow
```
> By default, `Tracker Host` is pre-configured to `13.127.166.143` (`TCP: 8000`, `UDP: 8001`). To test locally on a single machine, change `Tracker Host` to `127.0.0.1` and run a local `TrackerServer` first (see below).

### 3. Share (Seed) a File
1. Check **`LAN (0.0.0.0)`** if sharing with peers on other machines/networks *(automatically attempts UPnP port forwarding on your router)*.
2. Click **`Seed File...`** and select any file.
3. The file is sliced into 256 KB chunks inside `shared/` and a `.p2pmeta` file is saved.
4. Click **`Copy Hash`** and send the 64-character SHA-256 hash to anyone who wants to download the file.

### 4. Download a File
1. Open `MainGuiWindow` on the downloading peer *(if running a 2nd window on the same PC, `Peer Port` automatically picks the next free port such as `5002`)*.
2. Click **`Download by Hash...`** and paste the 64-character hash from the Seeder *(or click **`Download (.p2pmeta)...`** if you have the `.p2pmeta` file)*.
3. The peer fetches metadata directly from the Seeder, downloads & verifies every 256 KB piece over TCP, reassembles the file in `downloads/`, and automatically continues seeding completed pieces to other peers.

---

## Running Your Own Tracker Server

### Local Machine
```bash
java -cp out com.p2p.tracker.TrackerServer
```
Then set `Tracker Host` to `127.0.0.1` in `MainGuiWindow`.

### Cloud Server (AWS Lightsail / EC2)
1. Open inbound firewall rules for **TCP `8000`** and **UDP `8001`** (`0.0.0.0/0`).
2. Compile and start the tracker in the background:
   ```bash
   javac -d out $(find src -name "*.java")
   nohup java -cp out com.p2p.tracker.TrackerServer > tracker.log 2>&1 &
   ```
3. Enter your server's public IP in `Tracker Host` in `MainGuiWindow`.

> **Note for Internet Peers:** If a Seeder is behind a home router with UPnP disabled, forward the Seeder's `Peer Port` (e.g., TCP `5001`) on the router—or connect peers via a virtual LAN such as Tailscale/ZeroTier.

---

## Automated End-to-End Integration Test

Run the mock-free integration test (starts `TrackerServer`, `PeerServer`, `SeederService`, and `DownloadManager` on localhost and verifies byte-identical SHA-256 reassembly):
```bash
java -cp out com.p2p.IntegrationDemo
```

Other standalone module demos:
```bash
java -cp out com.p2p.storage.Member4Demo
java -cp out com.p2p.peer.client.Member5Demo
```

---

## Project Structure

```text
BitTorrent-Lite/
├── src/com/p2p/
│   ├── protocol/            ← Member 2: Wire Protocol, Framing & Security
│   ├── tracker/             ← Member 1: Tracker Server Engine (TCP + UDP)
│   ├── peer/
│   │   ├── server/          ← Member 3: Peer Upload Server, UploadListener & UPnP
│   │   └── client/          ← Member 5: Download Engine, TrackerClient, SeederService & MetadataClient
│   ├── storage/             ← Member 4: Java NIO Chunking, SHA-256 Hashing & Reassembly
│   ├── ui/                  ← Member 6: Swing Swarm Dashboard (MainGuiWindow)
│   └── IntegrationDemo.java ← End-to-end integration test across all modules
├── shared/                  ← Chunked files & .p2pmeta files for seeding
├── downloads/               ← Downloaded & reassembled files
└── PROJECT_SPECIFICATION.md
```

---

## Team

| Member | Module | Package |
|---|---|---|
| 1 | Tracker Server Engine | `com.p2p.tracker` |
| 2 | Wire Protocol & Security | `com.p2p.protocol` |
| 3 | Peer Upload Server | `com.p2p.peer.server` |
| 4 | Java NIO Storage, Chunking & Crypto | `com.p2p.storage` |
| 5 | Download Engine | `com.p2p.peer.client` |
| 6 | Desktop GUI & Swarm Dashboard | `com.p2p.ui` |
