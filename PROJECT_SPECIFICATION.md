# Network Programming Group Project Specification & Work Breakdown

**Project Title:** BitTorrent-Lite: Hybrid Peer-to-Peer (P2P) File Sharing System  
**Module:** Network Programming  
**Implementation Language:** Java (Standard Library: `java.net.*`, `java.io.*`, `java.util.concurrent.*`)  
**Architecture:** Hybrid Peer-to-Peer with Dual Transport Layer (TCP for Data + UDP for Signaling)  
**Team Size:** 6 Members  

---

## 1. Project Overview

In traditional client-server file transfers (such as standard HTTP or FTP), the central server acts as a single point of failure and a bandwidth bottleneck. When hundreds of clients download a large file simultaneously, the server's bandwidth saturates and causes severe degradation.

This project implements **BitTorrent-Lite**, a high-performance hybrid Peer-to-Peer (P2P) file distribution network designed to scale horizontally across multiple peer machines:
* **Centralized Tracker Server:** Maintains an active in-memory registry of files and participating peers. **No file data ever flows through the Tracker.**
* **Decentralized Peer Swarm:** Every peer node functions simultaneously as both a **client (Leecher)** and a **server (Seeder)**.
* **Dual-Transport Architecture:**
  1. **TCP Sockets:** Used for reliable, in-order peer-to-peer binary chunk transfers (`REQUEST_PIECE` $\rightarrow$ `PIECE_DATA`).
  2. **UDP Datagrams:** Used for lightweight, low-overhead peer discovery, tracker registration queries, and periodic keep-alive heartbeats without 3-way handshake overhead.
* **Chunking & Cryptographic Validation:** Files are sliced into uniform binary pieces (e.g., 256 KB). Leechers download disjoint pieces in parallel from multiple seeders over TCP, verify each piece using SHA-256 cryptographic hashes to prevent file corruption/poisoning, and reassemble the original file.

```
                           +------------------------+
                           |     TRACKER SERVER     |
                           |   TCP Port 8000 (Reg)  |
                           |   UDP Port 8001 (Ping) |
                           +------------------------+
                                  ^          ^
                 1. REGISTER_FILE |          | 2. GET_PEERS
                     (TCP/UDP)    |          |   (TCP/UDP)
                                  |          |
                    +-------------+--+    +--+-------------+
                    |  PEER A        |    |  PEER B        |
                    |  (Seeder)      |    |  (Leecher)     |
                    |  TCP: 5001     |    |  TCP: 5002     |
                    +----------------+    +----------------+
                            ^                    |
                            | 3. REQUEST_PIECE   |
                            +--------------------+
                              4. PIECE_DATA (TCP)
```

---

## 2. Core Network Programming Theory Applied

1. **Dual Transport-Layer Protocols (TCP vs. UDP Trade-Offs):**
   * **TCP (`Socket`, `ServerSocket`):** Applied where reliability is paramount. Guaranteed byte-ordering, flow control, and congestion avoidance prevent binary chunk corruption during file transmission.
   * **UDP (`DatagramSocket`, `DatagramPacket`):** Applied for periodic heartbeats and discovery where low latency and zero connection overhead are critical. Dropping an occasional heartbeat packet does not disrupt file integrity.
2. **Application-Layer Framing (Solving the TCP "Sticky Packet" Problem):**
   * TCP is a continuous byte stream with no intrinsic message boundaries. A custom 5-byte header prefix (`[4-byte Length][1-byte Opcode]`) is parsed via `DataInputStream.readFully()` to completely eliminate packet fragmentation and stream sticking.
3. **Multi-Threading & Socket Concurrency:**
   * Thread pools (`ExecutorService`) manage concurrent uploads to multiple peers, while background worker threads concurrently download different pieces from distinct seeders in parallel.
4. **Zero-Trust Data Integrity & Anti-Poisoning:**
   * Peers are treated as untrusted endpoints. Every piece is validated against its pre-shared SHA-256 cryptographic hash before being written to disk, preventing malicious chunk injection attacks.

---

## 3. Wire Protocol & Message Framing Specification

