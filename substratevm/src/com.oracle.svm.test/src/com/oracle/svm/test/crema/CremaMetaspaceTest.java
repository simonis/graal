/*
 * Copyright (c) 2026, 2026, Oracle and/or its affiliates. All rights reserved.
 * Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
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
package com.oracle.svm.test.crema;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;

import org.graalvm.nativeimage.VMRuntime;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.oracle.svm.core.metaspace.Metaspace;
import com.oracle.svm.test.crema.CremaTestSupport.DirLoader;
import com.oracle.svm.test.crema.CremaTestSupport.GarbageProducer;

/**
 * Tests the metaspace of the GC with runtime class loading (Crema): classes defined at run time
 * get their DynamicHubs and metadata arrays allocated in the metaspace, and the GC must treat
 * references from the metaspace into the collected heap as roots.
 * <p>
 * The tests only run in images built with {@code -H:+UnlockExperimentalVMOptions
 * -H:+RuntimeClassLoading} and are ignored otherwise. The classes they define at run time are
 * {@code cremametaspace.Hello} and {@code cremametaspace.Holder}, which are not part of the image
 * (see {@link CremaPayloads}).
 * <p>
 * Metaspace exhaustion is tested in a separate process by {@link CremaMetaspaceExhaustionTest}.
 */
public class CremaMetaspaceTest {
    private Path payloads;

    @Before
    public void setUp() throws Exception {
        CremaTestSupport.assumeRuntimeClassLoading();
        payloads = CremaTestSupport.writePayloads();
    }

    @After
    public void tearDown() throws Exception {
        CremaTestSupport.deleteRecursively(payloads);
    }

    /** The payload classes are only available through the class files written at run time. */
    @Test
    public void testPayloadsAreNotInImage() {
        for (String name : new String[]{CremaPayloads.HELLO, CremaPayloads.HOLDER}) {
            Assert.assertThrows(name + " must not be part of the image", ClassNotFoundException.class, () -> Class.forName(name));
        }
    }

    /**
     * Defines a class at run time and calls it through an interface that is part of the image.
     * Defining the class allocates its DynamicHub (and the hub's type-check and reference-map
     * arrays) in the metaspace.
     */
    @Test
    public void testHello() throws Exception {
        DirLoader loader = new DirLoader(payloads);
        Class<?> c = loader.loadClass(CremaPayloads.HELLO);
        Assert.assertSame(loader, c.getClassLoader());
        IntUnaryOperator f = (IntUnaryOperator) c.getDeclaredConstructor().newInstance();
        Assert.assertEquals(41, f.applyAsInt(20));
    }

    /**
     * Checks that references from the metaspace into the collected heap are GC roots: the static
     * fields of a runtime-loaded class keep heap objects alive that are only reachable through the
     * class's DynamicHub. While GCs run, the test keeps replacing these objects and checks that all
     * of them are still intact. It also creates the array class of the runtime-loaded class
     * (another metaspace DynamicHub) and checks that weak references to metaspace objects are not
     * cleared while they are strongly reachable.
     */
    @Test
    public void testMetaspaceRoots() throws Exception {
        int rounds = 1000;
        DirLoader loader = new DirLoader(payloads);
        Class<?> c = loader.loadClass(CremaPayloads.HOLDER);
        Method replace = c.getMethod("replace", int.class);
        IntSupplier check = (IntSupplier) c.getDeclaredConstructor().newInstance();

        Object array = Array.newInstance(c, 16);
        for (int i = 0; i < 16; i++) {
            Array.set(array, i, c.getDeclaredConstructor().newInstance());
        }
        Class<?> arrayClass = array.getClass();
        Assert.assertSame(c, arrayClass.getComponentType());

        WeakReference<Class<?>> weakClass = new WeakReference<>(c);
        WeakReference<Class<?>> weakArrayClass = new WeakReference<>(arrayClass);
        WeakReference<ClassLoader> weakLoader = new WeakReference<>(loader);
        loader = null;

        try (GarbageProducer _ = new GarbageProducer(2)) {
            for (int round = 0; round < rounds; round++) {
                replace.invoke(null, round);
                Assert.assertEquals("objects reachable from the static fields of Holder are corrupted in round " + round, 0, check.getAsInt());
                if (round % 200 == 199) {
                    System.gc();
                }
            }
        }
        System.gc();
        Assert.assertEquals("objects reachable from the static fields of Holder are corrupted after the final GC", 0, check.getAsInt());

        Assert.assertSame("weak reference to a strongly reachable runtime-loaded class was cleared", c, weakClass.get());
        Assert.assertSame("weak reference to a strongly reachable runtime-loaded array class was cleared", arrayClass, weakArrayClass.get());
        Assert.assertSame("weak reference to the class loader of a strongly reachable class was cleared", c.getClassLoader(), weakLoader.get());
        Assert.assertEquals(CremaPayloads.HOLDER, c.getName());
        Assert.assertEquals(7, c.getDeclaredFields().length);
        for (int i = 0; i < 16; i++) {
            Assert.assertTrue("array element " + i + " has the wrong type", c.isInstance(Array.get(array, i)));
        }
    }

