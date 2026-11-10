/*
 * Copyright (c) 2026, 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.test.gc;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.junit.Assume;
import org.junit.Test;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.heap.ObjectReferenceVisitor;
import com.oracle.svm.core.heap.StoredContinuation;
import com.oracle.svm.core.heap.StoredContinuationAccess;
import com.oracle.svm.core.thread.ContinuationInternals;
import com.oracle.svm.core.thread.Target_jdk_internal_vm_Continuation;
import com.oracle.svm.guest.staging.core.graal.KnownIntrinsics;
import com.oracle.svm.shared.NeverInline;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.SubstrateUtil;

/**
 * Checks that freezing a virtual thread (copying its frames into a {@link StoredContinuation})
 * keeps null references in uncompressed frame slots intact.
 *
 * <p>
 * Uncompressed references on Native Image stacks may encode null as the heap base. When the GC
 * applies barriers to the references of a frozen chunk (G1 for chunks outside young regions, i.e.
 * humongous chunks; Shenandoah for every chunk while it has forwarded objects), it must not write
 * back a raw 0 for such a null. Compiled code does not recognize a raw 0 in these slots as null and
 * crashes after the frames are thawed.
 *
 * <p>
 * To fail instead of crash, every virtual thread parks at the bottom of a recursion and waits until
 * the test thread has inspected all uncompressed reference slots of its frozen frames. A slot with
 * the raw value 0 fails the test, and the affected thread is never resumed.
 *
 * <p>
 * For Shenandoah, the test can only detect the problem if GC cycles run while it executes, e.g.
 * with {@code -XX:ShenandoahGCHeuristics=aggressive} or a heap of a few GB. A platform thread
 * allocates garbage to trigger them. The serial GC and Shenandoah's passive mode never apply such
 * barriers.
 */
public class VirtualThreadStoredContinuationNullTest {

    @TargetClass(className = "java.lang.VirtualThread")
    static final class Target_java_lang_VirtualThread_VirtualThreadStoredContinuationNullTest {
        @Alias //
        Target_jdk_internal_vm_Continuation cont;
    }

    /**
     * Workers stop after this time even if they have not finished all iterations, so that slow
     * configurations (e.g. with GC verification) do not take too long.
     */
    private static final long TIME_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final long TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(5);

    /** Always null, but the compiler cannot know that. */
    private static volatile Object alwaysNull;
    private static volatile Object sink;

    /** Many shallow virtual threads: freezes overlap with Shenandoah's evacuation phases. */
    @Test
    public void manyShallowThreads() throws Throwable {
        run(64, 1000, 3, 0);
    }

    /**
     * Few deep virtual threads: the frozen frames (640 KB) are larger than half a G1 region (with
     * the 1 MB regions of heaps up to 2 GB), so G1 allocates a humongous chunk and applies its
     * barriers on every freeze.
     */
    @Test
    public void fewDeepThreads() throws Throwable {
        run(4, 50, 0, 640 * 1024);
    }

    private static final class Payload {
        final long id;
        final long[] data;

        Payload(long id) {
            this.id = id;
            this.data = new long[16];
            for (int i = 0; i < data.length; i++) {
                data[i] = id * 31 + i;
            }
        }

        void check(long expectedId) {
            if (id != expectedId) {
                throw new AssertionError("payload id " + id + " != " + expectedId);
            }
            for (int i = 0; i < data.length; i++) {
                if (data[i] != id * 31 + i) {
                    throw new AssertionError("payload " + id + " data[" + i + "] corrupted: " + data[i]);
                }
            }
        }
    }

    private static final class Worker implements Runnable {
        final long base;
        final int iterations;
        final int minDepth;
        final int minStackBytes;
        final AtomicReference<Throwable> failure;
        final long endTime;
        Thread thread;

        /** Incremented by the worker before it parks for inspection. */
        volatile long parkedSeq;
        /** Set by the test thread to {@link #parkedSeq} once the frozen frames are verified. */
        volatile long releasedSeq;
        /** Last {@link #parkedSeq} that the test thread has inspected. */
        long checkedSeq;
        volatile boolean done;

