/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.sl.test;

import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.oracle.truffle.api.TruffleOptions;

/**
 * Tests that the garbage collector of a native image correctly handles runtime-compiled (JIT
 * compiled) code. Every test calls SL functions until Truffle has compiled them to machine code
 * with the Graal compiler that is part of the image, and then keeps running the compiled code while
 * other threads allocate garbage and {@link System#gc()} is called. The GC must treat the object
 * references in the compiled code (object constants, frame and deoptimization metadata) as roots,
 * update them when it moves the objects, and unload the code once it is no longer reachable.
 * <p>
 * The tests only run in a native image with an optimizing Truffle runtime, e.g.:
 *
 * <pre>
 * mx --dynamicimports /substratevm native-unittest com.oracle.truffle.sl.test.SLRuntimeCompiledCodeGCTest --build-args --macro:truffle [--gc=G1|--gc=shenandoah]
 * </pre>
 */
public class SLRuntimeCompiledCodeGCTest {

    /** How long a test runs its compiled code while GCs happen, in milliseconds. */
    private static final long DURATION_MILLIS = Long.getLong("SLRuntimeCompiledCodeGCTest.durationMillis", 2000);

    /** The maximum time it may take to compile a function, in milliseconds. */
    private static final long COMPILATION_TIMEOUT_MILLIS = 120_000;

    @Before
    public void checkRuntime() {
        Assume.assumeTrue("Tests the GC of a native image", TruffleOptions.AOT);
        Assume.assumeTrue("Needs an optimizing Truffle runtime", TruffleTestAssumptions.isOptimizingRuntime());
    }

    // @formatter:off
    private static final String SUM_OF_SQUARES =
        "function sumOfSquares(n) {\n" +
        "  s = 0;\n" +
        "  i = 0;\n" +
        "  while (i < n) {\n" +
        "    s = s + i * i;\n" +
        "    i = i + 1;\n" +
        "  }\n" +
        "  return s;\n" +
        "}\n";
    // @formatter:on

    /**
     * Calls an SL function until it is compiled, then keeps calling the compiled code while GCs run
     * (so that GCs happen while the compiled code is on the stack and its object constants may
     * move).
     */
    @Test
    @SuppressWarnings("try")
    public void testCompiledCode() throws Exception {
        CompilationLog log = new CompilationLog();
        try (Engine engine = newEngine(log); Context context = newContext(engine); GarbageProducer garbage = new GarbageProducer(2)) {
            Value f = function(context, "sumOfSquares", SUM_OF_SQUARES);
            runUntilCompiled(log, "sumOfSquares", () -> f.execute(100));
            Timer timer = new Timer();
            while (timer.running()) {
                for (int n = 0; n < 500; n++) {
                    Assert.assertEquals("sumOfSquares(" + n + ")", sumOfSquares(n), f.execute(n).asLong());
                }
                timer.gcEvery(250);
            }
            log.assertNoFailures();
        }
    }

    private static long sumOfSquares(int n) {
        long s = 0;
        for (int i = 0; i < n; i++) {
            s += (long) i * i;
        }
        return s;
    }

    // @formatter:off
    private static final String TABLE =
        "function makeTable() {\n" +
        "  table = new();\n" +
        "  i = 0;\n" +
        "  while (i < 64) {\n" +
        "    e = new();\n" +
        "    e.id = i;\n" +
        "    e.name = \"item-\" + i;\n" +
        "    e.data = new();\n" +
        "    e.data.value = i;\n" +
        "    e.data.square = i * i;\n" +
        "    table[\"e\" + i] = e;\n" +
        "    i = i + 1;\n" +
        "  }\n" +
        "  return table;\n" +
        "}\n" +
        "function lookup(table, i) {\n" +
        "  e = table[\"e\" + i];\n" +
        "  bonus = 0;\n" +
        "  if (e.name == \"item-\" + e.data.value) {\n" +
        "    bonus = 1000000;\n" +
        "  }\n" +
        "  return e.id + e.data.square + bonus;\n" +
        "}\n";
    // @formatter:on

