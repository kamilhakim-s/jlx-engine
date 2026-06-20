package com.lowlatency.marketdata;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Pulls <b>bulk historical</b> aggregate trades from Binance's public data archive
 * (<a href="https://data.binance.vision">data.binance.vision</a>) — the "large dataset" source. Each
 * daily file is a ZIP of CSV holding <i>millions</i> of trades for one symbol, free and unauthenticated.
 *
 * <p>The download is cached on disk (under {@code data/}); parsing <b>streams</b> the ZIP line by line
 * and pushes each {@link AggTrade} to a consumer, so memory stays flat no matter how big the file is —
 * we never hold the whole day in memory. That streaming discipline is the whole point: you can pull
 * gigabytes and feed them straight through the engine.
 *
 * <p>This is the REST/bulk half of Chunk 4; {@link BinanceLiveClient} is the live half. Both emit the
 * same normalised {@link AggTrade}, so downstream code is identical for replay and live.
 */
public final class BinanceHistoricalDownloader {

    private static final String BASE = "https://data.binance.vision/data/spot/daily/aggTrades";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final Path cacheDir;
    private final AggTradeCsvParser parser = new AggTradeCsvParser();

    public BinanceHistoricalDownloader() {
        this(Path.of("data", "binance"));
    }

    public BinanceHistoricalDownloader(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /** Returns the local path to the day's ZIP, downloading it (once) if not already cached. */
    public Path download(String symbol, LocalDate date) throws IOException, InterruptedException {
        String file = symbol + "-aggTrades-" + date + ".zip";
        Path target = cacheDir.resolve(symbol).resolve(file);
        if (Files.exists(target) && Files.size(target) > 0) {
            return target;
        }
        Files.createDirectories(target.getParent());

        URI uri = URI.create(BASE + "/" + symbol + "/" + file);
        Path tmp = Files.createTempFile(target.getParent(), file, ".part");
        HttpResponse<Path> response = http.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofFile(tmp));
        if (response.statusCode() == 404) {
            Files.deleteIfExists(tmp);
            throw new FileNotFoundException("No Binance data for " + symbol + " on " + date
                    + " (not published yet, or wrong symbol/date): " + uri);
        }
        if (response.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("Download failed (HTTP " + response.statusCode() + "): " + uri);
        }
        Files.move(tmp, target);
        return target;
    }

    /**
     * Streams every {@link AggTrade} in the ZIP to {@code consumer}, stopping after {@code maxTrades}
     * (use {@link Long#MAX_VALUE} for "all").
     *
     * @return number of trades emitted
     */
    public long stream(Path zip, SymbolScale scale, long maxTrades, Consumer<AggTrade> consumer)
            throws IOException {
        long count = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry = zis.getNextEntry();
            if (entry == null) {
                throw new IOException("Empty zip: " + zip);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
            String line;
            while (count < maxTrades && (line = reader.readLine()) != null) {
                AggTrade trade = parser.parseLine(line, scale);
                if (trade != null) {
                    consumer.accept(trade);
                    count++;
                }
            }
        }
        return count;
    }

    /** Convenience: download (if needed) and stream a day in one call. */
    public long streamDay(String symbol, LocalDate date, SymbolScale scale,
                          long maxTrades, Consumer<AggTrade> consumer)
            throws IOException, InterruptedException {
        return stream(download(symbol, date), scale, maxTrades, consumer);
    }
}
