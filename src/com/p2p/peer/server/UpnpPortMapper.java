package com.p2p.peer.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort UPnP IGD (Internet Gateway Device) port mapper.
 * Attempts to open a TCP port on the local router so peers on the internet
 * can reach this node without manual router configuration.
 */
public final class UpnpPortMapper {

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final int TIMEOUT_MS = 2000;

    private UpnpPortMapper() {}

    /**
     * Attempts to map {@code port} (TCP) on the local UPnP router.
     *
     * @return a human-readable status message describing the outcome
     */
    public static String tryForwardTcpPort(int port) {
        try {
            String location = discoverGatewayLocation();
            if (location == null) {
                return "No UPnP gateway found (manual port forwarding may be needed for internet peers).";
            }

            String xml = httpGet(location);
            String serviceType = findWanServiceType(xml);
            String controlPath = findControlUrl(xml, serviceType);
            if (serviceType == null || controlPath == null) {
                return "UPnP gateway found, but WANIPConnection/WANPPPConnection service is unavailable.";
            }

            URL base = URI.create(location).toURL();
            URL controlUrl = controlPath.startsWith("http")
                    ? URI.create(controlPath).toURL()
                    : new URL(base.getProtocol(), base.getHost(), base.getPort(),
                    controlPath.startsWith("/") ? controlPath : "/" + controlPath);

            String localIp = InetAddress.getLocalHost().getHostAddress();
            sendAddPortMapping(controlUrl, serviceType, port, localIp);
            return "UPnP forwarded TCP port " + port + " -> " + localIp + ":" + port;
        } catch (Exception e) {
            return "UPnP port forwarding skipped: " + e.getMessage();
        }
    }

    private static String discoverGatewayLocation() throws IOException {
        String search = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n\r\n";

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            byte[] req = search.getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(req, req.length,
                    InetAddress.getByName(SSDP_ADDR), SSDP_PORT));

            byte[] buf = new byte[2048];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            socket.receive(resp);
            String reply = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.US_ASCII);
            Matcher m = Pattern.compile("(?im)^LOCATION:\\s*(\\S+)").matcher(reply);
            return m.find() ? m.group(1).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String httpGet(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static String findWanServiceType(String xml) {
        if (xml.contains("urn:schemas-upnp-org:service:WANIPConnection:1")) {
            return "urn:schemas-upnp-org:service:WANIPConnection:1";
        }
        if (xml.contains("urn:schemas-upnp-org:service:WANPPPConnection:1")) {
            return "urn:schemas-upnp-org:service:WANPPPConnection:1";
        }
        return null;
    }

    private static String findControlUrl(String xml, String serviceType) {
        if (serviceType == null) return null;
        int idx = xml.indexOf(serviceType);
        if (idx < 0) return null;
        int end = xml.indexOf("</service>", idx);
        if (end < 0) end = xml.length();
        String block = xml.substring(idx, end);
        Matcher m = Pattern.compile("<controlURL>([^<]+)</controlURL>").matcher(block);
        return m.find() ? m.group(1).trim() : null;
    }

    private static void sendAddPortMapping(URL controlUrl, String serviceType,
                                           int port, String internalIp) throws IOException {
        String body = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:AddPortMapping xmlns:u=\"" + serviceType + "\">"
                + "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + port + "</NewExternalPort>"
                + "<NewProtocol>TCP</NewProtocol>"
                + "<NewInternalPort>" + port + "</NewInternalPort>"
                + "<NewInternalClient>" + internalIp + "</NewInternalClient>"
                + "<NewEnabled>1</NewEnabled>"
                + "<NewPortMappingDescription>BitTorrent-Lite</NewPortMappingDescription>"
                + "<NewLeaseDuration>0</NewLeaseDuration>"
                + "</u:AddPortMapping></s:Body></s:Envelope>";

        HttpURLConnection conn = (HttpURLConnection) controlUrl.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        conn.setRequestProperty("SOAPAction", "\"" + serviceType + "#AddPortMapping\"");

        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Router returned HTTP " + status);
        }
    }
}