    /**
     * Checks that the GC updates the object constants embedded in compiled code. Partial evaluation
     * folds the AST, the object shapes, the property keys and the string literals of the function
     * into its machine code, so the code references many heap objects that move during GCs. Stale
     * references would crash, return wrong results, or fail the (constant) shape checks of the
     * compiled code and deoptimize it, so the test also checks that the compiled function is
     * neither deoptimized nor recompiled while GCs run.
     */
    @Test
    @SuppressWarnings("try")
    public void testObjectConstants() throws Exception {
        CompilationLog log = new CompilationLog();
        try (Engine engine = newEngine(log); Context context = newContext(engine)) {
            Value table = function(context, "makeTable", TABLE).execute();
            Value lookup = function(context, "lookup", TABLE);
            int[] i = {0};
            runUntilCompiled(log, "lookup", () -> lookup.execute(table, (i[0]++) & 63));
            /* Let the profile settle before counting deoptimizations. */
            for (int k = 0; k < 100_000; k++) {
                lookup.execute(table, k & 63);
            }
            int compilations = log.compilations("lookup");
            int deopts = log.deopts("lookup");

            try (GarbageProducer garbage = new GarbageProducer(2)) {
                Timer timer = new Timer();
                while (timer.running()) {
                    for (int k = 0; k < 4096; k++) {
                        Assert.assertEquals("lookup(" + (k & 63) + ")", lookup(k & 63), lookup.execute(table, k & 63).asLong());
                    }
                    timer.gcEvery(250);
                }
            }
            System.gc();
            for (int k = 0; k < 64; k++) {
                Assert.assertEquals("lookup(" + k + ") after the final GC", lookup(k), lookup.execute(table, k).asLong());
            }
            log.assertNoFailures();
            Assert.assertEquals("lookup was deoptimized while GCs ran", deopts, log.deopts("lookup"));
            Assert.assertEquals("lookup was recompiled while GCs ran", compilations, log.compilations("lookup"));
        }
    }

    private static long lookup(int i) {
        return i + (long) i * i + 1000000;
    }

    private static String add(String name) {
        // @formatter:off
        return "function " + name + "(a, b) {\n" +
               "  r = a;\n" +
               "  i = 0;\n" +
               "  while (i < 10) {\n" +
               "    r = r + b;\n" +
               "    i = i + 1;\n" +
               "  }\n" +
               "  return r;\n" +
               "}\n";
        // @formatter:on
    }

    private static final long BIG = Long.MAX_VALUE - 5;
    private static final String BIG_RESULT = BigInteger.valueOf(BIG).add(BigInteger.TEN).toString();

    /**
     * Deoptimization and recompilation while GCs run: a function is compiled for small numbers, then
     * called with numbers that overflow into big integers and with strings, which fails the type
     * speculations of the compiled code, so the code is deoptimized, invalidated (made non-entrant)
     * and recompiled. Every round uses new functions, so that many invalidated methods accumulate,
     * which the GC must free. Deoptimization reads the frame and deoptimization metadata of the
     * compiled code, which contains object references that the GC must keep up to date.
     */
    @Test
    @SuppressWarnings("try")
    public void testDeoptimization() throws Exception {
        CompilationLog log = new CompilationLog();
        try (Engine engine = newEngine(log); Context context = newContext(engine); GarbageProducer garbage = new GarbageProducer(2)) {
            for (int round = 0; round < 10; round++) {
                String name = "add" + round;
                Value f = function(context, name, add(name));

                runUntilCompiled(log, name, () -> Assert.assertEquals(21, f.execute(1, 2).asLong()));
                System.gc();
                Assert.assertEquals(43, f.execute(3, 4).asLong());

                int deopts = log.deopts(name);
                Assert.assertEquals(BIG_RESULT, f.execute(BIG, 1).toString());
                runUntilCompiled(log, name, () -> Assert.assertEquals(BIG_RESULT, f.execute(BIG, 1).toString()));
                Assert.assertEquals("abbbbbbbbbb", f.execute("a", "b").asString());
                runUntilCompiled(log, name, () -> Assert.assertEquals("xyyyyyyyyyy", f.execute("x", "y").asString()));
                Assert.assertTrue(name + " was not deoptimized", log.deopts(name) > deopts);

                /* The generic code still handles all types. */
                System.gc();
                Assert.assertEquals(21, f.execute(1, 2).asLong());
                Assert.assertEquals(BIG_RESULT, f.execute(BIG, 1).toString());
                Assert.assertEquals("abbbbbbbbbb", f.execute("a", "b").asString());
            }
            log.assertNoFailures();
        }
    }

