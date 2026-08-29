package com.mod.echo.schematic;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds and downloads a schematic file for a plain-language request, e.g.
 * "casa medieval bonita" — best-effort, since there is no real public API for
 * this. There is no documented, stable "search schematics" service to call, so
 * this does the same thing a person would: search the web and grab the first
 * link that looks like an actual schematic file.
 *
 * <p><b>Be honest about this one</b> — it is the least reliable piece of the
 * whole build system. It will often come back empty for anything obscure or
 * for sites that block automated requests, and it cannot judge whether a file
 * it found is actually the right building rather than just a file with a
 * matching name. Every miss says so plainly instead of pretending to have
 * built something. Successful downloads are cached under
 * {@code config/echo-schematics/}, which also doubles as the manual drop-in
 * folder for a schematic downloaded by hand.
 */
public final class SchematicFetcher {

    private SchematicFetcher() {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Pattern SCHEM_LINK =
            Pattern.compile("https?://[^\"'\\s<>]+\\.(?:schem|schematic)", Pattern.CASE_INSENSITIVE);

    public record Result(Schematic schematic, String source) {}

    public static Result fetch(String query) throws IOException {
        Path cacheDir = FabricLoader.getInstance().getConfigDir().resolve("echo-schematics");
        Files.createDirectories(cacheDir);
        String slug = slug(query);
        Path cached = cacheDir.resolve(slug + ".schem");

        if (Files.exists(cached)) {
            return new Result(Schematic.parse(Files.readAllBytes(cached)), "cached (" + cached.getFileName() + ")");
        }

        String url = findDownloadUrl(query);
        if (url == null) {
            throw new IOException("Couldn't find a downloadable .schem file for \"" + query + "\" with a plain "
                    + "web search. Try describing it differently, or drop a .schem file yourself at "
                    + "config/echo-schematics/" + slug + ".schem and ask again.");
        }

        byte[] bytes = download(url);
        Schematic schem;
        try {
            schem = Schematic.parse(bytes);
        } catch (IOException e) {
            throw new IOException("Found " + url + " but couldn't read it (" + e.getMessage()
                    + "). It may be a .litematic file, which isn't supported yet.");
        }
        Files.write(cached, bytes);
        return new Result(schem, url);
    }

    private static String findDownloadUrl(String query) throws IOException {
        String url = "https://duckduckgo.com/html/?q=" + encode(query + " minecraft schematic download .schem");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0 (compatible; EchoMinecraftMod/1.0)")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return null;
            Matcher m = SCHEM_LINK.matcher(res.body());
            return m.find() ? m.group() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Search was interrupted.");
        } catch (IOException e) {
            throw new IOException("Web search is unreachable right now (" + e.getMessage() + ").");
        }
    }

    private static byte[] download(String url) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (compatible; EchoMinecraftMod/1.0)")
                    .GET().build();
            HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 != 2) {
                throw new IOException("download failed with HTTP " + res.statusCode());
            }
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download was interrupted.");
        }
    }

    private static String slug(String query) {
        String s = query.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s.isBlank() ? "schematic" : s;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
