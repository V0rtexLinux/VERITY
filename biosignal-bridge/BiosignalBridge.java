// BiosignalBridge.java — standalone Java program, zero external dependencies.
//
// WHAT THIS IS
// A small local server that plugs into ECHO's biosignal contract
// (see echo/src/main/java/com/mod/echo/bio/BioSignal.java in the mod):
//
//   GET http://127.0.0.1:9727/state
//   { "connected": true, "focus": 0.62, "calm": 0.41, "updatedAtMs": 1735500000000 }
//
// HOW IT GETS DATA FROM THE HARDWARE
// This program does NOT talk to the OpenBCI Ganglion directly — implementing
// Bluetooth LE and OpenBCI's binary packet format from scratch, in pure Java,
// without being able to test against the real board or verify a library's
// exact API from here, is exactly the kind of "confident guess with no way
// to check it" that has caused real bugs earlier in this project. Instead it
// leans on software that already does that correctly: the official OpenBCI
// GUI application (openbci.com) connects to the Ganglion over Bluetooth, and
// its "Networking" tab/widget can stream processed data out over the network
// as OSC-over-UDP. This program is just a small OSC listener that turns that
// stream into the HTTP contract above.
//
// SETUP (do this once you have the hardware)
//   1. Install and open the official OpenBCI GUI, connect to the Ganglion
//      as normal, start the stream.
//   2. Open the Networking widget, set:
//        Protocol:  OSC
//        Data Type: Band Power  (sometimes labelled "Avg Band Power" /
//                   "FFT" depending on GUI version — pick whichever one
//                   outputs band powers, not raw time-series samples)
//        IP:        127.0.0.1
//        Port:      12345           (must match OSC_PORT below)
//   3. Run this file:  java BiosignalBridge.java
//   4. Check the console — every OSC message's address is logged the first
//      time it's seen. If ADDRESS_MATCH below does not match what your GUI
//      version actually sends, the log will show you the real address to
//      put there instead. This is a deliberate, honest gap: the OSC address
//      string is a GUI setting/version detail I cannot verify without the
//      actual software in front of me, so it is a single constant you may
//      need to correct once, not a guess buried somewhere you'd never find it.
//   5. In config/echo.json, set "bioSignalEnabled": true (bioSignalUrl's
//      default already matches this program's HTTP_PORT).
//
// WHAT "focus" AND "calm" MEAN
// Standard, widely-used neurofeedback heuristics, computed from the band
// powers the GUI sends:
//   focus = beta / (alpha + theta)   — higher when more alert/engaged
//   calm  = alpha / (alpha + beta)   — higher when more relaxed
// Both are raw ratios with no fixed upper bound, so they are squashed into
// 0..1 with x / (1 + x), and smoothed with a rolling average so one noisy
// sample does not swing ECHO's tone around.

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class BiosignalBridge {

    // ---- adjust these two if your setup differs -------------------------
    private static final int OSC_PORT = 12345;   // must match the GUI's Networking output port
    private static final int HTTP_PORT = 9727;   // must match EchoConfig.bioSignalUrl's port
    // The OSC address prefix the GUI's "Band Power" output uses. If the
    // startup log shows a different address, change this to match it.
    private static final String ADDRESS_MATCH = "/openbci/band-power";
    // -----------------------------------------------------------------

    /** How long a reading stays "fresh" before /state reports disconnected. */
    private static final long STALE_MS = 10_000;
    /** Exponential smoothing factor for focus/calm — lower = smoother, slower to react. */
    private static final double SMOOTHING = 0.25;

    private static final AtomicReference<double[]> LATEST = new AtomicReference<>(new double[]{0.5, 0.5});
    private static final AtomicLong LAST_UPDATE_MS = new AtomicLong(0);

    public static void main(String[] args) throws Exception {
        System.out.println("ECHO biosignal bridge starting.");
        System.out.println("  Listening for OSC band-power packets on UDP " + OSC_PORT);
        System.out.println("  Serving ECHO's /state contract on http://127.0.0.1:" + HTTP_PORT + "/state");
        System.out.println("  Waiting for the first OSC packet to confirm the address pattern...");

        Thread oscThread = new Thread(BiosignalBridge::runOscListener, "osc-listener");
        oscThread.setDaemon(true);
        oscThread.start();

        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", HTTP_PORT), 0);
        http.createContext("/state", new StateHandler());
        http.setExecutor(null);
        http.start();

        System.out.println("Bridge running. Press Ctrl+C to stop.");
    }

    // ------------------------------------------------------------------ //
    //  OSC / UDP listener                                                  //
    // ------------------------------------------------------------------ //

    private static void runOscListener() {
        boolean loggedAnyAddress = false;
        try (DatagramSocket socket = new DatagramSocket(OSC_PORT)) {
            byte[] buf = new byte[2048];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                try {
                    OscMessage msg = OscMessage.parse(packet.getData(), packet.getLength());
                    if (msg == null) continue;

                    if (!loggedAnyAddress) {
                        System.out.println("First OSC packet received, address = \"" + msg.address
                                + "\" with " + msg.floatArgs.length + " float argument(s).");
                        if (!msg.address.startsWith(ADDRESS_MATCH)) {
                            System.out.println("NOTE: this does not start with ADDRESS_MATCH (\""
                                    + ADDRESS_MATCH + "\") — edit that constant to match, then restart.");
                        }
                        loggedAnyAddress = true;
                    }

                    if (msg.address.startsWith(ADDRESS_MATCH) && msg.floatArgs.length >= 3) {
                        // Band order follows the GUI's standard theta/alpha/beta/... layout;
                        // adjust the indices below if your GUI reports a different order.
                        double theta = msg.floatArgs[0];
                        double alpha = msg.floatArgs[1];
                        double beta  = msg.floatArgs[2];
                        applyReading(theta, alpha, beta);
                    }
                } catch (Exception parseError) {
                    // Malformed or unrelated packet on the same port — ignore and keep listening.
                }
            }
        } catch (Exception e) {
            System.err.println("OSC listener stopped: " + e);
        }
    }

    private static void applyReading(double theta, double alpha, double beta) {
        double focusRatio = beta / Math.max(1e-6, alpha + theta);
        double calmRatio  = alpha / Math.max(1e-6, alpha + beta);
        double focus = squash(focusRatio);
        double calm  = squash(calmRatio);

        double[] prev = LATEST.get();
        double smoothedFocus = prev[0] + SMOOTHING * (focus - prev[0]);
        double smoothedCalm  = prev[1] + SMOOTHING * (calm  - prev[1]);
        LATEST.set(new double[]{smoothedFocus, smoothedCalm});
        LAST_UPDATE_MS.set(System.currentTimeMillis());
    }

    /** Maps any non-negative ratio into 0..1, smoothly, with 1.0 at ratio=1. */
    private static double squash(double ratio) {
        double r = Math.max(0, ratio);
        return r / (1.0 + r);
    }

    // ------------------------------------------------------------------ //
    //  HTTP: GET /state                                                    //
    // ------------------------------------------------------------------ //

    private static final class StateHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws java.io.IOException {
            long lastUpdate = LAST_UPDATE_MS.get();
            boolean connected = lastUpdate > 0
                    && (System.currentTimeMillis() - lastUpdate) <= STALE_MS;
            double[] reading = LATEST.get();

            String json = String.format(java.util.Locale.ROOT,
                    "{\"connected\":%s,\"focus\":%.3f,\"calm\":%.3f,\"updatedAtMs\":%d}",
                    connected, reading[0], reading[1], lastUpdate);

            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Minimal OSC 1.0 message parser (address + float32 args only)       //
    // ------------------------------------------------------------------ //

    private static final class OscMessage {
        final String address;
        final float[] floatArgs;

        private OscMessage(String address, float[] floatArgs) {
            this.address = address;
            this.floatArgs = floatArgs;
        }

        /** OSC strings/blobs are null-terminated and padded to a 4-byte boundary. */
        static OscMessage parse(byte[] data, int length) {
            int[] pos = {0};
            String address = readOscString(data, length, pos);
            if (address == null || !address.startsWith("/")) return null;

            String typeTags = readOscString(data, length, pos);
            if (typeTags == null || !typeTags.startsWith(",")) return new OscMessage(address, new float[0]);

            java.util.List<Float> floats = new java.util.ArrayList<>();
            for (int i = 1; i < typeTags.length(); i++) {
                char tag = typeTags.charAt(i);
                if (tag == 'f') {
                    if (pos[0] + 4 > length) break;
                    int bits = ((data[pos[0]] & 0xFF) << 24) | ((data[pos[0] + 1] & 0xFF) << 16)
                             | ((data[pos[0] + 2] & 0xFF) << 8) | (data[pos[0] + 3] & 0xFF);
                    floats.add(Float.intBitsToFloat(bits));
                    pos[0] += 4;
                } else if (tag == 'i') {
                    pos[0] += 4; // consume and ignore ints so later floats still parse correctly
                } else if (tag == 'T' || tag == 'F' || tag == 'N' || tag == 'I') {
                    // no bytes in the argument section for these types
                } else {
                    break; // unsupported tag (string/blob/etc.) — stop rather than misparse
                }
            }
            float[] out = new float[floats.size()];
            for (int i = 0; i < out.length; i++) out[i] = floats.get(i);
            return new OscMessage(address, out);
        }

        private static String readOscString(byte[] data, int length, int[] pos) {
            int start = pos[0];
            if (start >= length) return null;
            ByteArrayOutputStream sb = new ByteArrayOutputStream();
            int i = start;
            while (i < length && data[i] != 0) sb.write(data[i++]);
            if (i >= length) return null;
            int end = ((i / 4) + 1) * 4; // advance to the next 4-byte boundary past the null terminator
            pos[0] = Math.min(end, length);
            return sb.toString(StandardCharsets.UTF_8);
        }
    }
}
