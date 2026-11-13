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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.graalvm.nativeimage.ImageInfo;
import org.junit.Assume;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.hub.RuntimeClassLoading;

/** Shared helpers of the Crema metaspace tests. */
final class CremaTestSupport {
    private CremaTestSupport() {
    }

    /**
     * Skips the calling test unless it runs in a native image that was built with runtime class
     * loading ({@code -H:+UnlockExperimentalVMOptions -H:+RuntimeClassLoading}).
     */
    static void assumeRuntimeClassLoading() {
        Assume.assumeTrue("Needs a native image", ImageInfo.inImageRuntimeCode());
        Assume.assumeTrue("Needs an image built with -H:+RuntimeClassLoading", RuntimeClassLoading.isSupported());
    }

    /**
     * The metaspaces of G1 and Shenandoah support humongous objects (larger than a heap region).
     * The metaspace of the serial GC only supports objects that fit into an aligned heap chunk.
     */
    static boolean supportsHumongousMetaspaceObjects() {
        return SubstrateOptions.useG1GC() || SubstrateOptions.useShenandoahGC();
    }

    /** Length of a large metaspace byte array, humongous if the metaspace supports it. */
    static int largeByteArrayLength() {
        return supportsHumongousMetaspaceObjects() ? 3 * 1024 * 1024 + 17 : 64 * 1024 + 17;
    }

    /** Length of a large metaspace int array, humongous if the metaspace supports it. */
    static int largeIntArrayLength() {
        return supportsHumongousMetaspaceObjects() ? 600_000 : 16_000;
    }

    /**
     * Writes the class files embedded in {@link CremaPayloads} to a new temporary directory, from
     * which {@link DirLoader} can define them.
     */
    static Path writePayloads() throws IOException {
        Path dir = Files.createTempDirectory("crema-metaspace");
        Path packageDir = Files.createDirectory(dir.resolve(CremaPayloads.PACKAGE));
        Files.write(packageDir.resolve("Hello.class"), CremaPayloads.HELLO_CLASS);
        Files.write(packageDir.resolve("Holder.class"), CremaPayloads.HOLDER_CLASS);
        return dir;
    }

    static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    /**
     * Defines the classes whose class files are in a directory, without delegating them to the
     * parent first. Every instance defines its own copies, i.e. new metaspace objects.
     */
    static final class DirLoader extends ClassLoader {
        private final Path dir;

        DirLoader(Path dir) {
            super(CremaTestSupport.class.getClassLoader());
            this.dir = dir;
        }

        private Path classFile(String name) {
            return dir.resolve(name.replace('.', '/') + ".class");
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            try {
                byte[] bytes = Files.readAllBytes(classFile(name));
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null && Files.exists(classFile(name))) {
                    c = findClass(name);
                }
                if (c == null) {
                    c = super.loadClass(name, false);
                }
                return c;
            }
        }
    }

    static volatile Object sink;

    /**
     * Allocates garbage on a few platform threads (keeping some of it alive for a while) so that GC
     * cycles (for concurrent GCs with evacuation) run while a test executes.
     */
    static final class GarbageProducer implements AutoCloseable {
        private volatile boolean stop;
        private final List<Thread> threads = new ArrayList<>();

        GarbageProducer(int nThreads) {
            for (int t = 0; t < nThreads; t++) {
                Thread thread = new Thread(this::run, "crema-garbage-" + t);
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
            for (Thread t : threads) {
                boolean interrupted = false;
                while (t.isAlive()) {
                    try {
                        t.join();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