### A. TCP Stream Framing (Used for File Data & Connection Channels)
Every TCP message sent over the wire begins with a fixed **5-byte header**:

```
+--------------------------+-----------------------+-----------------------------+
| Field                    | Data Type             | Description                 |
+--------------------------+-----------------------+-----------------------------+
| Payload Length           | 4 bytes (int)         | Byte length of payload body |
| Message Type ID (Opcode) | 1 byte (byte)         | Command identifier          |
| Payload Body             | N bytes (byte[])      | Command-specific data       |
+--------------------------+-----------------------+-----------------------------+
```

### B. UDP Datagram Structure (Used for Signaling & Heartbeats)
UDP packets are self-contained datagrams packed into byte buffers:
```
[1-byte Opcode] [4-byte Sequence/Port] [UTF Payload: peerId, fileHash]
```

### Standard Command Opcodes:
* `0x01` (`REGISTER_FILE`): Peer → Tracker. Announces file name, file hash, size, and listening TCP port.
* `0x02` (`REGISTER_SUCCESS`): Tracker → Peer. Confirms registration.
* `0x03` (`GET_PEERS`): Peer → Tracker. Queries active peer IP/ports holding a specific file hash.
* `0x04` (`PEER_LIST_RESPONSE`): Tracker → Peer. Returns list of available peers and their piece bitfields.
* `0x05` (`UNREGISTER_FILE`): Peer → Tracker. Gracefully removes a peer from a swarm upon exit.
* `0x10` (`HANDSHAKE`): Peer ↔ Peer. Establishes identity and verifies file hash between two peers over TCP.
* `0x11` (`BITFIELD`): Peer ↔ Peer. Shares bitmask of pieces currently owned.
* `0x12` (`REQUEST_PIECE`): Leecher → Seeder. Requests a specific chunk index (`fileHash` + `pieceIndex`).
* `0x13` (`PIECE_DATA`): Seeder → Leecher. Transmits raw binary chunk payload over TCP.
* `0x20` (`UDP_HEARTBEAT`): Peer → Tracker (UDP). Periodic ping datagram keeping peer entry active in tracker registry.
* `0x21` (`UDP_HEARTBEAT_ACK`): Tracker → Peer (UDP). Heartbeat acknowledgment.
* `0xFF` (`ERROR`): Error notification frame.

---

## 4. Equal Work Distribution (6 Members)

The architecture is partitioned into 6 independent, well-defined modules with clear boundaries. No single member is overloaded.

```
                                  +------------------------------------+
                                  |      SHARED WIRE PROTOCOL          |
                                  |       (Member 2 - Lead)            |
                                  +-----------------+------------------+
                                                    |
                   +--------------------------------+--------------------------------+
                   |                                |                                |
                   v                                v                                v
        +----------------------+         +----------------------+         +----------------------+
        | TRACKER SERVER CORE  |         |  PEER UPLOAD SERVER  |         |  FILE STORAGE & HASH |
        |      (Member 1)      |         |      (Member 3)      |         |      (Member 4)      |
        +----------------------+         +----------------------+         +----------------------+
                   ^                                ^                                |
                   |                                |                                v
                   |                     +----------+-----------+         +----------------------+
                   +---------------------| DOWNLOAD ENGINE CORE |<--------| REAL-TIME SWARM GUI  |
                                         |      (Member 5)      |         |      (Member 6)      |
                                         +----------------------+         +----------------------+
```

---

### Member 1: Central Tracker Server Engine (TCP + UDP)
* **Responsibilities:**
  * Implements `TrackerServer.java` hosting dual listeners: a TCP `ServerSocket` on port `8000` for peer lookup/registration and a `DatagramSocket` on port `8001` for UDP heartbeats.
  * Manages thread pools (`ExecutorService`) to handle concurrent peer queries safely.
  * Maintains thread-safe in-memory registries (`ConcurrentHashMap`) mapping each `fileHash` to active peer IP/port addresses.
  * Implements a background pruning thread that removes stale peers if their UDP heartbeat is missing for $> 15$ seconds.
* **Key Classes:** `TrackerServer.java`, `TrackerClientHandler.java`, `SwarmManager.java`, `HeartbeatListener.java`.

