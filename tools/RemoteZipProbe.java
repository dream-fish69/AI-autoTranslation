import com.dsh.autotranslation.RemoteZip;

/** Tests RemoteZip against a range-capable HTTP server. */
public final class RemoteZipProbe {

    public static void main(String[] args) throws Exception {
        long start = System.currentTimeMillis();
        RemoteZip zip = RemoteZip.open(args[0]);
        System.out.printf("size=%d entries=%d in %d ms%n",
                zip.size(), zip.entries().size(), System.currentTimeMillis() - start);
        String entry = args.length > 1 ? args[1] : "assets/xaeroworldmap/lang/zh_cn.json";
        byte[] data = zip.read(entry);
        String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        System.out.printf("read %s -> %d bytes, head=%s%n",
                entry, data.length, text.substring(0, Math.min(80, text.length())));
    }
}