    // @formatter:off
    private static final String STEP =
        "function step(seed) {\n" +
        "  list = new();\n" +
        "  i = 0;\n" +
        "  while (i < 32) {\n" +
        "    e = new();\n" +
        "    e.v = seed + i;\n" +
        "    e.s = \"n\" + i;\n" +
        "    list[\"p\" + i] = e;\n" +
        "    i = i + 1;\n" +
        "  }\n" +
        "  sum = 0;\n" +
        "  i = 0;\n" +
        "  while (i < 32) {\n" +
        "    e = list[\"p\" + i];\n" +
        "    if (e.s == \"n\" + i) {\n" +
        "      sum = sum + e.v;\n" +
        "    }\n" +
        "    i = i + 1;\n" +
        "  }\n" +
        "  return sum;\n" +
        "}\n";
    // @formatter:on

    private static long step(long seed) {
        return 32 * seed + 31 * 32 / 2;
    }

    /**
     * Several threads execute the same compiled code (one context per thread on a shared engine)
     * and allocate SL objects, while the main thread triggers GCs. GCs therefore find compiled
     * frames on many stacks, and the objects that the compiled code allocates and references move.
     */
    @Test
    public void testThreads() throws Exception {
        int nThreads = 4;
        CompilationLog log = new CompilationLog();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (Engine engine = newEngine(log)) {
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < nThreads; t++) {
                long id = t;
                Thread thread = new Thread(() -> {
                    try (Context context = newContext(engine)) {
                        Value step = function(context, "step", STEP);
                        /* Warm up until step is compiled (by any of the threads). */
                        long deadline = System.currentTimeMillis() + COMPILATION_TIMEOUT_MILLIS;
                        for (long i = 0; log.compilations("step") == 0; i++) {
                            Assert.assertEquals(step(i), step.execute(i).asLong());
                            Assert.assertTrue("step was not compiled", System.currentTimeMillis() < deadline);
                        }
                        Timer timer = new Timer();
                        for (long i = 0; failure.get() == null && timer.running(); i++) {
                            long seed = (id << 32) + i;
                            Assert.assertEquals("step(" + seed + ")", step(seed), step.execute(seed).asLong());
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }, "sl-" + t);
                thread.start();
                threads.add(thread);
            }
            for (Thread thread : threads) {
                while (thread.isAlive()) {
                    thread.join(50);
                    System.gc();
                }
            }
        }
        if (failure.get() != null) {
            throw new AssertionError("SL thread failed", failure.get());
        }
        log.assertNoFailures();
    }

    // @formatter:off
    private static final String WORK =
        "function work(n) {\n" +
        "  o = new();\n" +
        "  o.count = 0;\n" +
        "  o.text = \"unload\";\n" +
        "  i = 0;\n" +
        "  while (i < n) {\n" +
        "    if (o.text == \"unload\") {\n" +
        "      o.count = o.count + 6;\n" +
        "    }\n" +
        "    i = i + 1;\n" +
        "  }\n" +
        "  return o.count;\n" +
        "}\n";
    // @formatter:on

    /**
     * Code unloading: every round creates a new engine, compiles a function and drops the engine. The
     * compiled code of a dropped engine is no longer reachable (its call targets and their installed
     * code objects become garbage), so the GC must unload it. Every round evaluates a unique source
     * string: the compiled code references the AST, which references the source and its characters,
     * so the string stays reachable as long as the compiled code of its round is not unloaded.
     */
    @Test
    @SuppressWarnings("try")
    public void testCodeUnloading() throws Exception {
        int rounds = 20;
        List<WeakReference<Engine>> engines = new ArrayList<>();
        List<WeakReference<String>> sources = new ArrayList<>();
        try (GarbageProducer garbage = new GarbageProducer(1)) {
            for (int round = 0; round < rounds; round++) {
                CompilationLog log = new CompilationLog();
                try (Engine engine = newEngine(log); Context context = newContext(engine)) {
                    String source = "/* round " + round + " */\n" + WORK;
                    sources.add(new WeakReference<>(source));
                    Value f = function(context, "work", source);
                    runUntilCompiled(log, "work", () -> Assert.assertEquals(60, f.execute(10).asLong()));
                    log.assertNoFailures();
                    engines.add(new WeakReference<>(engine));
                }
                if (round % 5 == 4) {
                    System.gc();
                }
            }
        }
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        /*
         * Allow for a few engines and sources that are still reachable for unrelated reasons (e.g.
         * the source of the last round). Without code unloading, all sources stay reachable.
         */
        int liveEngines = countLive(engines);
        Assert.assertTrue(liveEngines + " of " + rounds + " dropped engines are still reachable, so their compiled code was not unloaded", liveEngines <= 2);
        int liveSources = countLive(sources);
        Assert.assertTrue(liveSources + " of " + rounds + " sources of dropped engines are still reachable, so their compiled code was not unloaded", liveSources <= rounds / 4);
    }

    private static int countLive(List<? extends WeakReference<?>> refs) {
        int live = 0;
        for (WeakReference<?> ref : refs) {
            if (ref.get() != null) {
                live++;
            }
        }
        return live;
    }

    /** Parses the engine log written with engine.TraceCompilation=true. */
    static final class CompilationLog extends OutputStream {
        // "opt done   engine=1  id=12    root sumOfSquares    |Tier 2|..." (SL roots are named "root <function>")
        private static final Pattern DONE = Pattern.compile("opt done\\s+engine=\\s*\\d+\\s+id=\\s*\\d+\\s+(?:root\\s+)?(\\S+)\\s+\\|Tier (\\d)");
        private static final Pattern EVENT = Pattern.compile("opt (deopt|inval\\.|failed)\\s+engine=\\s*\\d+\\s+id=\\s*\\d+\\s+(?:root\\s+)?(\\S+)");

        private final StringBuilder line = new StringBuilder();
        private final Map<String, Integer> lastTierCompilations = new HashMap<>();
        private final Map<String, Integer> deopts = new HashMap<>();
        private final List<String> failures = new ArrayList<>();
        /** The most recent log lines, for diagnostics. */
        private final ArrayDeque<String> recent = new ArrayDeque<>();

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                process(line.toString());
                line.setLength(0);
            } else {
                line.append((char) b);
            }
        }

