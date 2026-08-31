package com.mod.echo.hosting;

import com.mod.echo.EchoMod;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Asks the home router to forward a port automatically, via UPnP IGD (Internet
 * Gateway Device) — the same decades-old, unchanged standard game consoles and
 * Steam use for "no manual port forwarding needed". Unlike everything else in
 * {@code hosting}, this has nothing to do with Minecraft's own APIs: it is a
 * plain local-network protocol (SSDP discovery + a SOAP call), so it can be
 * implemented directly from the (stable, well-documented) spec rather than
 * guessed at.
 *
 * <h2>What this cannot fix</h2>
 * Two real things are outside any of this:
 * <ul>
 *   <li>UPnP has to be enabled on the router — most consumer routers ship
 *       with it on, but some ISPs or an administrator turn it off.</li>
 *   <li>Carrier-grade NAT (CGNAT): some ISPs (common on mobile data, some
 *       cable/DSL plans) never give the router a real public IP at all, in
 *       which case no port mapping on the router — UPnP or manual — makes
 *       this reachable from the real internet. {@link #detectCgnat()} checks
 *       for this by comparing what the router itself thinks its WAN IP is
 *       against what an outside service sees; a mismatch means CGNAT.</li>
 * </ul>
 */
public final class UpnpPortMapper {

    private UpnpPortMapper() {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public record Gateway(String controlUrl, String serviceType) {}

    /** Best-effort end-to-end: discover the router, ask it to forward the port. */
    public static String mapPort(int port) {
        try {
            Gateway gateway = discover();
            if (gateway == null) {
                return "No UPnP-capable router found on the network (it may be disabled) — "
                        + "forward port " + port + " manually if you want echo.net reachable from outside.";
            }
            String localIp = localAddress();
            addPortMapping(gateway, port, localIp);
            return "Asked the router to forward port " + port + " to this PC (UPnP) — no manual setup needed, "
                    + "if your router allows it.";
        } catch (Exception e) {
            EchoMod.LOGGER.debug("UPnP port mapping failed: {}", e.toString());
            return "Couldn't open the port automatically (" + e.getMessage() + ") — "
                    + "forward port " + port + " manually on your router if you want to be reachable from outside.";
        }
    }

    /** @return "cgnat" if the router's WAN IP doesn't match what the internet sees, "ok" if it matches, or
     *  "unknown" if either couldn't be determined. */
    public static String detectCgnat() {
        try {
            Gateway gateway = discover();
            if (gateway == null) return "unknown";
            String routerIp = getExternalIpAddress(gateway);
            String realIp = publicIpSeenByInternet();
            if (routerIp == null || realIp == null) return "unknown";
            return routerIp.equals(realIp) ? "ok" : "cgnat";
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------------ //
    //  SSDP discovery                                                      //
    // ------------------------------------------------------------------ //

    private static Gateway discover() throws Exception {
        String location = ssdpSearch();
        if (location == null) return null;
        return parseDeviceDescription(location);
    }

    private static String ssdpSearch() throws Exception {
        String request = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n";
        byte[] requestBytes = request.getBytes(StandardCharsets.UTF_8);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(3000);
            DatagramPacket packet = new DatagramPacket(requestBytes, requestBytes.length,
                    InetAddress.getByName("239.255.255.250"), 1900);
            socket.send(packet);

            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(response);
                } catch (Exception timeoutOrDone) {
                    break;
                }
                String text = new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8);
                for (String line : text.split("\r\n")) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("location:")) {
                        return line.substring(line.indexOf(':') + 1).trim();
                    }
                }
            }
        }
        return null;
    }

    private static Gateway parseDeviceDescription(String location) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(location))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) return null;

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(res.body().getBytes(StandardCharsets.UTF_8)));

        String urlBase = firstText(doc, "URLBase");
        String base = (urlBase != null && !urlBase.isBlank()) ? urlBase : baseOf(location);

        NodeList services = doc.getElementsByTagName("service");
        for (int i = 0; i < services.getLength(); i++) {
            Element service = (Element) services.item(i);
            String type = childText(service, "serviceType");
            if (type == null) continue;
            if (type.contains("WANIPConnection") || type.contains("WANPPPConnection")) {
                String control = childText(service, "controlURL");
                if (control == null) continue;
                String resolved = control.startsWith("http") ? control : joinUrl(base, control);
                return new Gateway(resolved, type);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ //
    //  SOAP actions                                                        //
    // ------------------------------------------------------------------ //

    private static void addPortMapping(Gateway gateway, int port, String localIp) throws Exception {
        String body = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:AddPortMapping xmlns:u=\"" + gateway.serviceType() + "\">"
                + "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + port + "</NewExternalPort>"
                + "<NewProtocol>TCP</NewProtocol>"
                + "<NewInternalPort>" + port + "</NewInternalPort>"
                + "<NewInternalClient>" + localIp + "</NewInternalClient>"
                + "<NewEnabled>1</NewEnabled>"
                + "<NewPortMappingDescription>echo.net</NewPortMappingDescription>"
                + "<NewLeaseDuration>0</NewLeaseDuration>"
                + "</u:AddPortMapping></s:Body></s:Envelope>";

        soapCall(gateway, "AddPortMapping", body);
    }

    private static String getExternalIpAddress(Gateway gateway) throws Exception {
        String body = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:GetExternalIPAddress xmlns:u=\"" + gateway.serviceType() + "\"></u:GetExternalIPAddress>"
                + "</s:Body></s:Envelope>";
        String response = soapCall(gateway, "GetExternalIPAddress", body);
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
        return firstText(doc, "NewExternalIPAddress");
    }

    private static String soapCall(Gateway gateway, String action, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(gateway.controlUrl()))
                .timeout(Duration.ofSeconds(6))
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("SOAPAction", "\"" + gateway.serviceType() + "#" + action + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("router replied HTTP " + res.statusCode() + " to " + action);
        }
        return res.body();
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    /** The LAN IP this machine would use to reach the internet — what the router should forward the port to. */
    private static String localAddress() throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 80);
            return socket.getLocalAddress().getHostAddress();
        }
    }

    private static String publicIpSeenByInternet() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.ipify.org"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() / 100 == 2 ? res.body().strip() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String baseOf(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getAuthority();
        } catch (Exception e) {
            return url;
        }
    }

    private static String joinUrl(String base, String path) {
        if (path.startsWith("/")) return base + path;
        return base + "/" + path;
    }

    private static String firstText(Document doc, String tag) {
        NodeList list = doc.getElementsByTagName(tag);
        if (list.getLength() == 0) return null;
        return list.item(0).getTextContent();
    }

    private static String childText(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tag.equals(child.getNodeName())) {
                return child.getTextContent();
            }
        }
        return null;
    }
}
