/*
 * Copyright (c) 2025, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.gc.shenandoah;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.word.Pointer;

import com.oracle.svm.core.gc.shenandoah.nativelib.ShenandoahLibrary;
import com.oracle.svm.core.gc.shenandoah.nativelib.ShenandoahStructs.ShenandoahRegionBoundaries;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.guest.staging.core.heap.RestrictHeapAccess;
import com.oracle.svm.guest.staging.core.heap.RestrictHeapAccess.Access;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.core.memory.NullableNativeMemory;
import com.oracle.svm.core.nmt.NmtCategory;
import com.oracle.svm.core.thread.RecurringCallbackSupport;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.guest.staging.core.graal.KnownIntrinsics;

import org.graalvm.word.impl.Word;

public class ShenandoahHeapWalker {
    private static final OutOfMemoryError OUT_OF_MEMORY_ERROR = new OutOfMemoryError("Ran out of native memory while preparing the heap walk.");

    public static void walkCollectedHeap(ObjectVisitor visitor) {
        RecurringCallbackSupport.suspendCallbackTimer("Recurring callbacks could allocate.");
        try {
            ShenandoahImageHeapInfo imageHeapInfo = ShenandoahHeap.getImageHeapInfo();
            int fromRegion = ShenandoahMetaspace.getRegionCount() + imageHeapInfo.getNumRegions();
            ShenandoahCommittedMemoryProvider memoryProvider = ImageSingletons.lookup(ShenandoahCommittedMemoryProvider.class);
            walkRegions(visitor, fromRegion, memoryProvider.getMaxRegions(), true);
        } finally {
            RecurringCallbackSupport.resumeCallbackTimer();
        }
    }

    public static void walkMetaspace(ObjectVisitor visitor) {
        RecurringCallbackSupport.suspendCallbackTimer("Recurring callbacks could allocate.");
        try {
            walkRegions(visitor, 0, ShenandoahMetaspace.getRegionCount(), false);
        } finally {
            RecurringCallbackSupport.resumeCallbackTimer();
        }
    }

    /**
     * Visits the objects in the given regions. If {@code reachableOnly} is true, only objects that
     * are reachable from the GC roots are visited, like HotSpot's {@code
     * ShenandoahHeap::object_iterate()} does. A linear walk over all objects is not safe in the
     * collected heap: dead objects may reference reclaimed memory, and if the safepoint interrupts
     * a concurrent cycle, the headers of evacuated objects hold forwarding pointers instead of hubs.
     */
    @RestrictHeapAccess(access = Access.NO_ALLOCATION, reason = "Allocations could change the heap regions")
    private static void walkRegions(ObjectVisitor visitor, int fromRegion, int toRegion, boolean reachableOnly) {
        assert RecurringCallbackSupport.isCallbackUnsupportedOrTimerSuspended();
        VMOperation.guaranteeInProgressAtSafepoint("must only be executed at a safepoint");

        ShenandoahCommittedMemoryProvider memoryProvider = ImageSingletons.lookup(ShenandoahCommittedMemoryProvider.class);
        int maxRegions = memoryProvider.getMaxRegions();

        ShenandoahRegionBoundaries regionBoundaries = NullableNativeMemory.calloc(Word.unsigned(maxRegions).multiply(SizeOf.get(ShenandoahRegionBoundaries.class)), NmtCategory.GC);
        if (regionBoundaries.isNull()) {
            throw OUT_OF_MEMORY_ERROR;
        }

        try {
            if (reachableOnly) {
                if (!ShenandoahLibrary.prepareHeapWalk(regionBoundaries)) {
                    throw OUT_OF_MEMORY_ERROR;
                }
                try {
                    walkReachableObjects(visitor, regionBoundaries, fromRegion, toRegion);
                } finally {
                    ShenandoahLibrary.finishHeapWalk();
                }
            } else {
                ShenandoahLibrary.getRegionBoundaries(regionBoundaries);
                walkAllObjects(visitor, regionBoundaries, fromRegion, toRegion);
            }
        } finally {
            NullableNativeMemory.free(regionBoundaries);
        }
    }

    @RestrictHeapAccess(access = Access.NO_ALLOCATION, reason = "Allocations could change the heap regions")
    private static void walkReachableObjects(ObjectVisitor visitor, ShenandoahRegionBoundaries regionBoundaries, int fromRegion, int toRegion) {
        ShenandoahHeap heap = ShenandoahHeap.get();
        Pointer bitmap = heap.getAuxBitmapAddress();
        /* The bitmap covers the heap reservation, which starts after the null regions. */
        Pointer coveredStart = KnownIntrinsics.heapBase().add(ShenandoahHeap.getNullRegionSize());
        for (int i = fromRegion; i < toRegion; i++) {
            ShenandoahRegionBoundaries bounds = regionBoundaries.addressOf(i);
            if (bounds.bottom().equal(0)) {
                continue;
            }

            Pointer top = bounds.top();
            Pointer cur = nextReachableObject(bitmap, coveredStart, bounds.bottom(), top);
            while (cur.belowThan(top)) {
                Object o = cur.toObject();
                visitor.visitObject(o);
                cur = nextReachableObject(bitmap, coveredStart, LayoutEncoding.getObjectEndInGC(o), top);
            }
        }
    }

    /**
     * Returns the start of the first object in {@code [addr, limit)} that is marked in the
     * auxiliary bitmap filled by {@link ShenandoahLibrary#prepareHeapWalk}, or {@code limit} if
     * there is none. Same as the C++ {@code MarkBitMap::get_next_marked_addr()}: bit {@code i}
     * stands for the address {@code coveredStart + (i << ShenandoahConstants.auxBitmapShift())}
     * and is bit {@code i % 64} of the 64-bit word {@code i / 64}.
     */
    private static Pointer nextReachableObject(Pointer bitmap, Pointer coveredStart, Pointer addr, Pointer limit) {
        if (addr.aboveOrEqual(limit)) {
            return limit;
        }
        int shift = ShenandoahConstants.auxBitmapShift();
        assert addr.subtract(coveredStart).unsignedRemainder(1 << shift).equal(0) : "objects start at bit boundaries";
        long bit = addr.subtract(coveredStart).rawValue() >>> shift;
        long endBit = limit.subtract(coveredStart).rawValue() >>> shift;
        long lastWordIndex = (endBit - 1) >>> 6;

        long wordIndex = bit >>> 6;
        long word = readBitmapWord(bitmap, wordIndex) & (-1L << (bit & 63));
        while (word == 0) {
            wordIndex++;
            if (wordIndex > lastWordIndex) {
                return limit;
            }
            word = readBitmapWord(bitmap, wordIndex);
        }
        long found = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
        return found < endBit ? coveredStart.add(Word.unsigned(found << shift)) : limit;
    }

    private static long readBitmapWord(Pointer bitmap, long wordIndex) {
        return bitmap.readLong(Word.unsigned(wordIndex).shiftLeft(3));
    }

    @RestrictHeapAccess(access = Access.NO_ALLOCATION, reason = "Allocations could change the heap regions")
    private static void walkAllObjects(ObjectVisitor visitor, ShenandoahRegionBoundaries regionBoundaries, int fromRegion, int toRegion) {
        for (int i = fromRegion; i < toRegion; i++) {
            ShenandoahRegionBoundaries bounds = regionBoundaries.addressOf(i);
            if (bounds.bottom().equal(0)) {
                continue;
            }

            Word top = bounds.top();
            Pointer cur = bounds.bottom();
            while (cur.belowThan(top)) {
                Object o = cur.toObject();
                visitor.visitObject(o);
                cur = LayoutEncoding.getObjectEndInGC(o);
            }
        }
    }
}
