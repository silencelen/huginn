// Prototype Q1 (docs/CORE-ARCHITECTURE.md §11): can the JVM GUI speak HTTP to the
// core over AF_UNIX through OkHttp 4.12.0 (the engine under the app's Ktor client)?
//
// The whole trick is CoreSocket: a java.net.Socket whose connect() ignores the
// InetSocketAddress OkHttp hands it and opens the core's socket file instead.
// OkHttp never knows; Ktor would get it through `engine { preconfigured = … }`.
//
// ⚠ TWO JDK TRAPS shape the adapter, and both would hang the app, not fail it:
//  1. Channels.newInputStream/newOutputStream on a SocketChannel take the
//     channel's blockingLock() for every read AND write (JDK 17), so one thread
//     blocked in read() stops every write. The streams below call read/write on
//     the channel directly; SocketChannelImpl has separate read and write locks.
//  2. A blocking SocketChannel ignores SO_TIMEOUT. OkHttp's pool health check
//     (RealConnection.isHealthy → Socket.isHealthy) sets soTimeout=1 and reads;
//     on an ignoring socket that "1 ms" read never returns. So the channel is
//     non-blocking and every read waits on a Selector for at most soTimeout.
//
// Run: see run.sh. Prints one PASS/FAIL line per case, exits non-zero on any FAIL.

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.SocketFactory;
import okhttp3.*;
import okio.BufferedSink;
import okio.BufferedSource;

public class UnixSocketProbe {

    static final class CoreSocket extends Socket {
        private final Path path;
        private SocketChannel ch;
        private Selector readSel, writeSel;
        private volatile int soTimeout;
        private volatile boolean closed, inShut, outShut;

        CoreSocket(Path path) { this.path = path; }

        @Override public void connect(SocketAddress ignored, int timeout) throws IOException {
            ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            ch.connect(UnixDomainSocketAddress.of(path));   // local: completes or fails at once
            ch.configureBlocking(false);
            readSel = Selector.open();
            writeSel = Selector.open();
            ch.register(readSel, SelectionKey.OP_READ);
            ch.register(writeSel, SelectionKey.OP_WRITE);
        }
        @Override public void connect(SocketAddress a) throws IOException { connect(a, 0); }

        @Override public boolean isConnected() { return ch != null && ch.isConnected(); }
        @Override public boolean isClosed() { return closed; }
        @Override public boolean isInputShutdown() { return inShut; }
        @Override public boolean isOutputShutdown() { return outShut; }
        @Override public void setSoTimeout(int t) { soTimeout = t; }
        @Override public int getSoTimeout() { return soTimeout; }
        @Override public void setTcpNoDelay(boolean on) { }
        @Override public void setKeepAlive(boolean on) { }
        // OkHttp logs/compares these; a fixed loopback answer is honest enough.
        @Override public InetAddress getInetAddress() { return InetAddress.getLoopbackAddress(); }
        @Override public InetAddress getLocalAddress() { return InetAddress.getLoopbackAddress(); }
        @Override public int getPort() { return 0; }
        @Override public int getLocalPort() { return 0; }
        @Override public SocketAddress getRemoteSocketAddress() { return new InetSocketAddress(getInetAddress(), 0); }

        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            // Okio's timeout watchdog closes the socket from another thread to
            // break a blocked read; wake both selectors so that read sees it.
            try { readSel.wakeup(); writeSel.wakeup(); } catch (Exception ignored) { }
            try { ch.close(); } finally {
                try { readSel.close(); } catch (Exception ignored) { }
                try { writeSel.close(); } catch (Exception ignored) { }
            }
        }
        @Override public void shutdownInput() throws IOException { inShut = true; ch.shutdownInput(); }
        @Override public void shutdownOutput() throws IOException { outShut = true; ch.shutdownOutput(); }