        Worker(long base, int iterations, int minDepth, int minStackBytes, AtomicReference<Throwable> failure, long endTime) {
            this.base = base;
            this.iterations = iterations;
            this.minDepth = minDepth;
            this.minStackBytes = minStackBytes;
            this.failure = failure;
            this.endTime = endTime;
        }

        @Override
        public void run() {
            try {
                Payload a = new Payload(base);
                for (int i = 0; i < iterations && System.nanoTime() - endTime < 0; i++) {
                    Payload c = new Payload(base + 1 + i);
                    // The stack grows downwards. The frames are only moved while parked.
                    long stopSP = KnownIntrinsics.readStackPointer().rawValue() - minStackBytes;
                    nested(a, c, i, 0, stopSP);
                    a.check(base);
                    c.check(base + 1 + i);
                    sink = new Object[8];
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            } finally {
                done = true;
            }
        }

        /** Many frames with live non-null and null references across the park. */
        private void nested(Payload a, Payload c, int i, int d, long stopSP) {
            Object nullRef = alwaysNull;
            Payload local = new Payload(-base - i - d);
            if (d < minDepth || Long.compareUnsigned(KnownIntrinsics.readStackPointer().rawValue(), stopSP) > 0) {
                nested(a, c, i, d + 1, stopSP);
            } else {
                parkForInspection();
            }
            checkNull(nullRef, d);
            local.check(-base - i - d);
            a.check(base);
            c.check(base + 1 + i);
        }

        private void parkForInspection() {
            long seq = parkedSeq + 1;
            parkedSeq = seq;
            while (releasedSeq != seq) {
                LockSupport.park();
            }
        }
    }

    @NeverInline("Keep the reference in an uncompressed argument slot.")
    private static void checkNull(Object o, int depth) {
        if (o != null) {
            throw new AssertionError("null reference in frame at depth " + depth + " became non-null after thaw");
        }
    }

    private static void run(int threads, int iterations, int minDepth, int minStackBytes) throws Throwable {
        Assume.assumeTrue("Inspects Native Image internals", ImageInfo.inImageRuntimeCode());
        Assume.assumeTrue("Without a heap base, null is always encoded as 0", KnownIntrinsics.heapBase().isNonNull());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Worker[] workers = new Worker[threads];
        long endTime = System.nanoTime() + TIME_BUDGET_NANOS;
        for (int t = 0; t < threads; t++) {
            workers[t] = new Worker(t * 1_000_000L, iterations, minDepth, minStackBytes, failure, endTime);
            workers[t].thread = Thread.ofVirtual().name("vt-" + t).start(workers[t]);
        }

        FrameSlotChecker checker = new FrameSlotChecker();
        GarbageProducer garbageProducer = new GarbageProducer();
        Thread garbageThread = Thread.ofPlatform().name("garbage-producer").daemon().start(garbageProducer);
        try {
            checkAndReleaseWorkers(workers, checker, failure);
        } finally {
            garbageProducer.stop = true;
            garbageThread.join();
        }
        for (Worker w : workers) {
            w.thread.join();
        }
        if (checker.checkedSlots == 0) {
            throw new AssertionError("no uncompressed reference slots found in frozen frames");
        }
        if (checker.maxFramesSize < minStackBytes) {
            throw new AssertionError("frozen frames too small: " + checker.maxFramesSize + " < " + minStackBytes + " bytes");
        }
    }

    /**
     * Allocates quickly while keeping some objects alive, so that the GC runs often and has
     * objects to evacuate (which is when Shenandoah applies barriers to frozen frames).
     */
    private static final class GarbageProducer implements Runnable {
        volatile boolean stop;

        @Override
        public void run() {
            Object[] live = new Object[16 * 1024];
            long n = 0;
            while (!stop) {
                byte[] b = new byte[64 + (int) (n % 960)];
                if ((n & 7) == 0) {
                    live[(int) ((n * 0x9E3779B97F4A7C15L) >>> 50)] = b;
                }
                n++;
            }
            sink = live;
        }
    }