    /**
     * Fills several metaspace regions: several threads concurrently define the same classes in
     * many class loaders while other threads allocate garbage, so that metaspace allocation
     * overlaps with GC cycles. At the end, all classes must still work.
     */
    @Test
    public void testManyClasses() throws Exception {
        int nThreads = 4;
        int loadersPerThread = 500;
        List<List<Class<?>>> classes = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();

        try (GarbageProducer _ = new GarbageProducer(2)) {
            for (int t = 0; t < nThreads; t++) {
                List<Class<?>> mine = new ArrayList<>();
                classes.add(mine);
                Thread thread = new Thread(() -> {
                    try {
                        for (int i = 0; i < loadersPerThread && failure.get() == null; i++) {
                            DirLoader loader = new DirLoader(payloads);
                            Class<?> hello = loader.loadClass(CremaPayloads.HELLO);
                            IntUnaryOperator f = (IntUnaryOperator) hello.getDeclaredConstructor().newInstance();
                            Assert.assertEquals(2 * i + 1, f.applyAsInt(i));
                            mine.add(hello);
                            if (i % 100 == 0) {
                                /* Holder's static initializer allocates heap objects only reachable through the class. */
                                Class<?> holder = loader.loadClass(CremaPayloads.HOLDER);
                                Assert.assertEquals("Holder is corrupted", 0, ((IntSupplier) holder.getDeclaredConstructor().newInstance()).getAsInt());
                                mine.add(holder);
                            }
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }, "crema-loader-" + t);
                thread.start();
                threads.add(thread);
            }
            for (Thread thread : threads) {
                thread.join();
            }
        }
        if (failure.get() != null) {
            throw new AssertionError("class loading thread failed", failure.get());
        }

        System.gc();
        int count = 0;
        for (List<Class<?>> mine : classes) {
            for (Class<?> c : mine) {
                Object o = c.getDeclaredConstructor().newInstance();
                if (o instanceof IntUnaryOperator f) {
                    Assert.assertEquals("Hello returned a wrong value after GC", 2 * count + 1, f.applyAsInt(count));
                } else {
                    Assert.assertEquals("Holder is corrupted after GC", 0, ((IntSupplier) o).getAsInt());
                }
                Assert.assertTrue("wrong class loader", c.getClassLoader() instanceof DirLoader);
                count++;
            }
        }
        Assert.assertEquals(nThreads * (loadersPerThread + loadersPerThread / 100), count);
    }

    private record Generation(List<WeakReference<ClassLoader>> loaders, List<WeakReference<Class<?>>> classes) {
    }

    /**
     * Class unloading scenario: classes are defined in class loaders that become unreachable, then
     * GCs run. The test checks that this is safe (no crash, no corruption, the classes can be
     * defined again) and that a class is never unloaded while one of its instances, its class
     * loader, or the class itself is strongly reachable.
     * <p>
     * Native Image does not support unloading runtime-loaded classes yet: a class loader stays
     * reachable through the DynamicHubs of its classes, which are metaspace objects and therefore
     * always alive. The test therefore also checks that nothing is unloaded. This check has to be
     * changed once class unloading is supported.
     */
    @Test
    public void testClassUnloading() throws Exception {
        int nLoaders = 100;
        int cycles = 3;

        /* Kept strongly reachable: must never be unloaded. */
        DirLoader keptLoader = new DirLoader(payloads);
        WeakReference<Class<?>> weakKeptByLoader = new WeakReference<>(keptLoader.loadClass(CremaPayloads.HELLO));
        Object keptInstance = new DirLoader(payloads).loadClass(CremaPayloads.HOLDER).getDeclaredConstructor().newInstance();
        WeakReference<Class<?>> weakKeptByInstance = new WeakReference<>(keptInstance.getClass());

        int unloadedLoaders = 0;
        int unloadedClasses = 0;
        try (GarbageProducer _ = new GarbageProducer(1)) {
            for (int cycle = 0; cycle < cycles; cycle++) {
                Generation g = defineAndDrop(nLoaders);
                for (int i = 0; i < 3; i++) {
                    System.gc();
                }
                for (int i = 0; i < g.loaders().size(); i++) {
                    boolean loaderGone = g.loaders().get(i).get() == null;
                    boolean helloGone = g.classes().get(2 * i).get() == null;
                    boolean holderGone = g.classes().get(2 * i + 1).get() == null;
                    if (loaderGone) {
                        Assert.assertTrue("class loader was unloaded, but not all of its classes", helloGone && holderGone);
                        unloadedLoaders++;
                    }
                    unloadedClasses += (helloGone ? 1 : 0) + (holderGone ? 1 : 0);
                }
                /* The same class names can be defined again in new loaders. */
                Class<?> again = new DirLoader(payloads).loadClass(CremaPayloads.HELLO);
                Assert.assertEquals("redefined Hello returned a wrong value", 2 * cycle + 1, ((IntUnaryOperator) again.getDeclaredConstructor().newInstance()).applyAsInt(cycle));
            }
        }

        Assert.assertNotNull("class of a strongly reachable loader was unloaded", weakKeptByLoader.get());
        Assert.assertSame(keptLoader, weakKeptByLoader.get().getClassLoader());
        Assert.assertSame("class of a strongly reachable instance was unloaded", keptInstance.getClass(), weakKeptByInstance.get());
        Assert.assertEquals("Holder kept alive by its instance is corrupted", 0, ((IntSupplier) keptInstance).getAsInt());

        Assert.assertEquals("class loaders were unloaded although class unloading is not supported", 0, unloadedLoaders);
        Assert.assertEquals("classes were unloaded although class unloading is not supported", 0, unloadedClasses);
    }

    /** Defines Hello and Holder in new loaders, uses them, and keeps only weak references. */
    private Generation defineAndDrop(int nLoaders) throws Exception {
        List<WeakReference<ClassLoader>> loaders = new ArrayList<>();
        List<WeakReference<Class<?>>> classes = new ArrayList<>();
        for (int i = 0; i < nLoaders; i++) {
            DirLoader loader = new DirLoader(payloads);
            Class<?> hello = loader.loadClass(CremaPayloads.HELLO);
            Class<?> holder = loader.loadClass(CremaPayloads.HOLDER);
            Assert.assertEquals("Hello returned a wrong value", 2 * i + 1, ((IntUnaryOperator) hello.getDeclaredConstructor().newInstance()).applyAsInt(i));
            Assert.assertEquals("Holder is corrupted", 0, ((IntSupplier) holder.getDeclaredConstructor().newInstance()).getAsInt());
            loaders.add(new WeakReference<>(loader));
            classes.add(new WeakReference<>(hello));
            classes.add(new WeakReference<>(holder));
        }
        return new Generation(loaders, classes);
    }

    /**
     * Walks the metaspace and the collected heap: writes heap dumps (which visit all metaspace
     * objects and the heap objects reachable through them) while GC cycles may be running.
     */
    @Test
    public void testHeapDump() throws Exception {
        List<Class<?>> holders = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            DirLoader loader = new DirLoader(payloads);
            loader.loadClass(CremaPayloads.HELLO);
            holders.add(loader.loadClass(CremaPayloads.HOLDER));
        }
        Path dir = Files.createTempDirectory("crema-heapdump");
        try (GarbageProducer _ = new GarbageProducer(2)) {
            for (int i = 0; i < 2; i++) {
                Path file = dir.resolve("dump" + i + ".hprof");
                /* live=false: no GC before the dump, so the dump may interrupt a concurrent cycle. */
                VMRuntime.dumpHeap(file.toString(), i == 0);
                Assert.assertTrue("empty heap dump", Files.size(file) > 0);
                Files.delete(file);
            }
        } finally {
            CremaTestSupport.deleteRecursively(dir);
        }
        for (Class<?> c : holders) {
            Assert.assertEquals("Holder is corrupted", 0, ((IntSupplier) c.getDeclaredConstructor().newInstance()).getAsInt());
        }
    }

    /**
     * Exercises the internal metaspace allocation API directly, to cover cases that runtime class
     * loading alone does not reach: small and large byte and int arrays (humongous, i.e. larger
     * than a heap region, if the GC's metaspace supports it), metaspace instances whose reference
     * field points into the collected heap (stores into them go through the GC barriers, and the
     * references are GC roots), and isInAddressSpace/isInAllocatedMemory.
     */
    @Test
    public void testMetaspaceApi() throws Exception {
        int rounds = 200;
        Metaspace metaspace = Metaspace.singleton();

        byte[] small = metaspace.allocateByteArray(100);
        int[] smallInts = metaspace.allocateIntArray(100);
        byte[] largeBytes = metaspace.allocateByteArray(CremaTestSupport.largeByteArrayLength());
        int[] largeInts = metaspace.allocateIntArray(CremaTestSupport.largeIntArrayLength());
        fill(small, 1);
        fill(smallInts, 2);
        fill(largeBytes, 3);
        fill(largeInts, 4);
        for (Object o : new Object[]{small, smallInts, largeBytes, largeInts}) {
            Assert.assertTrue("not in the metaspace address space", metaspace.isInAddressSpace(o));
            Assert.assertTrue("not in allocated metaspace memory", metaspace.isInAllocatedMemory(o));
        }
        Assert.assertFalse("heap object in the metaspace address space", metaspace.isInAddressSpace(new byte[1]));

        /* Metaspace instances whose reference field points into the heap. */
        AtomicReference<Object[]>[] boxes = allocateBoxes(metaspace, 64);
        for (int i = 0; i < boxes.length; i++) {
            Assert.assertTrue("box not in allocated metaspace memory", metaspace.isInAllocatedMemory(boxes[i]));
        }

        try (GarbageProducer _ = new GarbageProducer(2)) {
            for (int round = 0; round < rounds; round++) {
                for (int i = 0; i < boxes.length; i++) {
                    /* Fresh (young) heap objects that are only reachable through metaspace objects. */
                    int[] numbers = new int[i + 1];
                    fill(numbers, round + i);
                    String text = new StringBuilder().append(round).append('/').append(i).toString();
                    boxes[i].set(new Object[]{numbers, text, boxes[(i + 1) % boxes.length]});
                }
                if (round % 50 == 49) {
                    System.gc();
                }
                for (int i = 0; i < boxes.length; i++) {
                    checkBox(boxes, i, round);
                }
            }
        }
        System.gc();
        check(small, 1, "small byte[]");
        check(smallInts, 2, "small int[]");
        check(largeBytes, 3, "large byte[]");
        check(largeInts, 4, "large int[]");
        for (int i = 0; i < boxes.length; i++) {
            checkBox(boxes, i, rounds - 1);
        }
    }

    /**
     * Allocates {@link AtomicReference} instances in the metaspace. A JDK class is used because
     * {@link Metaspace#allocateObject} with a class of this test confuses type checks on the result.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static AtomicReference<Object[]>[] allocateBoxes(Metaspace metaspace, int n) {
        AtomicReference<Object[]>[] boxes = new AtomicReference[n];
        for (int i = 0; i < n; i++) {
            boxes[i] = metaspace.allocateObject(AtomicReference.class);
        }
        return boxes;
    }

    static void checkBox(AtomicReference<Object[]>[] boxes, int i, int round) {
        Object[] content = boxes[i].get();
        check((int[]) content[0], round + i, "box " + i + " numbers in round " + round);
        Assert.assertEquals("box " + i + " text in round " + round, round + "/" + i, content[1]);
        Assert.assertSame("box " + i + " link in round " + round, boxes[(i + 1) % boxes.length], content[2]);
    }

    static void fill(byte[] a, int seed) {
        for (int i = 0; i < a.length; i++) {
            a[i] = (byte) (seed * 31 + i);
        }
    }

    static void fill(int[] a, int seed) {
        for (int i = 0; i < a.length; i++) {
            a[i] = seed * 31 + i;
        }
    }

    static void check(byte[] a, int seed, String what) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != (byte) (seed * 31 + i)) {
                Assert.fail(what + " corrupted at index " + i);
            }
        }
    }

    static void check(int[] a, int seed, String what) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != seed * 31 + i) {
                Assert.fail(what + " corrupted at index " + i);
            }
        }
    }
}