        private final InputStream in = new InputStream() {
            @Override public int read() throws IOException {
                byte[] b = new byte[1];
                int n = read(b, 0, 1);
                return n < 0 ? -1 : (b[0] & 0xff);
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                ByteBuffer bb = ByteBuffer.wrap(b, off, len);
                long deadline = soTimeout > 0 ? System.nanoTime() + soTimeout * 1_000_000L : Long.MAX_VALUE;
                // ⚠ A close from another thread (Okio's timeout watchdog, or
                // Call.cancel() when a view drops its stream) surfaces here as
                // ClosedSelectorException — UNCHECKED, so it would escape every
                // `catch (IOException)` in OkHttp and kill the caller's thread.
                // Translate it, and its channel twin, into the SocketException
                // a real socket throws. Found by case 8 of this probe.
                try {
                    while (true) {
                        if (closed) throw new SocketException("Socket closed");
                        int n = ch.read(bb);
                        if (n != 0) return n;                        // data, or -1 at EOF
                        long left = deadline == Long.MAX_VALUE ? 0 : (deadline - System.nanoTime()) / 1_000_000L;
                        if (deadline != Long.MAX_VALUE && left <= 0) throw new SocketTimeoutException("Read timed out");
                        readSel.select(deadline == Long.MAX_VALUE ? 0 : Math.max(1, left));
                        readSel.selectedKeys().clear();
                    }
                } catch (ClosedSelectorException | ClosedChannelException e) {
                    throw new SocketException("Socket closed");
                }
            }
        };
        private final OutputStream out = new OutputStream() {
            @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                ByteBuffer bb = ByteBuffer.wrap(b, off, len);
                try {
                    while (bb.hasRemaining()) {
                        if (closed) throw new SocketException("Socket closed");
                        if (ch.write(bb) == 0) { writeSel.select(); writeSel.selectedKeys().clear(); }
                    }
                } catch (ClosedSelectorException | ClosedChannelException e) {
                    throw new SocketException("Socket closed");
                }
            }
        };
        @Override public InputStream getInputStream() { return in; }
        @Override public OutputStream getOutputStream() { return out; }
    }

    static final class CoreSocketFactory extends SocketFactory {
        final Path path;
        CoreSocketFactory(Path p) { path = p; }
        @Override public Socket createSocket() { return new CoreSocket(path); }
        @Override public Socket createSocket(String h, int p) throws IOException { Socket s = createSocket(); s.connect(null); return s; }
        @Override public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { return createSocket(h, p); }
        @Override public Socket createSocket(InetAddress a, int p) throws IOException { return createSocket("", p); }
        @Override public Socket createSocket(InetAddress a, int p, InetAddress l, int lp) throws IOException { return createSocket("", p); }
    }

    // ---- the cases -------------------------------------------------------------

    static int fails = 0;
    static void report(String name, boolean ok, String detail) {
        System.out.printf("%s  %-34s %s%n", ok ? "PASS" : "FAIL", name, detail);
        if (!ok) fails++;
    }

    static final String BASE = "http://huginn-core.invalid";   // never resolved: Dns below
    static final AtomicInteger connects = new AtomicInteger();

    static OkHttpClient client(Path sock, long readTimeoutMs) {
        return new OkHttpClient.Builder()
            .socketFactory(new CoreSocketFactory(sock))
            .dns(host -> List.of(InetAddress.getLoopbackAddress()))   // no DNS lookup for the fake host
            .proxy(Proxy.NO_PROXY)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                .header("X-Huginn-Core-Proto", "1").build()))
            .eventListener(new okhttp3.EventListener() {
                @Override public void connectStart(Call c, InetSocketAddress a, Proxy p) { connects.incrementAndGet(); }
            })
            .build();
    }

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { e.printStackTrace(); System.exit(2); });
        Path sock = Path.of(args[0]);
        OkHttpClient c = client(sock, 5_000);

        // 1. small JSON, proto header present
        try (Response r = c.newCall(new Request.Builder().url(BASE + "/core/status").build()).execute()) {
            String body = r.body().string();
            report("status JSON", r.code() == 200 && body.contains("\"proto\":\"1\""), r.code() + " " + body.trim());
        }

        // 2. no proto header -> 426 with the fix in the body
        OkHttpClient bare = new OkHttpClient.Builder()
            .socketFactory(new CoreSocketFactory(sock))
            .dns(host -> List.of(InetAddress.getLoopbackAddress())).proxy(Proxy.NO_PROXY).build();
        try (Response r = bare.newCall(new Request.Builder().url(BASE + "/core/status").build()).execute()) {
            String body = r.body().string();
            report("proto mismatch -> 426", r.code() == 426 && body.contains("huginn update"), r.code() + " " + body.trim());
        }

        // 3. SSE arrives event by event, not buffered to the end
        {
            int n = 6, gap = 250;
            List<Long> at = new ArrayList<>();
            long t0 = System.currentTimeMillis();
            try (Response r = c.newCall(new Request.Builder().url(BASE + "/core/events?n=" + n + "&gapms=" + gap).build()).execute()) {
                BufferedSource src = r.body().source();
                String line;
                while ((line = src.readUtf8Line()) != null) {
                    if (line.startsWith("data: {\"i\"")) at.add(System.currentTimeMillis() - t0);
                    if (line.equals("event: bye")) break;
                }
            }
            long spread = at.isEmpty() ? 0 : at.get(at.size() - 1) - at.get(0);
            report("SSE incremental", at.size() == n && spread >= (long) gap * (n - 1) * 8 / 10,
                "events=" + at.size() + " arrival(ms)=" + at);
        }

        // 4. 64 MiB streamed upload, sha256 matches
        {
            final int MB = 64;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] chunk = new byte[1 << 20];
            new Random(7).nextBytes(chunk);
            for (int i = 0; i < MB; i++) md.update(chunk);
            String want = HexFormat.of().formatHex(md.digest());
            RequestBody rb = new RequestBody() {
                @Override public MediaType contentType() { return MediaType.get("application/octet-stream"); }
                @Override public long contentLength() { return -1; }   // chunked, like a streamed attachment
                @Override public void writeTo(BufferedSink sink) throws IOException { for (int i = 0; i < MB; i++) sink.write(chunk); }
            };
            long t0 = System.nanoTime();
            try (Response r = c.newCall(new Request.Builder().url(BASE + "/core/upload").post(rb).build()).execute()) {
                String body = r.body().string();
                double s = (System.nanoTime() - t0) / 1e9;
                report("64 MiB chunked upload", body.contains(want), String.format("%.2fs %.0f MiB/s", s, MB / s));
            }
        }

        // 5. 256 MiB streamed download
        {
            long t0 = System.nanoTime(), got = 0;
            try (Response r = c.newCall(new Request.Builder().url(BASE + "/core/download?mb=256").build()).execute()) {
                BufferedSource src = r.body().source();
                okio.Buffer buf = new okio.Buffer();
                long n;
                while ((n = src.read(buf, 1 << 16)) != -1) { got += n; buf.clear(); }
            }
            double s = (System.nanoTime() - t0) / 1e9;
            report("256 MiB download", got == 256L << 20, String.format("%d bytes %.2fs %.0f MiB/s", got, s, 256 / s));
        }

        // 6. pooled connection reused across GET -> POST (exercises isHealthy's 1 ms read)
        {
            OkHttpClient fresh = client(sock, 5_000);
            connects.set(0);
            boolean ok = true;
            for (int i = 0; i < 5; i++) {
                Request req = i % 2 == 0
                    ? new Request.Builder().url(BASE + "/core/status").build()
                    : new Request.Builder().url(BASE + "/core/upload").post(RequestBody.create(new byte[1024], null)).build();
                long t0 = System.nanoTime();
                try (Response r = fresh.newCall(req).execute()) { r.body().string(); ok &= r.code() == 200; }
                ok &= (System.nanoTime() - t0) < 2_000_000_000L;   // a hung health check would blow past this
            }
            report("pool reuse + health check", ok && connects.get() == 1, "connects=" + connects.get() + " for 5 calls");
        }

        // 7. read timeout fires on a quiet stream instead of hanging
        {
            OkHttpClient shortT = client(sock, 300);
            long t0 = System.currentTimeMillis();
            String what;
            try (Response r = shortT.newCall(new Request.Builder().url(BASE + "/core/events?n=3&gapms=2000").build()).execute()) {
                BufferedSource src = r.body().source();
                while (src.readUtf8Line() != null) { }
                what = "no timeout";
            } catch (SocketTimeoutException e) {
                what = "SocketTimeoutException";
            } catch (IOException e) {
                what = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            long took = System.currentTimeMillis() - t0;
            report("read timeout on quiet stream", what.equals("SocketTimeoutException") && took < 2_000, what + " after " + took + " ms");
        }

        // 8. cancel an SSE mid-stream from another thread (the GUI closing a view)
        {
            Call call = c.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
                .newCall(new Request.Builder().url(BASE + "/core/events?n=100&gapms=200").build());
            ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();
            ex.schedule(call::cancel, 700, TimeUnit.MILLISECONDS);
            long t0 = System.currentTimeMillis();
            int events = 0;
            String how = "ended";
            try (Response r = call.execute()) {
                BufferedSource src = r.body().source();
                String line;
                while ((line = src.readUtf8Line()) != null) if (line.startsWith("data: {\"i\"")) events++;
            } catch (IOException e) {
                how = e.getClass().getSimpleName();
            }
            ex.shutdown();
            long took = System.currentTimeMillis() - t0;
            report("cancel mid-stream", took < 1_500 && events >= 2 && events < 100, how + " after " + took + " ms, events=" + events);
        }

        // 9. 16 calls in parallel (the app's poll fans out several at once)
        {
            ExecutorService pool = Executors.newFixedThreadPool(16);
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 16; i++)
                fs.add(pool.submit(() -> {
                    try (Response r = c.newCall(new Request.Builder().url(BASE + "/appd/v1/chats?i=1").build()).execute()) {
                        return r.body().string().contains("\"path\":\"/v1/chats\"") ? 1 : 0;
                    }
                }));
            int okN = 0;
            for (Future<Integer> f : fs) okN += f.get(10, TimeUnit.SECONDS);
            pool.shutdown();
            report("16 parallel proxy-shaped calls", okN == 16, okN + "/16");
        }

        // 10. a client-sent Authorization never survives the proxy hop
        try (Response r = c.newCall(new Request.Builder().url(BASE + "/appd/v1/status")
                .header("Authorization", "Bearer should-be-stripped").build()).execute()) {
            String body = r.body().string();
            report("Authorization stripped", body.contains("\"sawAuthorization\":false"), body.trim());
        }

        c.dispatcher().executorService().shutdown();
        c.connectionPool().evictAll();
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
