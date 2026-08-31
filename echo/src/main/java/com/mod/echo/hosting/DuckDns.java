package com.mod.echo.hosting;

import com.mod.echo.EchoMod;
import com.mod.echo.config.EchoConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Keeps a free DuckDNS subdomain (duckdns.org) pointed at this machine's
 * current public IP — the free, no-credit-card alternative to actually
 * owning a domain for reaching echo.net from outside the LAN.
 *
 * <p>DuckDNS's own update endpoint infers the caller's public IP from the
 * request itself when no {@code ip} parameter is given, which is exactly
 * right here: the request leaves this machine through the same NAT/router
 * a real player's connection would, so whatever DuckDNS sees is genuinely
 * the address to reach this server at (or would be, once the port is
 * actually forwarded — see {@link UpnpPortMapper}).
 *
 * <p>Setup (one-time, done by the player, not this mod): create a free
 * account at duckdns.org, add a subdomain, and put its name and token into
 * {@code echo set duckdns <name>} / {@code echo set duckdns_token <token>}.
 */
public final class DuckDns {

    private DuckDns() {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public static boolean isConfigured() {
        EchoConfig cfg = EchoConfig.get();
        return !cfg.duckDnsSubdomain.isBlank() && !cfg.duckDnsToken.isBlank();
    }

    public static String domain() {
        return EchoConfig.get().duckDnsSubdomain.strip() + ".duckdns.org";
    }

    /** Best-effort. Returns a short human-readable result; never throws. */
    public static String update() {
        EchoConfig cfg = EchoConfig.get();
        if (!isConfigured()) return "DuckDNS isn't configured (set duckdns and duckdns_token).";
        try {
            String url = "https://www.duckdns.org/update?domains=" + cfg.duckDnsSubdomain.strip()
                    + "&token=" + cfg.duckDnsToken.strip() + "&ip=";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String body = res.body() == null ? "" : res.body().strip();
            if (res.statusCode() / 100 == 2 && body.startsWith("OK")) {
                return domain() + " now points at this machine's public IP.";
            }
            return "DuckDNS update failed (HTTP " + res.statusCode() + ", replied \"" + body + "\") — "
                    + "double-check the subdomain name and token.";
        } catch (Exception e) {
            EchoMod.LOGGER.debug("DuckDNS update failed: {}", e.toString());
            return "Couldn't reach DuckDNS: " + e.getMessage();
        }
    }
}