    private static void checkAndReleaseWorkers(Worker[] workers, FrameSlotChecker checker, AtomicReference<Throwable> failure) throws Throwable {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        boolean allDone = false;
        while (!allDone && failure.get() == null) {
            allDone = true;
            for (Worker w : workers) {
                if (w.done) {
                    continue;
                }
                allDone = false;
                long seq = w.parkedSeq;
                if (seq == w.checkedSeq) {
                    continue;
                }
                StoredContinuation stored = getStoredContinuation(w.thread);
                if (stored == null) {
                    // Not frozen yet.
                    continue;
                }
                String error = checker.check(stored);
                if (error != null) {
                    /*
                     * Never resume this thread (or any other): thawing the corrupted frames would
                     * crash the process instead of failing this test.
                     */
                    throw new AssertionError(w.thread.getName() + " (park " + seq + "): " + error);
                }
                if (getStoredContinuation(w.thread) != stored) {
                    // Resumed and frozen again in the meantime, inspect the new frames.
                    continue;
                }
                w.checkedSeq = seq;
                w.releasedSeq = seq;
                LockSupport.unpark(w.thread);
            }
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("timeout: virtual threads did not finish");
            }
            Thread.onSpinWait();
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private static StoredContinuation getStoredContinuation(Thread virtualThread) {
        Target_jdk_internal_vm_Continuation cont = SubstrateUtil.cast(virtualThread, Target_java_lang_VirtualThread_VirtualThreadStoredContinuationNullTest.class).cont;
        return ContinuationInternals.getStoredContinuation(cont);
    }

    /** Finds uncompressed reference slots in frozen frames that contain a raw 0. */
    private static final class FrameSlotChecker implements ObjectReferenceVisitor {
        long checkedSlots;
        int maxFramesSize;
        private int zeroSlots;
        private int firstZeroOffset;

        /** Returns an error message, or null if all frame slots are intact. */
        String check(StoredContinuation stored) {
            zeroSlots = 0;
            firstZeroOffset = -1;
            int framesSize = walk(stored);
            maxFramesSize = Math.max(maxFramesSize, framesSize);
            if (zeroSlots == 0) {
                return null;
            }
            return zeroSlots + " uncompressed reference slot(s) in the frozen frames contain a raw 0 instead of the heap base " +
                            "as encoding of null (first at frame offset " + firstZeroOffset + " of " + framesSize + " bytes)";
        }

        @Uninterruptible(reason = "The StoredContinuation must not move while its frames are inspected.")
        private int walk(StoredContinuation stored) {
            StoredContinuationAccess.walkReferences(stored, this);
            return StoredContinuationAccess.getFramesSizeInBytes(stored);
        }

        @Override
        @Uninterruptible(reason = "Called while walking a StoredContinuation.", callerMustBe = true)
        public void visitObjectReferences(Pointer firstObjRef, boolean compressed, int referenceSize, Object holderObject, int count) {
            if (compressed) {
                // Compressed references encode null as 0.
                return;
            }
            for (int i = 0; i < count; i++) {
                Pointer slot = firstObjRef.add(i * referenceSize);
                UnsignedWord value = slot.readWord(0);
                checkedSlots++;
                if (value.equal(0)) {
                    if (zeroSlots == 0) {
                        firstZeroOffset = (int) slot.subtract(StoredContinuationAccess.getFramesStart((StoredContinuation) holderObject)).rawValue();
                    }
                    zeroSlots++;
                }
            }
        }

        @Override
        @Uninterruptible(reason = "Called while walking a StoredContinuation.", callerMustBe = true)
        public void visitDerivedReference(Pointer baseObjRef, Pointer derivedObjRef, boolean compressed, Object holderObject) {
            // The base reference is visited separately, derived references are never null-encoded.
        }
    }
}