---

### Member 2: Wire Protocol, Network Framing & Security (Your Role)
* **Responsibilities:**
  * Designs and maintains the common communications library (`com.p2p.protocol`) imported by all other members.
  * Implements `ProtocolHandler.java` providing low-level TCP stream framing (`sendMessage` / `readMessage`).
  * Implements UDP packet serialization and parsing helpers for signaling and heartbeats.
  * Implements defensive cybersecurity controls:
    * Frame-size caps (`MAX_FRAME_SIZE = 2 MB`) to prevent memory exhaustion and DoS attacks.
    * Negative length validation to defend against integer underflow exploits.
    * Opcode whitelist validation to reject malformed command injection.
  * Provides byte serialization helper methods (`MessageSerializer.java`) for packaging strings, piece requests, and byte buffers.
* **Key Classes:** `MessageType.java`, `Message.java`, `ProtocolHandler.java`, `UdpPacketHelper.java`, `MessageSerializer.java`.

---

### Member 3: Peer Upload Server (Seeder Engine)
* **Responsibilities:**
  * Implements a background `ServerSocket` on each peer node listening for incoming connections from other peers.
  * Accepts and validates incoming `HANDSHAKE` messages from Leechers over TCP.
  * Transmits the local peer's `BITFIELD` (map of owned pieces).
  * Listens for `REQUEST_PIECE` frames, retrieves the requested binary chunk from Member 4's storage layer, and streams it back via `PIECE_DATA`.
  * Manages multi-threaded upload workers so the peer can upload to multiple leechers concurrently.
* **Key Classes:** `PeerServer.java`, `PeerUploadHandler.java`, `UploadManager.java`.

---

### Member 4: File Chunking, Cryptography & Disk I/O Engine
* **Responsibilities:**
  * Implements `FileChunker.java` to slice arbitrary local files into uniform 256 KB binary blocks.
  * Implements cryptographic SHA-256 hashing via `java.security.MessageDigest` to compute chunk-level and master file hashes.
  * Validates downloaded pieces against known hashes before writing to disk, rejecting corrupted or tampered bytes.
  * Implements `FileReassembler.java` to stitch all verified chunks sequentially into the final downloaded file.
* **Key Classes:** `FileChunker.java`, `PieceStorage.java`, `FileReassembler.java`, `HashVerifier.java`.

---

### Member 5: Parallel Download Orchestration Engine (Leecher Core)
* **Responsibilities:**
  * Implements `TrackerClient.java` to query active swarms from the Tracker.
  * Implements `DownloadManager.java` with a pool of worker threads to download missing pieces concurrently from multiple seeders over TCP.
  * Coordinates piece scheduling: determines which peer holds which missing chunk and balances download requests across the swarm.
  * Handles socket timeouts, dropped connections, and re-queuing failed piece requests to alternative peers.
  * Dispatches periodic UDP heartbeat pings to the Tracker to maintain active presence.
* **Key Classes:** `DownloadManager.java`, `TrackerClient.java`, `PieceDownloadWorker.java`, `HeartbeatEmitter.java`.

---

### Member 6: Desktop GUI & Real-Time Swarm Dashboard (Java Swing / JavaFX)
* **Responsibilities:**
  * Builds the interactive Desktop Graphical User Interface (using Java Swing or JavaFX) for the client application.
  * **Visual Chunk Matrix:** Displays a dynamic grid of boxes that change color in real-time as pieces download and verify (`[■][■][■][□]`).
  * **Live Swarm Peer Table:** Displays connected peer IP addresses, ports, individual piece progress, and status (Seeding / Leeching).
  * **Network Statistics & Speed Gauges:** Displays real-time download/upload transfer speeds (KB/s), elapsed time, and ETA.
  * **Real-Time Packet Event Console:** A live scrolling log window showing protocol events as they happen on the wire (e.g., `[TCP] REQUEST_PIECE #3 sent to 192.168.1.15`).
* **Key Classes:** `MainGuiWindow.java`, `ChunkGridPanel.java`, `PeerTablePanel.java`, `NetworkStatsPanel.java`.
