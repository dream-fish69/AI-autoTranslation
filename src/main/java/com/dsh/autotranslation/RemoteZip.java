package com.dsh.autotranslation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Read-only zip over HTTP using range requests: fetches the central directory once, then only
 * the compressed bytes of the entries we actually need. Lets the mod pull a handful of mods out
 * of the 3 MB CFPA pack without downloading the whole thing.
 */
public final class RemoteZip {

    private static final int EOCD_SIG = 0x06054b50;
    private static final int CENTRAL_SIG = 0x02014b50;
    private static final int TAIL_BYTES = 128 * 1024;
    private static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024;

    public record Entry(String name, int method, long compressedSize, long localOffset) {
    }

    private final String url;
    private final long totalSize;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private HttpClient http;

    private RemoteZip(String url, long totalSize) {
        this.url = url;
        this.totalSize = totalSize;
    }

    /** @throws IOException when the server does not honour range requests. */
    public static RemoteZip open(String url) throws IOException {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        Response tail = fetch(http, url, "bytes=-" + TAIL_BYTES);
        byte[] data = tail.data();
        int eocd = findEocd(data);
        if (eocd < 0) {
            throw new IOException("remote zip: end-of-central-directory not found");
        }
        ByteBuffer eocdBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int centralSize = eocdBuffer.getInt(eocd + 12);
        long centralOffset = Integer.toUnsignedLong(eocdBuffer.getInt(eocd + 16));

        byte[] central;
        long tailStart = tail.total() - data.length;
        if (centralOffset >= tailStart) {
            int from = (int) (centralOffset - tailStart);
            int to = Math.min(data.length, from + centralSize);
            central = java.util.Arrays.copyOfRange(data, from, to);
        } else {
            central = fetch(http, url, "bytes=" + centralOffset + "-" + (centralOffset + centralSize - 1)).data();
        }

        RemoteZip zip = new RemoteZip(url, tail.total());
        zip.http = http;
        zip.parseCentral(central);
        return zip;
    }

    public long size() {
        return totalSize;
    }

    public Map<String, Entry> entries() {
        return entries;
    }

    public byte[] read(String name) throws IOException {
        Entry entry = entries.get(name);
        if (entry == null) {
            throw new IOException("remote zip: no entry " + name);
        }
        if (entry.compressedSize() > MAX_ENTRY_BYTES) {
            throw new IOException("remote zip: entry too big: " + name);
        }
        byte[] localHeader = fetch(http, url,
                "bytes=" + entry.localOffset() + "-" + (entry.localOffset() + 29)).data();
        int nameLength = u16(localHeader, 26);
        int extraLength = u16(localHeader, 28);
        long dataStart = entry.localOffset() + 30 + nameLength + extraLength;
        byte[] compressed = fetch(http, url,
                "bytes=" + dataStart + "-" + (dataStart + entry.compressedSize() - 1)).data();
        if (entry.method() == 0) {
            return compressed;
        }
        if (entry.method() == 8) {
            try (InflaterInputStream in = new InflaterInputStream(
                    new ByteArrayInputStream(compressed), new Inflater(true))) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                return out.toByteArray();
            }
        }
        throw new IOException("remote zip: unsupported compression method " + entry.method());
    }

    private void parseCentral(byte[] central) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(central).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.remaining() >= 46) {
            int start = buffer.position();
            if (buffer.getInt() != CENTRAL_SIG) {
                break;
            }
            buffer.position(start + 10);
            int method = Short.toUnsignedInt(buffer.getShort());
            buffer.position(start + 20);
            long compressedSize = Integer.toUnsignedLong(buffer.getInt());
            buffer.position(start + 28);
            int nameLength = Short.toUnsignedInt(buffer.getShort());
            int extraLength = Short.toUnsignedInt(buffer.getShort());
            int commentLength = Short.toUnsignedInt(buffer.getShort());
            buffer.position(start + 42);
            long localOffset = Integer.toUnsignedLong(buffer.getInt());
            byte[] nameBytes = new byte[nameLength];
            buffer.position(start + 46);
            buffer.get(nameBytes);
            String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
            entries.put(name, new Entry(name, method, compressedSize, localOffset));
            buffer.position(start + 46 + nameLength + extraLength + commentLength);
        }
    }

    private record Response(byte[] data, long total) {
    }

    private static Response fetch(HttpClient http, String url, String range) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "auto-translation/1.0")
                    .header("Range", range)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 206) {
                throw new IOException("remote zip: range not supported (HTTP " + response.statusCode() + ")");
            }
            long total = parseTotal(response.headers().firstValue("Content-Range").orElse(""));
            return new Response(response.body(), total);
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (Exception e) {
            throw new IOException("remote zip request failed: " + e.getMessage(), e);
        }
    }

    private static long parseTotal(String contentRange) {
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) {
            return -1;
        }
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int findEocd(byte[] data) {
        for (int i = data.length - 22; i >= 0; i--) {
            if (u32(data, i) == EOCD_SIG) {
                return i;
            }
        }
        return -1;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }
}
