/*
 * Copyright (c) 2011, 2024, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.hotspot.meta;

import jdk.graal.compiler.hotspot.GraalHotSpotVMConfig;
import jdk.vm.ci.code.Register;

public class HotSpotRegisters implements HotSpotRegistersProvider {

    private final Register threadRegister;
    private final Register heapBaseRegister;
    private final Register stackPointerRegister;

    public HotSpotRegisters(Register threadRegister, Register heapBaseRegister, Register stackPointerRegister) {
        this.threadRegister = threadRegister;
        this.heapBaseRegister = heapBaseRegister;
        this.stackPointerRegister = stackPointerRegister;
    }

    @Override
    public Register getThreadRegister() {
        assert !threadRegister.equals(Register.None) : "thread register is not defined";
        return threadRegister;
    }

    @Override
    public Register getHeapBaseRegister() {
        assert !heapBaseRegister.equals(Register.None) : "heap base register is not defined";
        return heapBaseRegister;
    }

    @Override
    public Register getStackPointerRegister() {
        assert !stackPointerRegister.equals(Register.None) : "stack pointer register is not defined";
        return stackPointerRegister;
    }

    @Override
    public Register getZeroValueRegister(GraalHotSpotVMConfig config) {
        if (config.useCompressedOops && !config.getOopEncoding().hasBase()) {
            /*
             * Heap base register is exempted from register allocation when using compressed oops.
             * Its value will be config.getOopEncoding().getBase().
             */
            return getHeapBaseRegister();
        }
        return Register.None;
    }

    @Override
    public boolean isReservedRegister(Register r) {
        return !r.equals(Register.None) && (r.equals(threadRegister) || r.equals(heapBaseRegister) || r.equals(stackPointerRegister));
    }
}
