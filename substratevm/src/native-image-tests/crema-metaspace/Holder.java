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
package cremametaspace;

import java.util.function.IntSupplier;

/**
 * Defined at run time by the Crema metaspace tests in
 * {@code com.oracle.svm.test.crema.CremaMetaspaceTest}. Its static fields keep heap objects alive
 * that are only reachable through the class (i.e. through its DynamicHub in the metaspace), so the
 * GC must treat the metaspace as a root and update these references when it moves the objects.
 * <p>
 * This file is not compiled directly into the test image. It is included indirectly as the byte
 * array {@code CremaPayloads.HOLDER_CLASS} in
 * {@code src/com.oracle.svm.test/src/com/oracle/svm/test/crema/CremaPayloads.java}, which the
 * tests write to a temporary class file at run time. After changing this file, regenerate that
 * byte array with {@code regenerate.sh} in this directory.
 * <p>
 * Only uses language features and image methods that are compiled into every image (no string
 * concatenation, no JDK calls), since interpreted code can only call AOT methods that the image's
 * analysis saw as reachable.
 */
public class Holder implements IntSupplier {
    static int[] squares;
    static Object[] objects;
    static Holder self;
    static long checksum;

    int value;
    int[] data;
    Holder next;

    static {
        squares = new int[256];
        for (int i = 0; i < squares.length; i++) {
            squares[i] = i * i;
        }
        objects = new Object[64];
        for (int i = 0; i < objects.length; i++) {
            Holder h = new Holder();
            h.value = i;
            h.data = new int[i + 1];
            for (int j = 0; j <= i; j++) {
                h.data[j] = i + j;
            }
            objects[i] = h;
        }
        self = new Holder();
        self.value = 7;
        self.next = (Holder) objects[63];
        checksum = compute();
    }

    static long compute() {
        long sum = 0;
        for (int i = 0; i < squares.length; i++) {
            sum += squares[i];
        }
        for (int i = 0; i < objects.length; i++) {
            Holder h = (Holder) objects[i];
            sum += h.value * 1000L;
            for (int j = 0; j < h.data.length; j++) {
                sum += h.data[j];
            }
        }
        return sum + self.value + self.next.value;
    }

    /** Returns 0 if all objects reachable from the static fields are still intact. */
    @Override
    public int getAsInt() {
        return compute() == checksum ? 0 : 1;
    }

    /** Allocates fresh heap objects and stores them in static fields of a runtime-loaded class. */
    public static void replace(int round) {
        int i = round & 63;
        Holder h = new Holder();
        h.value = i;
        h.data = new int[i + 1];
        for (int j = 0; j <= i; j++) {
            h.data[j] = i + j;
        }
        objects[i] = h;
        if (i == 63) {
            self.next = h;
        }
    }
}
