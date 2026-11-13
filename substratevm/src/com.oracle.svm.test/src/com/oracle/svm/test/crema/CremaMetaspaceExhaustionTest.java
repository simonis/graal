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

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntUnaryOperator;

import org.graalvm.nativeimage.ProcessProperties;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.oracle.svm.core.metaspace.Metaspace;
import com.oracle.svm.test.crema.CremaTestSupport.DirLoader;

/**
 * Exhausts the metaspace, which must throw an {@link OutOfMemoryError} (also when defining a
 * class) instead of crashing, and must not affect existing metaspace objects.
 * <p>
 * The metaspace cannot be freed, so the test exhausts it in a child process (this image, started
 * with {@code --run-explicit} for this test only) to not affect other tests that define classes at
 * run time. The child gets the VM options of the parent, so that it uses the same GC
 * configuration. Like {@link CremaMetaspaceTest}, the test only runs in images built with
 * {@code -H:+UnlockExperimentalVMOptions -H:+RuntimeClassLoading} and is ignored otherwise.
 */
public class CremaMetaspaceExhaustionTest {
    private static final String CHILD_PROPERTY = "svm.test.crema.metaspaceExhaustionChild";

    @Before
    public void setUp() {
        CremaTestSupport.assumeRuntimeClassLoading();
    }

    @Test
    public void testMetaspaceExhaustion() throws Exception {
        if (Boolean.getBoolean(CHILD_PROPERTY)) {
            exhaustMetaspace();
            return;
        }

        List<String> command = new ArrayList<>();
        command.add(ProcessProperties.getExecutableName());
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.add("-D" + CHILD_PROPERTY + "=true");
        command.add("--run-explicit");
        command.add(CremaMetaspaceExhaustionTest.class.getName());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        Assert.assertEquals("Child native image " + command + " failed:\n" + output, 0, exitCode);
        Assert.assertTrue("Child native image did not run the test:\n" + output, output.contains("metaspace exhausted after "));
    }

    /** Runs in the child process. */
    private static void exhaustMetaspace() throws Exception {
        Metaspace metaspace = Metaspace.singleton();
        byte[] largeBytes = metaspace.allocateByteArray(CremaTestSupport.largeByteArrayLength());
        int[] ints = metaspace.allocateIntArray(1000);
        CremaMetaspaceTest.fill(largeBytes, 3);
        CremaMetaspaceTest.fill(ints, 4);
        AtomicReference<Object[]>[] boxes = CremaMetaspaceTest.allocateBoxes(metaspace, 8);
        for (int i = 0; i < boxes.length; i++) {
            int[] numbers = new int[i + 1];
            CremaMetaspaceTest.fill(numbers, i);
            boxes[i].set(new Object[]{numbers, "0/" + i, boxes[(i + 1) % boxes.length]});
        }

        List<byte[]> chunks = new ArrayList<>();
        boolean exhausted = false;
        try {
            while (true) {
                chunks.add(metaspace.allocateByteArray(256 * 1024));
            }
        } catch (OutOfMemoryError e) {
            exhausted = true;
            System.out.println("metaspace exhausted after " + chunks.size() + " chunks: " + e.getMessage());
        }
        Assert.assertTrue("metaspace was not exhausted", exhausted);

        Path payloads = CremaTestSupport.writePayloads();
        try {
            Class<?> c = new DirLoader(payloads).loadClass(CremaPayloads.HELLO);
            /* Small objects may still fit into the remaining space. */
            Assert.assertEquals(3, ((IntUnaryOperator) c.getDeclaredConstructor().newInstance()).applyAsInt(1));
            System.out.println("class definition after exhaustion succeeded");
        } catch (OutOfMemoryError e) {
            System.out.println("class definition after exhaustion failed as expected: " + e);
        } finally {
            CremaTestSupport.deleteRecursively(payloads);
        }

        System.gc();
        CremaMetaspaceTest.check(largeBytes, 3, "large byte[] after exhaustion");
        CremaMetaspaceTest.check(ints, 4, "int[] after exhaustion");
        for (int i = 0; i < boxes.length; i++) {
            CremaMetaspaceTest.checkBox(boxes, i, 0);
        }
    }
}
