package de.vesterion.vistierie.agent.runner;

import de.vesterion.vistierie.agents.dto.ToolDef;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ToolDispatcher#dispatchMcp}. Logic tests (retry/cache/concurrency)
 * use a stub {@link ToolDispatcher.McpClientFactory}/{@link ToolDispatcher.McpClientHandle}
 * with a ZERO retry base so nothing sleeps; integration tests drive the real
 * {@link FakeMcpServer} through the production factory.
 */
class McpToolDispatcherTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String TOKEN = "test-token";

    private static ToolDef mcpTool(String serverUrl, String toolName, Integer timeoutSeconds) {
        return new ToolDef(toolName, "desc", JsonNodeFactory.instance.objectNode().put("type", "object"),
                "mcp", null, null, null, serverUrl, toolName, null, timeoutSeconds);
    }

    private static ToolUseParser.Block block(String id, String name, String inputJson) {
        return new ToolUseParser.Block(id, name, M.readTree(inputJson));
    }

    // ---- 1. Happy path (integration) --------------------------------------------------

    @Test
    void happyPathEchoesInput() throws Exception {
        try (FakeMcpServer fake = new FakeMcpServer(TOKEN, 0)) {
            ToolDispatcher dispatcher = new ToolDispatcher(
                    new ToolDispatcher.HttpClientMcpClientFactory(),
                    Executors.newVirtualThreadPerTaskExecutor(), 0L, 30);
            ToolDef tool = mcpTool(fake.baseUrl(), "echo", null);
            ToolUseParser.Block block = block("toolu_1", "echo", "{\"message\":\"hi\",\"n\":42}");

            ToolResult result = dispatcher.dispatchMcp(tool, block, "run-x", TOKEN).get();

            assertThat(result.toolUseId()).isEqualTo("toolu_1");
            assertThat(result.isError()).isFalse();
            assertThat(result.content().path("message").asString()).isEqualTo("hi");
            assertThat(result.content().path("n").asInt()).isEqualTo(42);
        }
    }

    // ---- 2. isError mapping (integration) ---------------------------------------------

    @Test
    void alwaysErrorMapsToErrorResult() throws Exception {
        try (FakeMcpServer fake = new FakeMcpServer(TOKEN, 0)) {
            ToolDispatcher dispatcher = new ToolDispatcher(
                    new ToolDispatcher.HttpClientMcpClientFactory(),
                    Executors.newVirtualThreadPerTaskExecutor(), 0L, 30);
            ToolDef tool = mcpTool(fake.baseUrl(), "always_error", null);
            ToolUseParser.Block block = block("toolu_e", "always_error", "{}");

            ToolResult result = dispatcher.dispatchMcp(tool, block, "run-e", TOKEN).get();

            assertThat(result.isError()).isTrue();
            assertThat(result.content().has("error")).isTrue();
        }
    }

    // ---- 3. Retry count (unit) --------------------------------------------------------

    @Test
    void alwaysThrowingHandleIsRetriedExactlyFourTimes() throws Exception {
        CountingFactory factory = new CountingFactory(() -> new ThrowingHandle());
        ToolDispatcher dispatcher = new ToolDispatcher(
                factory, Executors.newVirtualThreadPerTaskExecutor(), 0L, 30);
        ToolDef tool = mcpTool("http://server-a", "echo", null);

        ToolResult result = dispatcher.dispatchMcp(tool, block("u1", "echo", "{}"), "r", "tok").get();

        assertThat(result.isError()).isTrue();
        int totalCalls = factory.handles.stream().mapToInt(h -> ((ThrowingHandle) h).calls.get()).sum();
        assertThat(totalCalls).isEqualTo(4);            // 1 initial + 3 retries
        assertThat(factory.createCount.get()).isEqualTo(4); // evict + rebuild each attempt
    }

    @Test
    void transientHandleSucceedsOnThirdAttempt() throws Exception {
        // Failure is server-side state: a fresh handle is built each retry (evict + rebuild), so
        // the "first 2 calls fail" counter is shared across handles, mirroring FakeMcpServer.
        AtomicInteger sharedCalls = new AtomicInteger();
        CountingFactory factory = new CountingFactory(() -> new SharedFailThenSucceedHandle(sharedCalls, 2));
        ToolDispatcher dispatcher = new ToolDispatcher(
                factory, Executors.newVirtualThreadPerTaskExecutor(), 0L, 30);
        ToolDef tool = mcpTool("http://server-a", "echo", null);

        ToolResult result = dispatcher.dispatchMcp(tool, block("u1", "echo", "{\"k\":1}"), "r", "tok").get();

        assertThat(result.isError()).isFalse();
        assertThat(result.content().path("k").asInt()).isEqualTo(1);
        assertThat(sharedCalls.get()).isEqualTo(3); // fail, fail, succeed
        assertThat(factory.createCount.get()).isEqualTo(3); // one fresh client per attempt
    }

    // ---- 4. Cache key includes token (unit) -------------------------------------------

    @Test
    void cacheKeyIncludesToken() throws Exception {
        CountingFactory factory = new CountingFactory(EchoHandle::new);
        ToolDispatcher dispatcher = new ToolDispatcher(
                factory, Executors.newVirtualThreadPerTaskExecutor(), 0L, 30);
        ToolDef tool = mcpTool("http://same-server", "echo", null);

        dispatcher.dispatchMcp(tool, block("u1", "echo", "{}"), "r", "token-A").get();
        dispatcher.dispatchMcp(tool, block("u2", "echo", "{}"), "r", "token-B").get();

        // same server, two different tokens -> two distinct handles
        assertThat(factory.createArgs).containsExactlyInAnyOrder(
                "http://same-server|token-A", "http://same-server|token-B");
        assertThat(factory.createCount.get()).isEqualTo(2);

        // same server + same token twice -> cached, no new create
        dispatcher.dispatchMcp(tool, block("u3", "echo", "{}"), "r", "token-A").get();
        assertThat(factory.createCount.get()).isEqualTo(2);
    }

    /**
     * Deterministic race on a NEW key: two threads both pass the {@code get()==null} check and both
     * build a client (create blocks on a latch until both are inside); after release exactly one
     * holder survives and the loser closes its redundant client. Guards the get-then-putIfAbsent
     * pattern that keeps client construction off any map lock.
     */
    @Test
    void concurrentFirstCallOnSameKeyBuildsOneSurvivingHolderAndClosesLoser() throws Exception {
        CountDownLatch bothInCreate = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        var built = new ConcurrentLinkedQueue<CloseTrackingHandle>();
        ToolDispatcher.McpClientFactory factory = (serverUrl, token, timeout) -> {
            CloseTrackingHandle h = new CloseTrackingHandle();
            built.add(h);
            bothInCreate.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return h;
        };
        ToolDispatcher dispatcher = new ToolDispatcher(
                factory, Executors.newVirtualThreadPerTaskExecutor(), 0L, 30);
        ToolDef tool = mcpTool("http://server-a", "echo", null);

        var f1 = dispatcher.dispatchMcp(tool, block("u1", "echo", "{}"), "r", "tok");
        var f2 = dispatcher.dispatchMcp(tool, block("u2", "echo", "{}"), "r", "tok");
        bothInCreate.await();  // both threads passed get()==null and are inside create()
        release.countDown();

        assertThat(f1.get().isError()).isFalse();
        assertThat(f2.get().isError()).isFalse();
        assertThat(built).hasSize(2);
        long closed = built.stream().filter(h -> h.closed.get()).count();
        assertThat(closed).isEqualTo(1); // exactly the race loser's client got closed
    }

    /**
     * Deterministic reproduction of the close/callTool race on a SHARED holder.
     *
     * <p>Two dispatches hit the same (server, token) and therefore the same cached
     * {@link ToolDispatcher.McpClientHolder} wrapping one shared handle. Thread A's {@code callTool}
     * fails, so the retry loop must evict + {@code close()} that shared handle. Thread B is queued on
     * the holder's monitor and then calls the SAME handle. {@code McpSyncClient} is not thread-safe, so
     * a {@code callTool} must never overlap a {@code close()} on the same handle. The instrumented handle
     * flips {@link SharedRaceHandle#overlap} whenever two operations on it are in progress at once; the
     * test asserts that never happens.
     *
     * <p>Pre-fix the try/catch wraps the {@code synchronized} block, so A's {@code close()} runs AFTER the
     * monitor is released and B's queued {@code callTool} runs concurrently with it -> overlap==true ->
     * this test FAILS. Post-fix the eviction+close run under the monitor, B stays blocked until close
     * finishes -> overlap==false -> this test PASSES.
     *
     * <p>Determinism: latches force both A's {@code close()} and B's {@code callTool} to be parked (both
     * "in progress") simultaneously before anything is released, so whichever enters second observes the
     * overlap — no reliance on scheduling speed. The single bounded {@code await} ({@code bInCall}) is a
     * liveness bound that only elapses on the post-fix path (where B legitimately cannot proceed until
     * close completes); the pass/fail outcome does not depend on its duration.
     */
    @Test
    void closeNeverOverlapsCallToolOnSharedHolder() throws Exception {
        SharedRaceHandle shared = new SharedRaceHandle();
        AtomicInteger creates = new AtomicInteger();
        // First create() (the warm-up) hands back the instrumented shared handle that A and B will share;
        // later creates (A's post-failure rebuild) get throwaway handles so they cannot taint the counters.
        ToolDispatcher.McpClientFactory factory = (serverUrl, token, timeout) ->
                creates.getAndIncrement() == 0 ? shared : new EchoHandle();

        List<Thread> workers = new CopyOnWriteArrayList<>();
        ThreadFactory tf = r -> { Thread t = new Thread(r); workers.add(t); return t; };
        ExecutorService exec = Executors.newFixedThreadPool(4, tf);
        try {
            ToolDispatcher dispatcher = new ToolDispatcher(factory, exec, 0L, 30);
            ToolDef tool = mcpTool("http://server-a", "echo", null);

            // Warm-up: seed the cache with the shared holder so A and B both get() the SAME holder
            // (no putIfAbsent creation race, no premature eviction before B has the reference).
            assertThat(dispatcher.dispatchMcp(tool, block("warm", "echo", "{}"), "r", "tok").get().isError())
                    .isFalse();
            shared.arm();

            // A: acquires the holder monitor and parks INSIDE callTool (still holding it).
            var fA = dispatcher.dispatchMcp(tool, block("uA", "echo", "{}"), "r", "tok");
            shared.aInCall.await();

            // B: gets the SAME cached holder, then blocks trying to enter the monitor A holds.
            var fB = dispatcher.dispatchMcp(tool, block("uB", "echo", "{\"i\":1}"), "r", "tok");
            // Spin until B is genuinely BLOCKED on the holder monitor (not merely scheduled) so that
            // releasing A cannot outrun B's get()+monitor-enter. Condition-based, no fixed sleep.
            while (workers.stream().noneMatch(t -> t.getState() == Thread.State.BLOCKED)) {
                Thread.onSpinWait();
            }

            // Let A's callTool throw -> the retry loop must evict + close the shared handle.
            shared.aMayThrow.countDown();
            shared.closeStarted.await();            // close() is now in progress on the shared handle

            // Wait for B to actually enter callTool (only possible pre-fix, where close ran outside the
            // monitor) before letting close finish. Post-fix B stays blocked and this bound elapses.
            boolean bEnteredDuringClose = shared.bInCall.await(2, TimeUnit.SECONDS);

            shared.closeMayProceed.countDown();
            shared.bMayProceed.countDown();
            if (!bEnteredDuringClose) shared.bInCall.await();   // post-fix: B runs cleanly after close

            assertThat(fA.get().isError()).isFalse();
            assertThat(fB.get().isError()).isFalse();
            assertThat(shared.closed.get()).as("the failed shared handle was closed").isTrue();
            assertThat(shared.overlap.get())
                    .as("close() must not overlap a callTool() on the same handle")
                    .isFalse();
        } finally {
            exec.shutdownNow();
        }
    }

    // ---- 5. Concurrent same (server,token) do not interleave (unit) --------------------

    @Test
    void concurrentSameHolderSerializes() throws Exception {
        ConcurrencyEchoHandle handle = new ConcurrencyEchoHandle();
        CountingFactory factory = new CountingFactory(() -> handle);
        ToolDispatcher dispatcher = new ToolDispatcher(
                factory, Executors.newVirtualThreadPerTaskExecutor(), 0L, 30);
        ToolDef tool = mcpTool("http://server-a", "echo", null);

        int n = 16;
        var futures = new ConcurrentLinkedQueue<CompletableFuture<ToolResult>>();
        for (int i = 0; i < n; i++) {
            futures.add(dispatcher.dispatchMcp(tool, block("u" + i, "echo", "{\"i\":" + i + "}"), "r", "tok"));
        }
        int idx = 0;
        for (var f : futures) {
            ToolResult r = f.get();
            assertThat(r.isError()).isFalse();
            assertThat(r.content().path("i").asInt()).isEqualTo(idx++); // each result matches its own request
        }
        assertThat(handle.maxConcurrent.get()).isEqualTo(1); // serialized on the single holder
        assertThat(factory.createCount.get()).isEqualTo(1); // one holder built
    }

    // ---- 6. Concurrent different (server,token) run in parallel (unit) -----------------

    @Test
    void concurrentDifferentHoldersRunInParallel() throws Exception {
        int k = 6;
        long sleepMs = 150;
        CountingFactory factory = new CountingFactory(() -> new SleepingHandle(sleepMs));
        ToolDispatcher dispatcher = new ToolDispatcher(
                factory, Executors.newVirtualThreadPerTaskExecutor(), 0L, 30);

        var futures = new ConcurrentLinkedQueue<CompletableFuture<ToolResult>>();
        long start = System.nanoTime();
        for (int i = 0; i < k; i++) {
            ToolDef tool = mcpTool("http://server-" + i, "echo", null);
            futures.add(dispatcher.dispatchMcp(tool, block("u" + i, "echo", "{}"), "r", "tok"));
        }
        for (var f : futures) assertThat(f.get().isError()).isFalse();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // Parallel: wall clock is near one latency, nowhere near k*sleepMs.
        assertThat(elapsedMs).isLessThan((long) (k * sleepMs * 0.6));
        assertThat(factory.createCount.get()).isEqualTo(k);
    }

    // ---- 7. Missing credential (unit) -------------------------------------------------

    @Test
    void missingCredentialShortCircuits() throws Exception {
        CountingFactory factory = new CountingFactory(EchoHandle::new);
        ToolDispatcher dispatcher = new ToolDispatcher(
                factory, Executors.newVirtualThreadPerTaskExecutor(), 0L, 30);
        ToolDef tool = mcpTool("http://server-a", "echo", null);

        ToolResult blankResult = dispatcher.dispatchMcp(tool, block("u1", "echo", "{}"), "r", "   ").get();
        ToolResult nullResult = dispatcher.dispatchMcp(tool, block("u2", "echo", "{}"), "r", null).get();

        assertThat(blankResult.isError()).isTrue();
        assertThat(blankResult.content().path("error").asString()).contains("no mcp credential");
        assertThat(nullResult.isError()).isTrue();
        assertThat(nullResult.content().path("error").asString()).contains("no mcp credential");
        assertThat(factory.createCount.get()).isZero(); // never touched the network
    }

    // ==== stub factory + handles =======================================================

    /** Counts and records every {@code create(...)}; hands back handles from a supplier. */
    static final class CountingFactory implements ToolDispatcher.McpClientFactory {
        final AtomicInteger createCount = new AtomicInteger();
        final ConcurrentLinkedQueue<String> createArgs = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<ToolDispatcher.McpClientHandle> handles = new ConcurrentLinkedQueue<>();
        private final java.util.function.Supplier<ToolDispatcher.McpClientHandle> supplier;

        CountingFactory(java.util.function.Supplier<ToolDispatcher.McpClientHandle> supplier) {
            this.supplier = supplier;
        }

        @Override
        public ToolDispatcher.McpClientHandle create(String serverUrl, String token, Duration timeout) {
            createCount.incrementAndGet();
            createArgs.add(serverUrl + "|" + token);
            ToolDispatcher.McpClientHandle h = supplier.get();
            handles.add(h);
            return h;
        }
    }

    static final class ThrowingHandle implements ToolDispatcher.McpClientHandle {
        final AtomicInteger calls = new AtomicInteger();
        @Override public JsonNode callTool(String toolName, JsonNode input) {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        }
        @Override public void close() {}
    }

    /** Shares a call counter across successive (rebuilt) handles; first N calls throw. */
    static final class SharedFailThenSucceedHandle implements ToolDispatcher.McpClientHandle {
        private final AtomicInteger sharedCalls;
        private final int failuresBeforeSuccess;
        SharedFailThenSucceedHandle(AtomicInteger sharedCalls, int failuresBeforeSuccess) {
            this.sharedCalls = sharedCalls;
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }
        @Override public JsonNode callTool(String toolName, JsonNode input) {
            if (sharedCalls.incrementAndGet() <= failuresBeforeSuccess) throw new RuntimeException("transient");
            return input; // echo
        }
        @Override public void close() {}
    }

    static final class EchoHandle implements ToolDispatcher.McpClientHandle {
        @Override public JsonNode callTool(String toolName, JsonNode input) { return input; }
        @Override public void close() {}
    }

    /** Echoes input; records whether {@code close()} was called (for the race-loser assertion). */
    static final class CloseTrackingHandle implements ToolDispatcher.McpClientHandle {
        final AtomicBoolean closed = new AtomicBoolean();
        @Override public JsonNode callTool(String toolName, JsonNode input) { return input; }
        @Override public void close() { closed.set(true); }
    }

    /** Echoes input and records the max number of concurrent in-flight calls. */
    static final class ConcurrencyEchoHandle implements ToolDispatcher.McpClientHandle {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        @Override public JsonNode callTool(String toolName, JsonNode input) {
            int now = inFlight.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(20); // widen the window for interleaving to show
                return input;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                inFlight.decrementAndGet();
            }
        }
        @Override public void close() {}
    }

    /**
     * Instrumented handle for {@link #closeNeverOverlapsCallToolOnSharedHolder}. Tracks operations on
     * itself: any moment two of {callTool, close} are in progress flips {@link #overlap}. A's first
     * (armed) callTool parks holding the holder monitor then throws; a later callTool (B) parks so that,
     * iff close ran outside the monitor, both B's call and A's close are in progress at the same time.
     */
    static final class SharedRaceHandle implements ToolDispatcher.McpClientHandle {
        final AtomicInteger active = new AtomicInteger();
        final AtomicBoolean overlap = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile boolean armed = false;      // race phase active (set after warm-up)
        volatile boolean throwNext = false;  // next callTool is A's failing call

        final CountDownLatch aInCall = new CountDownLatch(1);
        final CountDownLatch aMayThrow = new CountDownLatch(1);
        final CountDownLatch bInCall = new CountDownLatch(1);
        final CountDownLatch bMayProceed = new CountDownLatch(1);
        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch closeMayProceed = new CountDownLatch(1);

        void arm() { armed = true; throwNext = true; }

        private void enter() { if (active.incrementAndGet() > 1) overlap.set(true); }
        private void exit() { active.decrementAndGet(); }

        @Override public JsonNode callTool(String toolName, JsonNode input) {
            if (throwNext) {                 // A's call: park holding the monitor, then fail
                throwNext = false;
                enter();
                try {
                    aInCall.countDown();
                    await(aMayThrow);
                } finally { exit(); }
                throw new RuntimeException("boom-A");
            }
            if (armed) {                     // B's call: park while active so close can overlap it
                enter();
                try {
                    bInCall.countDown();
                    await(bMayProceed);
                    return input;
                } finally { exit(); }
            }
            return input;                    // warm-up
        }

        @Override public void close() {
            enter();
            try {
                closeStarted.countDown();
                await(closeMayProceed);
            } finally {
                exit();
                closed.set(true);
            }
        }

        private static void await(CountDownLatch l) {
            try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    static final class SleepingHandle implements ToolDispatcher.McpClientHandle {
        private final long sleepMs;
        SleepingHandle(long sleepMs) { this.sleepMs = sleepMs; }
        @Override public JsonNode callTool(String toolName, JsonNode input) {
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return input;
        }
        @Override public void close() {}
    }
}
