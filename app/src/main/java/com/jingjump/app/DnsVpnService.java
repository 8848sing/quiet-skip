package com.jingjump.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class DnsVpnService extends VpnService {
    static final String ACTION_START = "com.jingjump.app.action.DNS_START";
    static final String ACTION_STOP = "com.jingjump.app.action.DNS_STOP";
    static final String ACTION_RELOAD = "com.jingjump.app.action.DNS_RELOAD";

    private static final String CHANNEL_ID = "dns_filter";
    private static final int NOTIFICATION_ID = 1201;
    private static final String TUN_ADDRESS = "10.111.222.1";
    private static final int TUN_PREFIX = 32;
    private static final String TUN_V6_ADDRESS = "fd11:1122:3344::1";
    private static final int TUN_V6_PREFIX = 64;
    private static final int TUN_MTU = 1500;
    private static final int DNS_TIMEOUT_MS = 900;
    /** Largest DNS payload that still fits one MTU-sized datagram. */
    static final int MAX_UDP_PAYLOAD_V4 = TUN_MTU - 20 - 8;
    static final int MAX_UDP_PAYLOAD_V6 = TUN_MTU - 40 - 8;
    private static final int UPSTREAM_THREADS = 8;
    private static final int UPSTREAM_QUEUE = 96;
    private static final int TCP_CONNECT_TIMEOUT_MS = 3_000;
    private static final int TCP_READ_TIMEOUT_MS = 4_000;
    private static final String PRIMARY_DNS = "223.5.5.5";
    private static final String SECONDARY_DNS = "119.29.29.29";
    private static final List<String> UPSTREAM_SERVERS = Collections.unmodifiableList(Arrays.asList(
            PRIMARY_DNS,
            SECONDARY_DNS
    ));
    /**
     * Public resolvers that get a /32 (or /128) route through the tunnel so
     * apps hard-coding one of these still pass the local filter. Traffic to
     * any other address never enters the TUN — this is still DNS-only.
     */
    static final String[] ROUTED_DNS_IPV4 = {
            "223.5.5.5", "223.6.6.6",
            "119.29.29.29", "119.28.183.184", "182.254.116.116",
            "8.8.8.8", "8.8.4.4",
            "1.1.1.1", "1.0.0.1",
            "114.114.114.114", "114.114.115.115",
            "180.76.76.79",
            "202.141.176.3",
            "101.226.4.6", "210.22.84.3"
    };
    static final String[] ROUTED_DNS_IPV6 = {
            "2400:3200::1",
            "2400:3200:b000::1",
            "2400:da00::6666",
            "2606:4700:4700::1111",
            "2606:4700:4700::1001",
            "2001:4860:4860::8888",
            "2001:4860:4860::8844",
            "2620:fe::fe"
    };

    private static volatile DnsVpnService instance;
    private static volatile boolean running;
    private static volatile boolean starting;
    private static volatile String lastError = "";

    private final Object writeLock = new Object();
    private volatile ParcelFileDescriptor tunnel;
    private volatile FileOutputStream tunnelOutput;
    private volatile Thread tunnelThread;
    private volatile ExecutorService queryExecutor;
    private volatile TcpDnsRelay tcpRelay;
    private volatile DnsFilterSnapshot snapshot = DnsFilterSnapshot.empty();
    private final DnsCache responseCache = new DnsCache();
    /** Upstream sockets are reused per worker thread instead of per query. */
    private final ThreadLocal<UpstreamChannel> upstreamChannels = new ThreadLocal<>();
    private final Set<DatagramSocket> openUpstreamSockets =
            ConcurrentHashMap.newKeySet();
    private volatile boolean strictMode;
    private volatile boolean cnameGuard;
    private volatile boolean autoLearn;
    private volatile int blockMode;
    private volatile int preferredUpstream;
    private final java.util.concurrent.atomic.AtomicBoolean reloadRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean reloadPending;
    private volatile String lastRuleFingerprint = "";
    private int startGeneration;

    static boolean isRunning() {
        return running;
    }

    static boolean isAlive() {
        return instance != null;
    }

    static String lastError() {
        return lastError;
    }

    static int ruleCount() {
        DnsVpnService service = instance;
        return service == null ? 0 : service.snapshot.totalRules();
    }

    /** Live snapshot for the rules UI; empty when the filter is not running. */
    static DnsFilterSnapshot currentSnapshot() {
        DnsVpnService service = instance;
        return service == null ? DnsFilterSnapshot.empty() : service.snapshot;
    }

    /**
     * Re-reads every rule source (built-in, extended list, import, custom
     * lists, strict mode) without tearing the tunnel down.
     */
    static void reloadRules() {
        DnsVpnService service = instance;
        if (service == null || !running) {
            return;
        }
        service.requestReload();
    }

    /**
     * Coalesces reload requests: rapid switch toggles run one rebuild at a
     * time plus at most one follow-up, so the 10^5-entry list is never parsed
     * by several threads at once (which would spike memory on low-end phones).
     */
    private void requestReload() {
        reloadPending = true;
        if (!reloadRunning.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                while (reloadPending && running) {
                    reloadPending = false;
                    reloadSnapshot();
                }
            } finally {
                reloadRunning.set(false);
                if (reloadPending && running) {
                    requestReload();
                }
            }
        }, "jingjump-dns-reload");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown(true, false);
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RELOAD.equals(intent.getAction())) {
            if (running) {
                reloadRules();
            }
            return START_STICKY;
        }
        final int generation;
        synchronized (this) {
            if (running || starting) {
                return START_STICKY;
            }
            lastError = "";
            starting = true;
            startGeneration++;
            generation = startGeneration;
            setState(SettingsStore.DNS_STATE_STARTING);
        }
        if (!startForegroundSafely()) {
            return START_NOT_STICKY;
        }
        Thread starter = new Thread(
                () -> startTunnel(generation),
                "jingjump-dns-start"
        );
        starter.setDaemon(true);
        tunnelThread = starter;
        starter.start();
        return START_STICKY;
    }

    private void startTunnel(int generation) {
        // Capture the inputs before loading: if a switch changes while the
        // list is being parsed, the fingerprint mismatch below triggers one
        // follow-up reload instead of silently keeping stale rules.
        String loadedFingerprint = DnsRuleStore.ruleFingerprint(this);
        DnsFilterSnapshot loadedSnapshot = DnsRuleStore.load(this);
        ThreadPoolExecutor loadedExecutor = new ThreadPoolExecutor(
                UPSTREAM_THREADS,
                UPSTREAM_THREADS,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(UPSTREAM_QUEUE)
        );
        loadedExecutor.allowCoreThreadTimeOut(true);
        TcpDnsRelay loadedRelay = new TcpDnsRelay(this::writeRaw, this::upstreamTcpQuery);
        ParcelFileDescriptor establishedTunnel = null;

        try {
            Builder builder = new Builder()
                    .setSession("静跳 DNS 过滤")
                    .setMtu(TUN_MTU)
                    .addAddress(TUN_ADDRESS, TUN_PREFIX)
                    .addAddress(TUN_V6_ADDRESS, TUN_V6_PREFIX)
                    .setBlocking(true)
                    .addDnsServer(PRIMARY_DNS)
                    .addDnsServer(SECONDARY_DNS);
            for (String server : ROUTED_DNS_IPV4) {
                builder.addRoute(server, 32);
            }
            for (String server : ROUTED_DNS_IPV6) {
                builder.addRoute(server, 128);
            }
            builder.addDisallowedApplication(getPackageName());
            establishedTunnel = builder.establish();
        } catch (Throwable error) {
            loadedExecutor.shutdownNow();
            loadedRelay.close();
            closeQuietly(establishedTunnel);
            failStart(generation, error.getClass().getSimpleName());
            return;
        }
        if (establishedTunnel == null) {
            loadedExecutor.shutdownNow();
            loadedRelay.close();
            failStart(generation, "VPN_NOT_AVAILABLE");
            return;
        }

        synchronized (this) {
            if (!starting || generation != startGeneration) {
                loadedExecutor.shutdownNow();
                loadedRelay.close();
                closeQuietly(establishedTunnel);
                return;
            }
            snapshot = loadedSnapshot;
            lastRuleFingerprint = loadedFingerprint;
            refreshSettings();
            queryExecutor = loadedExecutor;
            tcpRelay = loadedRelay;
            tunnel = establishedTunnel;
            starting = false;
            running = true;
            setState(SettingsStore.DNS_STATE_RUNNING);
        }
        refreshNotification();
        if (!DnsRuleStore.ruleFingerprint(this).equals(lastRuleFingerprint)) {
            requestReload();
        }
        Thread reader = new Thread(this::readTunnel, "jingjump-dns-reader");
        reader.setDaemon(true);
        tunnelThread = reader;
        reader.start();
    }

    private void reloadSnapshot() {
        try {
            // Cheap flags (block mode, CNAME guard, auto-learn) must apply even
            // when the rule sources are unchanged.
            refreshSettings();
            String fingerprint = DnsRuleStore.ruleFingerprint(this);
            if (fingerprint.equals(lastRuleFingerprint)) {
                return;
            }
            DnsFilterSnapshot next = DnsRuleStore.load(this);
            synchronized (this) {
                if (!running) {
                    return;
                }
                snapshot = next;
                lastRuleFingerprint = fingerprint;
                refreshSettings();
            }
            responseCache.clear();
            refreshNotification();
        } catch (Throwable ignored) {
            // keep the previous snapshot when a reload fails
        }
    }

    private void refreshSettings() {
        strictMode = SettingsStore.dnsStrictMode(this);
        cnameGuard = SettingsStore.dnsCnameGuard(this);
        autoLearn = SettingsStore.autoLearn(this);
        blockMode = SettingsStore.dnsBlockMode(this);
    }

    private synchronized void failStart(int generation, String error) {
        if (!starting || generation != startGeneration) {
            return;
        }
        lastError = error;
        setState(SettingsStore.DNS_STATE_ERROR);
        shutdown(true, true);
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (Throwable ignored) {
        }
    }

    private void readTunnel() {
        byte[] buffer = new byte[32_767];
        try (FileInputStream input = new FileInputStream(tunnel.getFileDescriptor());
             FileOutputStream output = new FileOutputStream(tunnel.getFileDescriptor())) {
            tunnelOutput = output;
            while (running) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    Thread.sleep(15L);
                    continue;
                }
                int offset = 0;
                while (running && offset + 20 <= read) {
                    int version = (buffer[offset] >>> 4) & 0x0f;
                    int packetLength;
                    if (version == 4) {
                        packetLength = u16(buffer, offset + 2);
                        if (packetLength < 20) {
                            break;
                        }
                    } else if (version == 6) {
                        if (offset + 40 > read) {
                            break;
                        }
                        packetLength = 40 + u16(buffer, offset + 4);
                    } else {
                        break;
                    }
                    if (offset + packetLength > read) {
                        break;
                    }
                    // A single malformed packet must never tear the tunnel down:
                    // that used to turn one bad frame into "DNS filtering died".
                    try {
                        handlePacket(buffer, offset, packetLength);
                    } catch (Throwable ignored) {
                        // ignore this packet and keep serving the rest
                    }
                    offset += packetLength;
                }
            }
        } catch (Throwable ignored) {
            // Closing the TUN descriptor during stop is expected to interrupt this loop.
        } finally {
            if (running) {
                lastError = "DNS_TUNNEL_CLOSED";
                setState(SettingsStore.DNS_STATE_ERROR);
                shutdown(true, true);
            }
        }
    }

    private void handlePacket(byte[] packet, int base, int available) {
        DnsPacket.Segment segment = DnsPacket.parseTcpSegment(packet, base, available);
        if (segment != null) {
            if (segment.destinationPort == 853) {
                if (strictMode) {
                    // DNS-over-TLS: refuse the handshake so clients fall back to
                    // resolvers this filter can actually see.
                    writeRaw(DnsPacket.buildTcpRst(segment));
                }
                return;
            }
            if (segment.destinationPort == 53) {
                TcpDnsRelay relay = tcpRelay;
                if (relay != null) {
                    relay.handle(packet, base, available);
                }
            }
            return;
        }
        DnsPacket.Query query = DnsPacket.parseQuery(packet, base, available);
        if (query != null) {
            handleDnsQuery(query);
        }
    }

    private void handleDnsQuery(DnsPacket.Query query) {
        String domain = query.question.name;
        int queryType = query.question.type;
        DnsFilterSnapshot current = snapshot;

        if (current.isAllowed(domain)) {
            forward(query, domain, queryType, current);
            return;
        }
        int source = current.blockSource(domain);
        if (source == DnsFilterSnapshot.SOURCE_NONE && autoLearn) {
            if (AutoLearnStore.isLearnedBlocked(this, domain)) {
                source = DnsFilterSnapshot.SOURCE_LEARNED;
            } else if (AutoLearnStore.inspectAndLearn(this, domain)) {
                source = DnsFilterSnapshot.SOURCE_LEARNED;
            }
        }
        if (source != DnsFilterSnapshot.SOURCE_NONE) {
            byte[] response = DnsPacket.buildBlockedResponse(
                    query.dnsPayload,
                    query.question,
                    blockMode
            );
            if (response != null) {
                writeReply(query, response);
                noteBlocked(domain, queryType, source);
            }
            return;
        }
        forward(query, domain, queryType, current);
    }

    private void forward(
            DnsPacket.Query query,
            String domain,
            int queryType,
            DnsFilterSnapshot current
    ) {
        int queryId = ((query.dnsPayload[0] & 0xff) << 8) | (query.dnsPayload[1] & 0xff);
        byte[] cached = responseCache.get(domain, queryType, queryId);
        if (cached != null) {
            writeReply(query, cached);
            return;
        }
        ExecutorService executor = queryExecutor;
        if (executor == null || executor.isShutdown()) {
            sendTemporaryFailure(query);
            return;
        }
        try {
            // Bounded queue: under a query storm a fail-fast SERVFAIL is far
            // better than letting the backlog grow until clients time out.
            executor.execute(() -> resolveAndReply(query, domain, queryType, current));
        } catch (RejectedExecutionException overloaded) {
            sendTemporaryFailure(query);
        } catch (Throwable ignored) {
            sendTemporaryFailure(query);
        }
    }

    private void resolveAndReply(
            DnsPacket.Query query,
            String domain,
            int queryType,
            DnsFilterSnapshot current
    ) {
        byte[] upstream = resolve(query.dnsPayload);
        if (upstream == null) {
            sendTemporaryFailure(query);
            return;
        }
        String target = cnameBlockTarget(current, cnameGuard, domain, upstream);
        if (target != null) {
            byte[] response = DnsPacket.buildBlockedResponse(
                    query.dnsPayload,
                    query.question,
                    blockMode
            );
            if (response != null) {
                writeReply(query, response);
                noteBlocked(target, queryType, DnsFilterSnapshot.SOURCE_CNAME);
                return;
            }
        }
        int limit = query.ipVersion == 6 ? MAX_UDP_PAYLOAD_V6 : MAX_UDP_PAYLOAD_V4;
        if (upstream.length <= limit) {
            long ttl = DnsPacket.cacheTtlSeconds(upstream);
            if (ttl > 0) {
                responseCache.put(domain, queryType, upstream, ttl);
            }
            writeReply(query, upstream);
            return;
        }
        // The answer does not fit the tunnel MTU. Tell the client to retry over
        // TCP (which the relay now really serves) instead of pretending the
        // upstream is broken.
        byte[] truncated = DnsPacket.buildTruncatedResponse(
                query.dnsPayload,
                query.question
        );
        if (truncated != null) {
            writeReply(query, truncated);
        } else {
            sendTemporaryFailure(query);
        }
    }

    /**
     * CNAME-cloaked tracking decision. A whitelisted request is never broken
     * by its answer chain — otherwise adding a domain to the whitelist could
     * still fail to resolve it, which is the classic "white-listed but still
     * broken" report.
     */
    static String cnameBlockTarget(
            DnsFilterSnapshot snapshot,
            boolean guard,
            String queriedDomain,
            byte[] upstream
    ) {
        if (!guard || snapshot == null || upstream == null) {
            return null;
        }
        if (snapshot.isAllowed(queriedDomain)) {
            return null;
        }
        String target = DnsPacket.findBlockedTarget(upstream, snapshot);
        if (target != null && !snapshot.isAllowed(target)) {
            return target;
        }
        return null;
    }

    private byte[] resolve(byte[] query) {
        // Serial with a short per-server timeout: bursts of DNS queries must
        // never spawn per-query threads, and the response cache already
        // absorbs the repeat traffic that made racing worthwhile.
        java.util.List<String> order = orderFor(preferredUpstream);
        for (String server : order) {
            byte[] answer = queryServer(server, query);
            if (answer != null) {
                preferredUpstream = UPSTREAM_SERVERS.indexOf(server);
                return answer;
            }
        }
        return null;
    }

    /**
     * Resolution order for the next attempt: the server that answered last
     * goes first, so a broken primary stops costing a full timeout on every
     * cold query.
     */
    static java.util.List<String> orderFor(int preferred) {
        if (preferred <= 0 || preferred >= UPSTREAM_SERVERS.size()) {
            return UPSTREAM_SERVERS;
        }
        java.util.ArrayList<String> order = new java.util.ArrayList<>(UPSTREAM_SERVERS);
        java.util.Collections.swap(order, 0, preferred);
        return order;
    }

    private static final class UpstreamChannel {
        DatagramSocket socket;
        InetAddress address;
        final byte[] buffer = new byte[TUN_MTU];
    }

    private byte[] queryServer(String server, byte[] query) {
        UpstreamChannel channel;
        try {
            channel = upstreamChannels.get();
            if (channel == null) {
                channel = new UpstreamChannel();
                upstreamChannels.set(channel);
            }
            if (channel.socket == null || channel.socket.isClosed()) {
                DatagramSocket socket = new DatagramSocket();
                if (!protect(socket)) {
                    socket.close();
                    return null;
                }
                socket.setSoTimeout(DNS_TIMEOUT_MS);
                channel.socket = socket;
                openUpstreamSockets.add(socket);
            }
            if (channel.address == null
                    || !server.equals(channel.address.getHostAddress())) {
                channel.address = InetAddress.getByName(server);
            }
            DatagramSocket socket = channel.socket;
            socket.send(new DatagramPacket(query, query.length, channel.address, 53));
            long deadline = System.currentTimeMillis() + DNS_TIMEOUT_MS;
            int expectedId = u16(query, 0);
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                socket.setSoTimeout((int) remaining);
                DatagramPacket packet = new DatagramPacket(
                        channel.buffer, channel.buffer.length);
                socket.receive(packet);
                if (packet.getLength() < 12) {
                    continue;
                }
                // A late answer for an earlier query can land on the reused
                // socket: skip it instead of failing this query.
                if (u16(channel.buffer, 0) != expectedId
                        || (channel.buffer[2] & 0x80) == 0) {
                    continue;
                }
                return Arrays.copyOf(channel.buffer, packet.getLength());
            }
        } catch (Throwable ignored) {
            // A timeout or a reset socket both mean "try the next server".
            return null;
        }
    }

    /** One framed DNS query over a real TCP connection to the sticky upstream. */
    private byte[] upstreamTcpQuery(byte[] dnsQuery) throws IOException {
        String server = orderFor(preferredUpstream).get(0);
        Socket socket = new Socket();
        try {
            if (!protect(socket)) {
                throw new IOException("PROTECT_FAILED");
            }
            socket.connect(new InetSocketAddress(server, 53), TCP_CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(TCP_READ_TIMEOUT_MS);
            OutputStream output = socket.getOutputStream();
            output.write((dnsQuery.length >>> 8) & 0xff);
            output.write(dnsQuery.length & 0xff);
            output.write(dnsQuery);
            output.flush();
            InputStream input = socket.getInputStream();
            int high = input.read();
            int low = input.read();
            if (high < 0 || low < 0) {
                return null;
            }
            int length = (high << 8) | low;
            if (length < 12 || length > 65_535) {
                return null;
            }
            byte[] response = new byte[length];
            int read = 0;
            while (read < length) {
                int chunk = input.read(response, read, length - read);
                if (chunk < 0) {
                    return null;
                }
                read += chunk;
            }
            preferredUpstream = UPSTREAM_SERVERS.indexOf(server);
            return response;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void sendTemporaryFailure(DnsPacket.Query query) {
        byte[] response = DnsPacket.buildError(
                query.dnsPayload,
                0,
                query.question,
                DnsPacket.RCODE_SERVFAIL
        );
        if (response != null) {
            writeReply(query, response);
        }
    }

    private void noteBlocked(String domain, int queryType, int source) {
        DnsBlockLog.record(domain, queryType, source);
        int count = SettingsStore.recordDnsBlock(this);
        if (count % 20 == 0) {
            refreshNotification();
        }
    }

    private void writeReply(DnsPacket.Query query, byte[] dnsPayload) {
        writeRaw(DnsPacket.buildReply(query, dnsPayload));
    }

    private void writeRaw(byte[] packet) {
        FileOutputStream output = tunnelOutput;
        if (packet == null || output == null || !running) {
            return;
        }
        try {
            synchronized (writeLock) {
                output.write(packet);
                output.flush();
            }
        } catch (Throwable ignored) {
            // The reader loop handles the closed descriptor.
        }
    }

    private int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "DNS 广告过滤",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("显示本地 DNS 过滤运行状态");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(
                this,
                11,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Intent stopIntent = new Intent(this, DnsVpnService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(
                this,
                12,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        int rules = snapshot.totalRules();
        int blocked = SettingsStore.dnsBlockCount(this);
        int state = SettingsStore.dnsState(this);
        String title;
        String text;
        if (state == SettingsStore.DNS_STATE_STARTING) {
            title = "DNS 过滤正在启动";
            text = "正在建立本地 DNS 通道";
        } else if (state == SettingsStore.DNS_STATE_RUNNING) {
            title = "DNS 过滤已开启";
            text = "规则 " + rules + " 条 · 已拦截 " + blocked + " 次";
        } else {
            title = "DNS 过滤正在停止";
            text = "释放本地 DNS 通道";
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(0, "停止", stop)
                .build();
    }

    private boolean startForegroundSafely() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (Throwable error) {
            lastError = "FOREGROUND_SERVICE";
            setState(SettingsStore.DNS_STATE_ERROR);
            shutdown(true, true);
            return false;
        }
    }

    private void refreshNotification() {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && (running || starting)) {
                manager.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Throwable ignored) {
            // The VPN remains usable even if a vendor notification manager rejects an update.
        }
    }

    private synchronized void shutdown(boolean stopService, boolean preserveError) {
        startGeneration++;
        boolean wasRunning = running;
        boolean wasStarting = starting;
        running = false;
        starting = false;
        ParcelFileDescriptor currentTunnel = tunnel;
        tunnel = null;
        if (currentTunnel != null) {
            try {
                currentTunnel.close();
            } catch (Throwable ignored) {
            }
        }
        ExecutorService executor = queryExecutor;
        queryExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        TcpDnsRelay relay = tcpRelay;
        tcpRelay = null;
        if (relay != null) {
            relay.close();
        }
        for (DatagramSocket socket : openUpstreamSockets) {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
        openUpstreamSockets.clear();
        tunnelOutput = null;
        responseCache.clear();
        SettingsStore.flushDnsBlockCount(this);
        if (wasRunning || wasStarting || stopService) {
            try {
                stopForeground(true);
            } catch (Throwable ignored) {
            }
        }
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {
        }
        if (!preserveError) {
            setState(SettingsStore.DNS_STATE_IDLE);
        }
        if (stopService) {
            stopSelf();
        }
    }

    private void setState(int state) {
        SettingsStore.setDnsState(this, state);
    }

    @Override
    public void onRevoke() {
        shutdown(true, false);
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        shutdown(false, SettingsStore.dnsState(this) == SettingsStore.DNS_STATE_ERROR);
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}