        private void process(String l) {
            if (recent.size() == 20) {
                recent.removeFirst();
            }
            recent.addLast(l);
            Matcher m = DONE.matcher(l);
            if (m.find()) {
                if (m.group(2).equals("2")) {
                    lastTierCompilations.merge(m.group(1), 1, Integer::sum);
                }
                return;
            }
            m = EVENT.matcher(l);
            if (m.find()) {
                if (m.group(1).equals("failed")) {
                    failures.add(l);
                } else {
                    deopts.merge(m.group(2), 1, Integer::sum);
                }
            }
        }

        /** Number of finished last-tier (tier 2) compilations of the given SL function. */
        synchronized int compilations(String name) {
            return lastTierCompilations.getOrDefault(name, 0);
        }

        /** Number of deoptimizations and invalidations of the given SL function. */
        synchronized int deopts(String name) {
            return deopts.getOrDefault(name, 0);
        }

        synchronized void assertNoFailures() {
            Assert.assertTrue("compilation failures: " + failures, failures.isEmpty());
        }

        synchronized String recentLines() {
            return String.join(System.lineSeparator(), recent);
        }
    }

    /**
     * An engine that compiles within a few thousand calls and traces the compilations. The call
     * targets of the polyglot {@link Value#execute} wrappers are not compiled: they would inline the
     * SL function they call, which then may never get a last-tier compilation of its own.
     */
    private static Engine newEngine(CompilationLog log) {
        return Engine.newBuilder("sl").allowExperimentalOptions(true).logHandler(log).option("engine.TraceCompilation", "true").option("engine.CompilationFailureAction", "Print").option(
                        "engine.CompileOnly", "~org.graalvm.polyglot.Value<").option("engine.FirstTierCompilationThreshold", "50").option("engine.LastTierCompilationThreshold",
                                        "500").option("engine.WarnInterpreterOnly", "false").build();
    }

    private static Context newContext(Engine engine) {
        return Context.newBuilder("sl").engine(engine).build();
    }

    /** Evaluates an SL script and returns the SL function {@code name} that it defines. */
    private static Value function(Context context, String name, String script) {
        context.eval(Source.create("sl", script));
        Value f = context.getBindings("sl").getMember(name);
        Assert.assertTrue(name + " is not executable", f != null && f.canExecute());
        return f;
    }

    /** Calls {@code call} until the SL function {@code name} has been compiled (again) with the last tier. */
    private static void runUntilCompiled(CompilationLog log, String name, Runnable call) {
        int before = log.compilations(name);
        long deadline = System.currentTimeMillis() + COMPILATION_TIMEOUT_MILLIS;
        long calls = 0;
        while (log.compilations(name) == before) {
            for (int i = 0; i < 1000; i++) {
                call.run();
            }
            calls += 1000;
            if (System.currentTimeMillis() >= deadline) {
                Assert.fail(name + " was not compiled after " + calls + " calls. Most recent compilation log lines:" + System.lineSeparator() + log.recentLines());
            }
        }
    }

    /** Measures the run time of a test phase. */
    private static final class Timer {
        private final long start = System.currentTimeMillis();
        private long lastGC = start;

        boolean running() {
            return System.currentTimeMillis() - start < DURATION_MILLIS;
        }

        /** Triggers a {@link System#gc()} every {@code periodMillis}. */
        void gcEvery(long periodMillis) {
            long now = System.currentTimeMillis();
            if (now - lastGC > periodMillis) {
                System.gc();
                lastGC = now;
            }
        }
    }

    static volatile Object sink;

    /**
     * Allocates garbage on a few platform threads (keeping some of it alive for a while) so that GC
     * cycles (with evacuation) run while a test executes compiled code.
     */
    private static final class GarbageProducer implements AutoCloseable {
        private volatile boolean stop;
        private final List<Thread> threads = new ArrayList<>();

        GarbageProducer(int nThreads) {
            for (int t = 0; t < nThreads; t++) {
                Thread thread = new Thread(this::run, "garbage-" + t);
                thread.setDaemon(true);
                thread.start();
                threads.add(thread);
            }
        }

        private void run() {
            Object[] live = new Object[8 * 1024];
            long n = 0;
            while (!stop) {
                byte[] b = new byte[64 + (int) (n % 960)];
                if ((n & 7) == 0) {
                    live[(int) ((n * 0x9E3779B97F4A7C15L) >>> 51)] = b;
                }
                n++;
            }
            sink = live;
        }

        @Override
        public void close() {
            stop = true;
            boolean interrupted = false;
            for (Thread t : threads) {
                while (t.isAlive()) {
                    try {
                        t.join();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
