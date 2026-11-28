package com.p2p.tracker;

import com.p2p.protocol.Message;
import com.p2p.protocol.MessageSerializer;
import com.p2p.protocol.ProtocolException;
import com.p2p.protocol.ProtocolHandler;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles one TCP peer connection to the tracker.
 */
public class TrackerClientHandler implements Runnable {

    private static final int ERROR_CODE_MALFORMED_REQUEST = 400;
    private static final int ERROR_CODE_INVALID_REQUEST = 401;
    private static final int ERROR_CODE_UNSUPPORTED_REQUEST = 402;

    private final Socket socket;
    private final SwarmManager swarmManager;
    private final ProtocolHandler protocolHandler;

    public TrackerClientHandler(Socket socket, SwarmManager swarmManager) {
        if (socket == null) {
            throw new IllegalArgumentException("Socket must not be null");
        }
        if (swarmManager == null) {
            throw new IllegalArgumentException("SwarmManager must not be null");
        }
        this.socket = socket;
        this.swarmManager = swarmManager;
        this.protocolHandler = new ProtocolHandler();
    }

    @Override
    public void run() {
        try (Socket connection = socket;
             DataInputStream in = ProtocolHandler.wrapInput(connection);
             DataOutputStream out = ProtocolHandler.wrapOutput(connection)) {

            while (true) {
                final Message request;
                try {
                    request = protocolHandler.readMessage(in);
                } catch (EOFException eof) {
                    break;
                } catch (ProtocolException e) {
                    sendError(out, ERROR_CODE_MALFORMED_REQUEST, e.getMessage());
                    break;
                } catch (IOException e) {
                    break;
                }

                try {
                    handleRequest(request, out);
                } catch (ProtocolException e) {
                    sendError(out, ERROR_CODE_MALFORMED_REQUEST, e.getMessage());
                } catch (IllegalArgumentException e) {
                    sendError(out, ERROR_CODE_INVALID_REQUEST, e.getMessage());
                } catch (IOException e) {
                    break;
                }
            }
        } catch (IOException | ProtocolException e) {
            // Stream setup or final socket cleanup failed; the connection is closed by
            // the try-with-resources block.
        }
    }

    private void handleRequest(Message request, DataOutputStream out)
            throws IOException, ProtocolException {
        switch (request.getType()) {
            case REGISTER_FILE:
                handleRegisterFile(request, out);
                break;
            case GET_PEERS:
                handleGetPeers(request, out);
                break;
            case UNREGISTER_FILE:
                handleUnregisterFile(request);
                break;
            default:
                sendError(
                    out,
                    ERROR_CODE_UNSUPPORTED_REQUEST,
                    "Unsupported request: " + request.getType()
                );
                break;
        }
    }

    private void handleRegisterFile(Message request, DataOutputStream out)
            throws IOException, ProtocolException {
        MessageSerializer.RegisterFileData data =
            MessageSerializer.parseRegisterFile(request.getPayload());

        validateFileHash(data.fileHash);
        validateFileName(data.fileName);
        validateTcpPort(data.peerPort);
        if (data.fileSize < 0) {
            throw new IllegalArgumentException("File size must not be negative");
        }
        if (data.pieceCount < 0) {
            throw new IllegalArgumentException("Piece count must not be negative");
        }

        String peerIp = socket.getInetAddress().getHostAddress();
        PeerInfo peerInfo = new PeerInfo(peerIp, data.peerPort, "", new byte[0]);
        swarmManager.registerPeer(data.fileHash, peerInfo);

        Message response = MessageSerializer.createRegisterSuccess(
            data.fileHash,
            "File registered successfully"
        );
        protocolHandler.sendMessage(out, response);
    }

    private void handleGetPeers(Message request, DataOutputStream out)
            throws IOException, ProtocolException {
        String fileHash = MessageSerializer.parseGetPeers(request.getPayload());
        validateFileHash(fileHash);

        List<PeerInfo> registeredPeers = swarmManager.getPeers(fileHash);
        List<MessageSerializer.PeerEntry> entries =
            new ArrayList<>(registeredPeers.size());

        for (PeerInfo peerInfo : registeredPeers) {
            entries.add(new MessageSerializer.PeerEntry(
                peerInfo.getIpAddress(),
                peerInfo.getTcpPort(),
                peerInfo.getBitfield()
            ));
        }

        Message response =
            MessageSerializer.createPeerListResponse(fileHash, entries);
        protocolHandler.sendMessage(out, response);
    }

    private void handleUnregisterFile(Message request)
            throws IOException, ProtocolException {
        MessageSerializer.UnregisterFileData data =
            MessageSerializer.parseUnregisterFile(request.getPayload());

        validateFileHash(data.fileHash);
        validateTcpPort(data.peerPort);

        String peerIp = socket.getInetAddress().getHostAddress();
        swarmManager.unregisterPeer(data.fileHash, peerIp, data.peerPort);
    }

    private void sendError(DataOutputStream out, int errorCode, String message)
            throws IOException, ProtocolException {
        String safeMessage =
            (message == null || message.trim().isEmpty())
                ? "Invalid request"
                : message;
        protocolHandler.sendMessage(
            out,
            MessageSerializer.createError(errorCode, safeMessage)
        );
    }

    private static void validateFileHash(String fileHash) {
        if (fileHash == null || fileHash.trim().isEmpty()) {
            throw new IllegalArgumentException("File hash must not be empty");
        }
    }

    private static void validateFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("File name must not be empty");
        }
    }

    private static void validateTcpPort(int tcpPort) {
        if (tcpPort < 1 || tcpPort > 65535) {
            throw new IllegalArgumentException("TCP port must be between 1 and 65535");
        }
    }
}
