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

import java.util.function.IntUnaryOperator;

/**
 * Defined at run time by the Crema metaspace tests in
 * {@code com.oracle.svm.test.crema.CremaMetaspaceTest}.
 * <p>
 * This file is not compiled directly into the test image. It is included indirectly as the byte
 * array {@code CremaPayloads.HELLO_CLASS} in
 * {@code src/com.oracle.svm.test/src/com/oracle/svm/test/crema/CremaPayloads.java}, which the
 * tests write to a temporary class file at run time. After changing this file, regenerate that
 * byte array with {@code regenerate.sh} in this directory.
 * <p>
 * Only uses language features and image methods that are compiled into every image (no string
 * concatenation, no JDK calls), since interpreted code can only call AOT methods that the image's
 * analysis saw as reachable.
 */
public class Hello implements IntUnaryOperator {
    @Override
    public int applyAsInt(int x) {
        return 2 * x + 1;
    }
}
