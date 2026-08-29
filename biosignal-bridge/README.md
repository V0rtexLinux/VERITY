# ECHO biosignal bridge

A standalone Java program — **not part of the Fabric mod build**, does not
touch `echo/` or its Gradle project — that feeds a real EEG reading into
ECHO's optional biosignal feature (`com.mod.echo.bio.BioSignal` in the mod).

It has **zero external dependencies**: only classes built into the JDK
(`java.net.DatagramSocket`, `com.sun.net.httpserver.HttpServer`). No Maven,
no Gradle, nothing to download — verified by actually compiling and running
it against simulated data (see "How this was tested" below).

## Why it doesn't talk to the Ganglion directly

Implementing Bluetooth LE and OpenBCI's binary packet format from scratch,
in Java, without the actual board in hand to test against, would mean
shipping code nobody could verify until much later — the same kind of
unverified guess that has caused real bugs elsewhere in this project. So
this leans on software that already gets that part right: the official
**OpenBCI GUI** (openbci.com) already connects to the Ganglion over
Bluetooth and can stream processed data out over the network. This program
is just a small listener for that stream.

## Setup

1. Install the official OpenBCI GUI, connect to your Ganglion as normal,
   start the stream.
2. Open the **Networking** widget in the GUI and set:
   - Protocol: `OSC`
   - Data Type: whichever option outputs **band power** (labelled "Band
     Power", "Avg Band Power" or similar depending on GUI version — not raw
     time-series samples)
   - IP: `127.0.0.1`
   - Port: `12345` (must match `OSC_PORT` in `BiosignalBridge.java`)
3. Run the bridge — no build step needed, Java 11+ can run a `.java` file
   directly:
   ```
   java BiosignalBridge.java
   ```
4. Watch the console. The first OSC packet's address is logged automatically.
   If it doesn't start with what `ADDRESS_MATCH` in the source expects,
   the log tells you so — edit that one constant to match your GUI version
   and restart. This is a deliberately visible, one-line thing to check
   rather than a silent wrong guess: the exact OSC address string is a GUI
   setting I can't verify without the real software in front of me.
5. In the mod's `config/echo.json`, set `"bioSignalEnabled": true`. The
   default `bioSignalUrl` already points at this bridge's HTTP port.

## How this was tested

The OpenBCI hardware isn't available in the environment this was written in,
so the boundary that *can* be verified without it was tested directly:
compiled clean, started, and confirmed against simulated OSC packets (sent
with a throwaway Python script standing in for the GUI) that:

- `GET /state` reports `connected: false` with sane defaults before any
  packet arrives
- a correctly-addressed packet with theta/alpha/beta floats is parsed and
  produces the expected focus/calm values, smoothed correctly over
  successive packets
- a reading older than 10 seconds correctly reports `connected: false`
  again even though the bridge process itself is still running
- garbage / malformed UDP packets on the same port are ignored without
  crashing the listener

What was **not** verified, because it requires the real board: the exact
OSC address string and band ordering the OpenBCI GUI actually sends, and
end-to-end behaviour with a real Ganglion over Bluetooth. Step 4 above
exists specifically to catch a mismatch there quickly and visibly.
