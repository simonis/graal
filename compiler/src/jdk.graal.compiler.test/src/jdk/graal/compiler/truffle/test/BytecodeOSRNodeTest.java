/*
 * Copyright (c) 2021, 2024, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.truffle.test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.graalvm.polyglot.Context;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameDescriptor.Builder;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.impl.FrameWithoutBoxing;
import com.oracle.truffle.api.nodes.BytecodeOSRNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.test.SubprocessTestUtils;
import com.oracle.truffle.api.test.SubprocessTestUtils.WithSubprocess;
import com.oracle.truffle.runtime.BytecodeOSRMetadata;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;

import jdk.graal.compiler.test.GraalTest;

public class BytecodeOSRNodeTest extends TestWithSynchronousCompiling {

    private static final OptimizedTruffleRuntime runtime = (OptimizedTruffleRuntime) Truffle.getRuntime();

    @Rule public TestRule timeout = SubprocessTestUtils.disableForParentProcess(GraalTest.createTimeout(60, TimeUnit.SECONDS));

    // Use a multiple of the poll interval, so OSR triggers immediately when it hits the threshold.
    private static final int OSR_THRESHOLD = 10 * BytecodeOSRMetadata.OSR_POLL_INTERVAL;

    @Before
    @Override
    public void before() {
        setupContext("engine.MultiTier", "false",
                        "engine.OSR", "true",
                        "engine.OSRCompilationThreshold", String.valueOf(OSR_THRESHOLD),
                        "engine.OSRMaxCompilationReAttempts", String.valueOf(1),
                        "engine.ThrowOnMaxOSRCompilationReAttemptsReached", "true");
    }

    /*
     * These state checks are surrounded by boundary calls to make sure there are frame states
     * immediately before and after the check.
     *
     * - This makes sure any return value computed before will not be optimized away, and would be
     * restored on deopt.
     *
     * - This also ensures that if a deopt happens, it will not roll-back upwards of the check,
     * yielding false positives/negatives.
     */

    @TruffleBoundary(allowInlining = false)
    private static void boundaryCall() {
    }

    private static void checkInInterpreter() {
        boundaryCall();
        Assert.assertTrue(CompilerDirectives.inInterpreter());
        boundaryCall();
    }

    private static void checkInCompiledCode() {
        boundaryCall();
        Assert.assertTrue(CompilerDirectives.inCompiledCode());
        boundaryCall();
    }

    /*
     * Test that an infinite interpreter loop triggers OSR.
     */
    @Test
    public void testSimpleInterpreterLoop() {
        RootNode rootNode = new Program(new InfiniteInterpreterLoop(), new FrameDescriptor());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        // Interpreter invocation should be OSR compiled and break out of the interpreter loop.
        Assert.assertEquals(42, target.call());
    }

    /*
     * Test that a loop which just exceeds the threshold triggers OSR.
     */
    @Test
    public void testFixedIterationLoop() {
        var frameBuilder = FrameDescriptor.newBuilder();
        FixedIterationLoop osrNode = new FixedIterationLoop(frameBuilder);
        RootNode rootNode = new Program(osrNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(FixedIterationLoop.OSR_RESULT, target.call(OSR_THRESHOLD + 1));
    }

    /*
     * Test that a loop just below the OSR threshold does not trigger OSR.
     */
    @Test
    public void testFixedIterationLoopBelowThreshold() {
        var frameBuilder = FrameDescriptor.newBuilder();
        FixedIterationLoop osrNode = new FixedIterationLoop(frameBuilder);
        RootNode rootNode = new Program(osrNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(FixedIterationLoop.NORMAL_RESULT, target.call(OSR_THRESHOLD));
    }

    /*
     * Test that OSR is triggered in the expected location when multiple loops are involved.
     */
    @Test
    public void testMultipleLoops() {
        // Each loop runs for osrThreshold + 1 iterations, so the first loop should trigger OSR.
        var frameBuilder = FrameDescriptor.newBuilder();
        TwoFixedIterationLoops osrNode = new TwoFixedIterationLoops(frameBuilder);
        RootNode rootNode = new Program(osrNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(TwoFixedIterationLoops.OSR_IN_FIRST_LOOP, target.call(OSR_THRESHOLD + 1));

        // Each loop runs for osrThreshold/2 + 1 iterations, so the second loop should trigger OSR.
        frameBuilder = FrameDescriptor.newBuilder();
        osrNode = new TwoFixedIterationLoops(frameBuilder);
        rootNode = new Program(osrNode, frameBuilder.build());
        target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(TwoFixedIterationLoops.OSR_IN_SECOND_LOOP, target.call(OSR_THRESHOLD / 2 + 1));

        // Each loop runs for osrThreshold/2 iterations, so OSR should not get triggered.
        frameBuilder = FrameDescriptor.newBuilder();
        osrNode = new TwoFixedIterationLoops(frameBuilder);
        rootNode = new Program(osrNode, frameBuilder.build());
        target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(TwoFixedIterationLoops.NO_OSR, target.call(OSR_THRESHOLD / 2));
    }

    /*
     * Test that OSR is triggered in the expected location when multiple loops are involved, and
     * makes sure that their cached frame states for transfers are independent.
     */
    @Test
    public void testMultipleLoopsIncompatibleState() {
        // Each loop runs for osrThreshold + 1 iterations, so the first loop should trigger OSR.
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        TwoLoopsIncompatibleFrames osrNode = new TwoLoopsIncompatibleFrames(builder);
        RootNode rootNode = new Program(osrNode, builder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(TwoLoopsIncompatibleFrames.OSR_IN_FIRST_LOOP, target.call(OSR_THRESHOLD + 1, true));
        Assert.assertEquals(TwoLoopsIncompatibleFrames.OSR_IN_SECOND_LOOP, target.call(OSR_THRESHOLD + 1, false));
    }

    /*
     * Test that OSR fails if the code cannot be compiled.
     */
    @Test
    @WithSubprocess
    public void testFailedCompilation() throws IOException, InterruptedException {
        // Run in a subprocess to prevent graph graal dumps that are enabled by the default mx
        // unittest options.
        SubprocessTestUtils.newBuilder(BytecodeOSRNodeTest.class, () -> {
            Context.Builder builder = newContextBuilder().logHandler(new ByteArrayOutputStream());
            builder.option("engine.MultiTier", "false");
            builder.option("engine.OSR", "true");
            builder.option("engine.OSRCompilationThreshold", String.valueOf(OSR_THRESHOLD));
            setupContext(builder);
            var frameBuilder = FrameDescriptor.newBuilder();
            UncompilableFixedIterationLoop osrNode = new UncompilableFixedIterationLoop(frameBuilder);
            RootNode rootNode = new Program(osrNode, frameBuilder.build());
            OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
            Assert.assertEquals(FixedIterationLoop.NORMAL_RESULT, target.call(OSR_THRESHOLD + 1));
            // Compilation should be disabled after a compilation failure.
            Assert.assertTrue(osrNode.getGraalOSRMetadata().isDisabled());
        }).run();
    }

    /*
     * Test that a deoptimized OSR target can recompile.
     */
    @Test
    public void testDeoptimizeAndRecompile() {
        var frameBuilder = FrameDescriptor.newBuilder();
        DeoptimizingFixedIterationLoop osrNode = new DeoptimizingFixedIterationLoop(frameBuilder);
        RootNode rootNode = new Program(osrNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        // After osrThreshold+1 iterations, it should trigger OSR and deoptimize. OSR should not be
        // disabled, but the target should be invalid pending recompilation.
        Assert.assertEquals(FixedIterationLoop.NORMAL_RESULT, target.call(OSR_THRESHOLD + 1));
        Assert.assertNotEquals(osrNode.getGraalOSRMetadata(), BytecodeOSRMetadata.DISABLED);
        BytecodeOSRMetadata osrMetadata = osrNode.getGraalOSRMetadata();
        OptimizedCallTarget osrTarget = osrMetadata.getOSRCompilations().get(BytecodeOSRTestNode.DEFAULT_TARGET_AS_LONG);
        Assert.assertFalse(target.isValid());
        // If we call it again, it should recompile, and the same call target should be used.
        Assert.assertEquals(FixedIterationLoop.OSR_RESULT, target.call(OSR_THRESHOLD + 1));
        Assert.assertNotEquals(osrNode.getGraalOSRMetadata(), BytecodeOSRMetadata.DISABLED);
        OptimizedCallTarget newOSRTarget = osrMetadata.getOSRCompilations().get(BytecodeOSRTestNode.DEFAULT_TARGET_AS_LONG);
        Assert.assertTrue(osrTarget.isValid());
        Assert.assertEquals(newOSRTarget, osrTarget);
    }

    /*
     * Test that node replacement in the base node invalidates both OSR and root call targets.
     */
    @Test
    public void testInvalidateOnNodeReplaced() {
        var frameBuilder = FrameDescriptor.newBuilder();
        Node childToReplace = new Node() {
        };
        FixedIterationLoop osrNode = new FixedIterationLoopWithChild(frameBuilder, childToReplace);
        RootNode rootNode = new Program(osrNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(FixedIterationLoop.OSR_RESULT, target.call(OSR_THRESHOLD + 1));
        BytecodeOSRMetadata osrMetadata = osrNode.getGraalOSRMetadata();
        OptimizedCallTarget osrTarget = osrMetadata.getOSRCompilations().get(BytecodeOSRTestNode.DEFAULT_TARGET_AS_LONG);
        Assert.assertNotNull(osrTarget);
        Assert.assertTrue(osrTarget.isValid());

        // Invoke the root node itself until it's compiled.
        for (int i = 1; i < SINGLE_TIER_THRESHOLD; i++) {
            target.call(5);
        }
        Assert.assertTrue(target.isValid());

        childToReplace.replace(new Node() {
        });
        Assert.assertFalse(osrTarget.isValid());
        Assert.assertFalse(target.isValid());

        // Invalidating a target on node replace should not disable compilation or remove the target
        Assert.assertNotEquals(osrNode.getGraalOSRMetadata(), BytecodeOSRMetadata.DISABLED);
        Assert.assertFalse(osrMetadata.getOSRCompilations().isEmpty());
        // Calling the node will eventually trigger OSR again.
        target.resetCompilationProfile(); // Clear call count so the root node is not compiled.
        Assert.assertEquals(FixedIterationLoop.OSR_RESULT, target.call(OSR_THRESHOLD + 1));
        Assert.assertNotEquals(osrNode.getGraalOSRMetadata(), BytecodeOSRMetadata.DISABLED);
        OptimizedCallTarget newOSRTarget = osrMetadata.getOSRCompilations().get(BytecodeOSRTestNode.DEFAULT_TARGET_AS_LONG);
        Assert.assertTrue(newOSRTarget.isValid());
        Assert.assertEquals(osrTarget, newOSRTarget);
    }

    /*
     * Test that node replacement in the base node invalidates both OSR and root call targets when
     * the bytecode OSR node is also the root node. (GR-53074 regression test)
     */
    @Test
    public void testInvalidateOnNodeReplacedOSRNodeIsRoot() {
        // Simplified version of the above test where the BytecodeOSRNode is also the RootNode.
        Node childToReplace = new Node() {
        };
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        FixedIterationLoopRootNode osrRootNode = new FixedIterationLoopRootNode(builder, childToReplace, builder.addSlot(FrameSlotKind.Int, "i", null), builder.addSlot(FrameSlotKind.Int, "n", null));
        OptimizedCallTarget target = (OptimizedCallTarget) osrRootNode.getCallTarget();
        Assert.assertEquals(FixedIterationLoop.OSR_RESULT, target.call(OSR_THRESHOLD + 1));
        BytecodeOSRMetadata osrMetadata = osrRootNode.getGraalOSRMetadata();
        OptimizedCallTarget osrTarget = osrMetadata.getOSRCompilations().get(BytecodeOSRTestNode.DEFAULT_TARGET_AS_LONG);
        Assert.assertNotNull(osrTarget);
        Assert.assertTrue(osrTarget.isValid());

        // Invoke the root node itself until it's compiled.
        for (int i = 1; i < SINGLE_TIER_THRESHOLD; i++) {
            target.call(5);
        }
        Assert.assertTrue(target.isValid());

        childToReplace.replace(new Node() {
        });
        Assert.assertFalse(osrTarget.isValid());
        Assert.assertFalse(target.isValid());

        // Invalidating a target on node replace should not disable compilation or remove the target
        Assert.assertNotEquals(osrRootNode.getGraalOSRMetadata(), BytecodeOSRMetadata.DISABLED);
        Assert.assertFalse(osrMetadata.getOSRCompilations().isEmpty());
        // Calling the node will eventually trigger OSR again.
        target.resetCompilationProfile(); // Clear call count so the root node is not compiled.
        Assert.assertEquals(FixedIterationLoop.OSR_RESULT, target.call(OSR_THRESHOLD + 1));
        Assert.assertNotEquals(osrRootNode.getGraalOSRMetadata(), BytecodeOSRMetadata.DISABLED);
        OptimizedCallTarget newOSRTarget = osrMetadata.getOSRCompilations().get(BytecodeOSRTestNode.DEFAULT_TARGET_AS_LONG);
        Assert.assertTrue(newOSRTarget.isValid());
        Assert.assertEquals(osrTarget, newOSRTarget);
    }

    /*
     * Test that OSR succeeds even if a Frame with the given FrameDescriptor has been materialized
     * before.
     */
    @Test
    public void testOSRWithMaterializeableFrame() {
        var frameBuilder = FrameDescriptor.newBuilder();
        MaterializedFixedIterationLoop osrNode = new MaterializedFixedIterationLoop(frameBuilder);
        RootNode rootNode = new Program(osrNode, frameBuilder.build());
        runtime.markFrameMaterializeCalled(rootNode.getFrameDescriptor());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        // OSR should succeed.
        Assert.assertEquals(FixedIterationLoop.OSR_RESULT, target.call(OSR_THRESHOLD + 1));
        // Since the frame could be materialized, we should reuse the parent frame instead of
        // copying.
        Assert.assertFalse(osrNode.frameWasCopied);
    }

    /*
     * Test that OSR compilation gets polled when compilation is asynchronous.
     */
    @Test
    public void testOSRPolling() {
        setupContext(
                        "engine.MultiTier", "false",
                        "engine.OSR", "true",
                        "engine.OSRCompilationThreshold", String.valueOf(OSR_THRESHOLD),
                        "engine.BackgroundCompilation", Boolean.TRUE.toString() // override defaults
        );
        InfiniteInterpreterLoop osrNode = new InfiniteInterpreterLoop();
        RootNode rootNode = new Program(osrNode, new FrameDescriptor());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(42, target.call());
    }

    /*
     * Test that a state object can be constructed and passed to OSR code.
     */
    @Test
    public void testInterpreterStateObject() {
        RootNode rootNode = new Program(new InterpreterStateInfiniteLoop(), new FrameDescriptor());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(42, target.call());
    }

    /*
     * Test that a callback can be passed and invoked before the OSR transfer.
     */
    @Test
    public void testBeforeTransferCallback() {
        RootNode rootNode = new Program(new BeforeTransferInfiniteLoop(), new FrameDescriptor());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(42, target.call());
    }

    /*
     * Test that the OSR call target does not get included in the Truffle stack trace.
     */
    @Test
    public void testStackTraceHidesOSRCallTarget() {
        var frameBuilder = FrameDescriptor.newBuilder();
        CheckStackWalkCallTarget osrNode = new CheckStackWalkCallTarget(frameBuilder);
        RootNode rootNode = new Program(osrNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(FixedIterationLoop.OSR_RESULT, target.call(2 * OSR_THRESHOLD));
    }

    /*
     * Test that the OSR frame is used in the Truffle stack trace.
     */
    @Test
    public void testStackTraceUsesOSRFrame() {
        var frameBuilder = FrameDescriptor.newBuilder();
        CheckStackWalkFrame osrNode = new CheckStackWalkFrame(frameBuilder);
        RootNode rootNode = new Program(osrNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        osrNode.callTarget = target; // set the call target so stack walking can use it
        Assert.assertEquals(FixedIterationLoop.OSR_RESULT, target.call(2 * OSR_THRESHOLD));
    }

    /*
     * Test that the topmost OSR frame is used in the Truffle stack trace when there are multiple
     * levels of OSR.
     */
    @Test
    public void testStackTraceUsesNewestOSRFrame() {
        var frameBuilder = FrameDescriptor.newBuilder();
        CheckStackWalkFrameNested osrNode = new CheckStackWalkFrameNested(frameBuilder);
        RootNode rootNode = new Program(osrNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        osrNode.callTarget = target; // set the call target so stack walking can use it
        Assert.assertEquals(FixedIterationLoop.OSR_RESULT, target.call(3 * OSR_THRESHOLD));
        Assert.assertTrue(osrNode.hasDeoptimizedYet);
    }

    /*
     * Test that getCallerFrame returns the correct frame when OSR is involved.
     *
     * Specifically, if X calls Y, and Y is OSRed, it should correctly skip over both the OSR and
     * original Y frames, returning X's frame.
     */
    @Test
    public void testGetCallerFrameSkipsOSR() {
        var frameBuilder = FrameDescriptor.newBuilder();
        CheckGetCallerFrameSkipsOSR osrNode = new CheckGetCallerFrameSkipsOSR(frameBuilder);
        RootNode rootNode = new Program(osrNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        RootNode caller = new CheckGetCallerFrameSkipsOSR.Caller(target);
        OptimizedCallTarget callerTarget = (OptimizedCallTarget) caller.getCallTarget();
        osrNode.caller = callerTarget;
        Assert.assertEquals(FixedIterationLoop.OSR_RESULT, callerTarget.call(OSR_THRESHOLD + 1));
    }

    /*
     * Test storeParentFrameInArguments and restoreParentFrame can be used to preserve selected
     * frame arguments after OSR.
     */
    @Test
    public void testStoreArguments() {
        RootNode rootNode = new Program(new PreserveFirstFrameArgumentNode(), new FrameDescriptor());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(42, target.call(PreserveFirstFrameArgumentNode.EXPECTED_FIRST_ARG, 0));
    }

    /*
     * Test that the frame transfer helper works as expected, both on OSR enter and exit.
     */
    @Test
    public void testFrameTransfer() {
        var frameBuilder = FrameDescriptor.newBuilder();
        RootNode rootNode = new Program(new FrameTransferringNode(frameBuilder), frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(42, target.call());
    }

    /*
     * Test that the frame transfer helper works as expected, even with static accesses, both on OSR
     * enter and exit.
     */
    @Test
    public void testFrameTransferWithStaticAccesses() {
        frameTransferWithStaticAccesses();
    }

    /*
     * Test that the frame transfer helper works as expected, even with static accesses, both on OSR
     * enter and exit, and even when assertions are disabled.
     */
    @Test
    @WithSubprocess
    public void testFrameTransferWithStaticAccessesWithAssertionsDisabled() throws Throwable {
        // Execute in a subprocess to disable assertion checking for frames.
        SubprocessTestUtils.newBuilder(BytecodeOSRNodeTest.class,
                        () -> {
                            Assert.assertFalse(FrameAssertionsChecker.areFrameAssertionsEnabled());
                            frameTransferWithStaticAccesses();
                        }).disableAssertions(FrameWithoutBoxing.class).run();
    }

    public static void frameTransferWithStaticAccesses() {
        var frameBuilder = FrameDescriptor.newBuilder();
        RootNode rootNode = new Program(new FrameTransferringWithStaticAccessNode(frameBuilder), frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(42, target.call());
    }

    /*
     * Test that the frame transfer helper works as expected, even with static accesses, both on OSR
     * enter and exit, and that merging of static slots works fine.
     */
    @Test
    public void testFrameTransferWithMergingStaticAccesses() {
        var frameBuilder = FrameDescriptor.newBuilder();
        RootNode rootNode = new Program(new FrameTransferringNodeWithMergingStaticAccess(frameBuilder), frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(42, target.call());
    }

    /*
     * Test that the frame transfer helper works even if a tag changes inside the OSR code. When
     * restoring the frame, we should detect the tag difference and deoptimize.
     */
    @Test
    public void testFrameTransferWithTagUpdate() {
        var frameBuilder = FrameDescriptor.newBuilder();
        RootNode rootNode = new Program(new FrameTransferringNodeWithTagUpdate(frameBuilder), frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(42, target.call());
    }

    /*
     * Test that frame transferring works (without deoptimizing) for a frame with uninitialized
     * slots. An uninitialized slot's tag may differ from the declared tag (since slots are given a
     * default value), but the tag speculation should not deoptimize because of this difference.
     *
     * (Concretely, this means we should speculate on a Frame's concrete tags instead of its
     * declared tags)
     */
    @Test
    public void testFrameTransferWithUninitializedSlots() {
        // use a non-null default value to make sure it gets copied properly.
        var frameBuilder = FrameDescriptor.newBuilder();
        Object defaultValue = new Object();
        frameBuilder.defaultValue(defaultValue);
        RootNode rootNode = new Program(new FrameTransferringNodeWithUninitializedSlots(frameBuilder, defaultValue), frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(42, target.call());
    }

    /*
     * Same as above, but with static slots.
     *
     * Note that uninitialized static slots differs from regular slots in that it may be read as any
     * kind, and always return the default ('defaultValue' for Object, '0' for primitives).
     */
    @Test
    public void testFrameTransferWithUninitializedStaticSlots() {
        frameTransferWithUninitializedStaticSlots();
    }

    /*
     * Same as above, but with assertion disabled.
     */
    @Test
    @WithSubprocess
    public void testFrameTransferWithUninitializedStaticSlotsWithoutAssertions() throws Throwable {
        // Execute in a subprocess to disable assertion checking for frames.
        SubprocessTestUtils.newBuilder(BytecodeOSRNodeTest.class,
                        () -> {
                            Assert.assertFalse(FrameAssertionsChecker.areFrameAssertionsEnabled());
                            frameTransferWithUninitializedStaticSlots();
                        }).disableAssertions(FrameWithoutBoxing.class).run();
    }

    public static void frameTransferWithUninitializedStaticSlots() {
        // use a non-null default value to make sure it gets copied properly.
        var frameBuilder = FrameDescriptor.newBuilder();
        Object defaultValue = new Object();
        frameBuilder.defaultValue(defaultValue);
        RootNode rootNode = new Program(new FrameTransferringNodeWithUninitializedStaticSlots(frameBuilder, defaultValue), frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(42, target.call());
    }

    /*
     * Test that we can safely recover from bailing out of OSR compilation while an OSR frame is
     * currently executing.
     */
    @Test
    public void testCanRecoverFromDisablingInOSRFrame() {
        // use a non-null default value to make sure it gets copied properly.
        var frameBuilder = FrameDescriptor.newBuilder();
        Object defaultValue = new Object();
        frameBuilder.defaultValue(defaultValue);
        RootNode rootNode = new Program(new OSRDisablingTransferringNode(frameBuilder), frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(42, target.call());
    }

    /*
     * Test that there is no infinitely recursive OSR calls.
     */
    @Test
    public void testRecursiveDeoptHandling() {
        FrameDescriptor frameDescriptor = new FrameDescriptor();
        RecursiveBytecodeOSRTestNode osrNode = new RecursiveBytecodeOSRTestNode();
        RootNode rootNode = new Program(osrNode, frameDescriptor);
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(RecursiveBytecodeOSRTestNode.RETURN_VALUE, target.call());
    }

    // Bytecode programs
    /*
     * do { input1 -= 1; result += 3; } while (input1); return result;
     */
    byte[] tripleInput1 = new byte[]{
                    /* 0: */BytecodeNode.Bytecode.DEC, 0,
                    /* 2: */BytecodeNode.Bytecode.INC, 2,
                    /* 4: */BytecodeNode.Bytecode.INC, 2,
                    /* 6: */BytecodeNode.Bytecode.INC, 2,
                    /* 8: */BytecodeNode.Bytecode.JMPNONZERO, 0, -8,
                    /* 11: */BytecodeNode.Bytecode.RETURN, 2
    };

    /*
     * do { input1--; temp = input2; do { temp--; result++; } while(temp); } while(input1); return
     * result;
     */
    byte[] multiplyInputs = new byte[]{
                    /* 0: */BytecodeNode.Bytecode.DEC, 0,
                    /* 2: */BytecodeNode.Bytecode.COPY, 1, 2,
                    /* 5: */BytecodeNode.Bytecode.DEC, 2,
                    /* 7: */BytecodeNode.Bytecode.INC, 3,
                    /* 9: */BytecodeNode.Bytecode.JMPNONZERO, 2, -4,
                    /* 12: */BytecodeNode.Bytecode.JMPNONZERO, 0, -12,
                    /* 15: */BytecodeNode.Bytecode.RETURN, 3
    };

    /*
     * Tests to validate the OSR mechanism with bytecode interpreters.
     */
    @Test
    public void testOSRInBytecodeLoop() {
        // osrThreshold + 1 back-edges -> compiled
        var frameBuilder = FrameDescriptor.newBuilder();
        BytecodeNode bytecodeNode = new BytecodeNode(3, frameBuilder, tripleInput1);
        RootNode rootNode = new Program(bytecodeNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        // note: requires an extra iteration due to an awkward interaction with enterprise loop
        // peeling.
        Assert.assertEquals(3 * (OSR_THRESHOLD + 2), target.call(OSR_THRESHOLD + 2, 0));
        Assert.assertTrue(bytecodeNode.compiled);
        BytecodeOSRMetadata osrMetadata = (BytecodeOSRMetadata) bytecodeNode.getOSRMetadata();
        Assert.assertNotEquals(osrMetadata, BytecodeOSRMetadata.DISABLED);
        Assert.assertTrue(osrMetadata.getOSRCompilations().containsKey(0L));
        Assert.assertTrue(osrMetadata.getOSRCompilations().get(0L).isValid());
    }

    @Test
    public void testNoOSRInBytecodeLoop() {
        // osrThreshold back-edges -> not compiled
        var frameBuilder = FrameDescriptor.newBuilder();
        BytecodeNode bytecodeNode = new BytecodeNode(3, frameBuilder, tripleInput1);
        RootNode rootNode = new Program(bytecodeNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(3 * OSR_THRESHOLD, target.call(OSR_THRESHOLD, 0));
        Assert.assertFalse(bytecodeNode.compiled);
        BytecodeOSRMetadata osrMetadata = (BytecodeOSRMetadata) bytecodeNode.getOSRMetadata();
        Assert.assertNotEquals(osrMetadata, BytecodeOSRMetadata.DISABLED);
    }

    @Test
    public void testOSRInBytecodeOuterLoop() {
        // computes osrThreshold * 2
        // Inner loop contributes 1 back-edge, so each outer loop contributes 2 back-edges, and
        // the even-valued osrThreshold gets hit by the outer loop back-edge.
        var frameBuilder = FrameDescriptor.newBuilder();
        BytecodeNode bytecodeNode = new BytecodeNode(4, frameBuilder, multiplyInputs);
        RootNode rootNode = new Program(bytecodeNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(2 * OSR_THRESHOLD, target.call(OSR_THRESHOLD, 2));
        Assert.assertTrue(bytecodeNode.compiled);
        BytecodeOSRMetadata osrMetadata = (BytecodeOSRMetadata) bytecodeNode.getOSRMetadata();
        Assert.assertNotEquals(osrMetadata, BytecodeOSRMetadata.DISABLED);
        Assert.assertTrue(osrMetadata.getOSRCompilations().containsKey(0L));
        Assert.assertTrue(osrMetadata.getOSRCompilations().get(0L).isValid());
    }

    @Test
    public void testOSRInBytecodeInnerLoop() {
        // computes 2 * (osrThreshold - 1)
        // Inner loop contributes osrThreshold-2 back-edges, so the first outer loop contributes
        // osrThreshold-1 back-edges, then the next back-edge (which triggers OSR) is from the inner
        // loop.
        var frameBuilder = FrameDescriptor.newBuilder();
        BytecodeNode bytecodeNode = new BytecodeNode(4, frameBuilder, multiplyInputs);
        RootNode rootNode = new Program(bytecodeNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(2 * (OSR_THRESHOLD - 1), target.call(2, OSR_THRESHOLD - 1));
        Assert.assertTrue(bytecodeNode.compiled);
        BytecodeOSRMetadata osrMetadata = (BytecodeOSRMetadata) bytecodeNode.getOSRMetadata();
        Assert.assertNotEquals(osrMetadata, BytecodeOSRMetadata.DISABLED);
        Assert.assertTrue(osrMetadata.getOSRCompilations().containsKey(5L));
        Assert.assertTrue(osrMetadata.getOSRCompilations().get(5L).isValid());
    }

    @Test
    public void testFixedIterationLoopLongTarget() {
        var frameBuilder = FrameDescriptor.newBuilder();
        FixedIterationLoopLongTarget osrNode = new FixedIterationLoopLongTarget(frameBuilder);
        RootNode rootNode = new Program(osrNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(42, target.call(OSR_THRESHOLD + 1, 42));
    }

    @Test
    public void testFixedIterationLoopLongTargetUsesIntOverload() {
        var frameBuilder = FrameDescriptor.newBuilder();
        FixedIterationLoopLongTargetUsesIntOverload osrNode = new FixedIterationLoopLongTargetUsesIntOverload(frameBuilder);
        RootNode rootNode = new Program(osrNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        Assert.assertEquals(0, target.call(OSR_THRESHOLD + 1, 42));
    }

    @Test
    public void testLongTargetBadOverload1() {
        var frameBuilder = FrameDescriptor.newBuilder();
        FixedIterationLoopLongTargetLoopBadOverload1 osrNode = new FixedIterationLoopLongTargetLoopBadOverload1(frameBuilder);
        RootNode rootNode = new Program(osrNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        assertThrowsWithMessage("long target used without implementing long overload of prepareOSR", AssertionError.class, () -> target.call(OSR_THRESHOLD + 1));
    }

    @Test
    public void testLongTargetBadOverload2() {
        var frameBuilder = FrameDescriptor.newBuilder();
        FixedIterationLoopLongTargetLoopBadOverload2 osrNode = new FixedIterationLoopLongTargetLoopBadOverload2(frameBuilder);
        RootNode rootNode = new Program(osrNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        assertThrowsWithMessage("long target used without implementing long overload of copyIntoOSRFrame", AssertionError.class, () -> target.call(OSR_THRESHOLD + 1));
    }

    @Test
    public void testLongTargetBadOverload3() {
        var frameBuilder = FrameDescriptor.newBuilder();
        FixedIterationLoopLongTargetLoopBadOverload3 osrNode = new FixedIterationLoopLongTargetLoopBadOverload3(frameBuilder);
        RootNode rootNode = new Program(osrNode, frameBuilder.build());
        OptimizedCallTarget target = (OptimizedCallTarget) rootNode.getCallTarget();
        assertThrowsWithMessage("long target used without implementing long overload of executeOSR", AssertionError.class, () -> target.call(OSR_THRESHOLD + 1));
    }

    private static void assertThrowsWithMessage(String errorMessage, Class<?> expectedThrowable, Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            assertTrue(expectedThrowable.isInstance(t));
            assertTrue(t.getMessage().contains(errorMessage));
            return;
        }
        fail("No exception was thrown.");
    }

    public static class Program extends RootNode {
        @Child BytecodeOSRTestNode osrNode;

        public Program(BytecodeOSRTestNode osrNode, FrameDescriptor frameDescriptor) {
            super(null, frameDescriptor);
            this.osrNode = osrNode;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return osrNode.execute(frame);
        }
    }

    abstract static class BytecodeOSRTestNode extends Node implements BytecodeOSRNode {
        public static final int DEFAULT_TARGET = -1;
        public static final long DEFAULT_TARGET_AS_LONG = DEFAULT_TARGET;
        @CompilationFinal Object osrMetadata;

        @Override
        public Object getOSRMetadata() {
            return osrMetadata;
        }

        @Override
        public void setOSRMetadata(Object osrMetadata) {
            this.osrMetadata = osrMetadata;
        }

        BytecodeOSRMetadata getGraalOSRMetadata() {
            return (BytecodeOSRMetadata) getOSRMetadata();
        }

        protected int getInt(Frame frame, int frameSlot) {
            try {
                return frame.getInt(frameSlot);
            } catch (FrameSlotTypeException e) {
                throw new IllegalStateException("Error accessing frame slot " + frameSlot);
            }
        }

        protected void setInt(Frame frame, int frameSlot, int value) {
            frame.setInt(frameSlot, value);
        }

        // Prevent assertion code from being compiled.
        @TruffleBoundary
        public void assertEquals(Object expected, Object actual) {
            Assert.assertEquals(expected, actual);
        }

        @TruffleBoundary
        void assertDoubleEquals(double expected, double actual) {
            Assert.assertEquals(expected, actual, 0);
        }

        abstract Object execute(VirtualFrame frame);
    }

    public static class InfiniteInterpreterLoop extends BytecodeOSRTestNode {
        @Override
        public Object executeOSR(VirtualFrame osrFrame, int target, Object interpreterState) {
            if (CompilerDirectives.inCompiledCode()) {
                return 42;
            }
            return execute(osrFrame);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            // This node only terminates in compiled code.
            while (true) {
                if (CompilerDirectives.inCompiledCode()) {
                    // must never happen
                    return 41;
                }
                if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                    Object result = BytecodeOSRNode.tryOSR(this, DEFAULT_TARGET, null, null, frame);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }
    }

    public static class FixedIterationLoop extends BytecodeOSRTestNode {
        @CompilationFinal int indexSlot;
        @CompilationFinal int numIterationsSlot;

        static final String OSR_RESULT = "osr result";
        static final String NORMAL_RESULT = "normal result";

        public FixedIterationLoop(FrameDescriptor.Builder builder) {
            indexSlot = builder.addSlot(FrameSlotKind.Int, "i", null);
            numIterationsSlot = builder.addSlot(FrameSlotKind.Int, "n", null);
        }

        @Override
        public void copyIntoOSRFrame(VirtualFrame osrFrame, VirtualFrame parentFrame, int target, Object targetMetadata) {
            setInt(osrFrame, indexSlot, getInt(parentFrame, indexSlot));
            setInt(osrFrame, numIterationsSlot, getInt(parentFrame, numIterationsSlot));
        }

        @Override
        public Object executeOSR(VirtualFrame osrFrame, int target, Object interpreterState) {
            return executeLoop(osrFrame, getInt(osrFrame, numIterationsSlot));
        }

        @Override
        public Object execute(VirtualFrame frame) {
            frame.setInt(indexSlot, 0);
            int numIterations = (Integer) frame.getArguments()[0];
            frame.setInt(numIterationsSlot, numIterations);
            return executeLoop(frame, numIterations);
        }

        protected Object executeLoop(VirtualFrame frame, int numIterations) {
            int loopIterations = 0;
            try {
                for (int i = frame.getInt(indexSlot); i < numIterations; i++) {
                    loopIterations++;
                    frame.setInt(indexSlot, i);
                    if (i + 1 < numIterations) { // back-edge will be taken
                        if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                            Object result = BytecodeOSRNode.tryOSR(this, DEFAULT_TARGET, null, null, frame);
                            if (result != null) {
                                return result;
                            }
                        }
                    }
                }
                return CompilerDirectives.inCompiledCode() ? OSR_RESULT : NORMAL_RESULT;
            } catch (FrameSlotTypeException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            } finally {
                LoopNode.reportLoopCount(this, loopIterations);
            }
        }
    }

    public static class TwoFixedIterationLoops extends FixedIterationLoop {
        static final String NO_OSR = "no osr";
        static final String OSR_IN_FIRST_LOOP = "osr in first loop";
        static final String OSR_IN_SECOND_LOOP = "osr in second loop";

        public TwoFixedIterationLoops(FrameDescriptor.Builder frameBuilder) {
            super(frameBuilder);
        }

        @Override
        protected Object executeLoop(VirtualFrame frame, int numIterations) {
            try {
                for (int i = frame.getInt(indexSlot); i < numIterations; i++) {
                    frame.setInt(indexSlot, i);
                    if (i + 1 < numIterations) { // back-edge will be taken
                        if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                            Object result = BytecodeOSRNode.tryOSR(this, DEFAULT_TARGET, null, null, frame);
                            if (result != null) {
                                return OSR_IN_FIRST_LOOP;
                            }
                        }
                    }
                }
                for (int i = frame.getInt(indexSlot); i < 2 * numIterations; i++) {
                    frame.setInt(indexSlot, i);
                    if (i + 1 < 2 * numIterations) { // back-edge will be taken
                        if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                            Object result = BytecodeOSRNode.tryOSR(this, DEFAULT_TARGET, null, null, frame);
                            if (result != null) {
                                return OSR_IN_SECOND_LOOP;
                            }
                        }
                    }
                }
                return NO_OSR;
            } catch (FrameSlotTypeException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }
    }

    /**
     * Contains two loops whose entry's frame states differs. Makes sure that one's entry frame
     * state does not spill to the other
     */
    public static class TwoLoopsIncompatibleFrames extends BytecodeOSRTestNode {
        static final String NO_OSR = "no osr";
        static final String OSR_IN_FIRST_LOOP = "osr in first loop";
        static final String OSR_IN_SECOND_LOOP = "osr in second loop";

        static final int FIRST_LOOP_TARGET = 0;
        static final int SECOND_LOOP_TARGET = 1;

        @CompilationFinal int iterationsSlot;
        @CompilationFinal int indexSlot;
        @CompilationFinal int selectSlot;
        @CompilationFinal int localSlot;

        public TwoLoopsIncompatibleFrames(FrameDescriptor.Builder builder) {
            iterationsSlot = builder.addSlot(FrameSlotKind.Int, "iterations", null);
            indexSlot = builder.addSlot(FrameSlotKind.Int, "index", null);
            selectSlot = builder.addSlot(FrameSlotKind.Boolean, "select", null);
            localSlot = builder.addSlot(FrameSlotKind.Illegal, "local", null);
        }

        @Override
        Object execute(VirtualFrame frame) {
            int numIter = (int) frame.getArguments()[0];
            boolean select = (boolean) frame.getArguments()[1];
            frame.setInt(iterationsSlot, numIter);
            frame.setInt(indexSlot, 0);
            frame.setBoolean(selectSlot, select);
            if (select) {
                frame.setInt(localSlot, 0);
                return executeLoop(frame, numIter, select);
            } else {
                frame.setDouble(localSlot, 0d);
                return executeLoop(frame, numIter, select);
            }
        }

        @Override
        public Object executeOSR(VirtualFrame osrFrame, int target, Object interpreterState) {
            return executeLoop(osrFrame, osrFrame.getInt(iterationsSlot), target == FIRST_LOOP_TARGET);
        }

        protected Object executeLoop(VirtualFrame frame, int numIterations, boolean select) {
            try {
                if (select) {
                    for (int i = frame.getInt(indexSlot); i < numIterations; i++) {
                        frame.setInt(indexSlot, i);
                        int partial = frame.getInt(localSlot);
                        frame.setInt(localSlot, i + partial);
                        if (i + 1 < numIterations) { // back-edge will be taken
                            if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                                Object result = BytecodeOSRNode.tryOSR(this, FIRST_LOOP_TARGET, null, null, frame);
                                if (result != null) {
                                    return OSR_IN_FIRST_LOOP;
                                }
                            }
                        }
                    }
                } else {
                    for (int i = frame.getInt(indexSlot); i < numIterations; i++) {
                        frame.setInt(indexSlot, i);
                        double partial = frame.getDouble(localSlot);
                        frame.setDouble(localSlot, i + partial);
                        if (i + 1 < numIterations) { // back-edge will be taken
                            if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                                Object result = BytecodeOSRNode.tryOSR(this, SECOND_LOOP_TARGET, null, null, frame);
                                if (result != null) {
                                    return OSR_IN_SECOND_LOOP;
                                }
                            }
                        }
                    }
                }
                return NO_OSR;
            } catch (FrameSlotTypeException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }
    }

    public static class UncompilableFixedIterationLoop extends FixedIterationLoop {
        public UncompilableFixedIterationLoop(FrameDescriptor.Builder frameBuilder) {
            super(frameBuilder);
        }

        @Override
        protected Object executeLoop(VirtualFrame frame, int numIterations) {
            for (int i = 0; i < numIterations; i++) {
                CompilerAsserts.neverPartOfCompilation();
                if (i + 1 < numIterations) { // back-edge will be taken
                    if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                        Object result = BytecodeOSRNode.tryOSR(this, DEFAULT_TARGET, null, null, frame);
                        if (result != null) {
                            return result;
                        }
                    }
                }
            }
            return CompilerDirectives.inCompiledCode() ? OSR_RESULT : NORMAL_RESULT;
        }
    }

    public static class DeoptimizingFixedIterationLoop extends FixedIterationLoop {
        @CompilationFinal boolean loaded;

        public DeoptimizingFixedIterationLoop(FrameDescriptor.Builder frameBuilder) {
            super(frameBuilder);
            loaded = false;
        }

        @Override
        protected Object executeLoop(VirtualFrame frame, int numIterations) {
            checkField();
            try {
                for (int i = frame.getInt(indexSlot); i < numIterations; i++) {
                    frame.setInt(indexSlot, i);
                    if (i + 1 < numIterations) { // back-edge will be taken
                        if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                            Object result = BytecodeOSRNode.tryOSR(this, DEFAULT_TARGET, null, null, frame);
                            if (result != null) {
                                return result;
                            }
                        }
                    }
                }
                return CompilerDirectives.inCompiledCode() ? OSR_RESULT : NORMAL_RESULT;
            } catch (FrameSlotTypeException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }

        void checkField() {
            if (CompilerDirectives.inCompiledCode() && !loaded) {
                // the boundary call prevents Truffle from moving the deopt earlier,
                // which ensures this branch is taken.
                boundaryCall();
                CompilerDirectives.transferToInterpreterAndInvalidate();
                loaded = true;
            }
        }
    }

    public static class FixedIterationLoopWithChild extends FixedIterationLoop {
        @Child Node child;

        public FixedIterationLoopWithChild(FrameDescriptor.Builder frameBuilder, Node child) {
            super(frameBuilder);
            this.child = child;
        }
    }

    public static class MaterializedFixedIterationLoop extends FixedIterationLoop {
        boolean frameWasCopied = false;

        public MaterializedFixedIterationLoop(FrameDescriptor.Builder frameBuilder) {
            super(frameBuilder);
        }

        @Override
        public void restoreParentFrame(VirtualFrame osrFrame, VirtualFrame parentFrame) {
            super.restoreParentFrame(osrFrame, parentFrame);
            frameWasCopied = true;
        }
    }

    public static class InterpreterStateInfiniteLoop extends BytecodeOSRTestNode {

        static final class InterpreterState {
            final int foo;
            final int bar;

            InterpreterState(int foo, int bar) {
                this.foo = foo;
                this.bar = bar;
            }
        }

        @Override
        public Object executeOSR(VirtualFrame osrFrame, int target, Object interpreterState) {
            InterpreterState state = (InterpreterState) interpreterState;
            return executeLoop(osrFrame, state.foo, state.bar);
        }

        @Override
        Object execute(VirtualFrame frame) {
            return executeLoop(frame, 1, 20);
        }

        protected Object executeLoop(VirtualFrame frame, int foo, int bar) {
            CompilerAsserts.partialEvaluationConstant(foo);
            CompilerAsserts.partialEvaluationConstant(bar);
            // This node only terminates in compiled code.
            while (true) {
                if (CompilerDirectives.inCompiledCode()) {
                    return foo + bar;
                }
                if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                    Object result = BytecodeOSRNode.tryOSR(this, DEFAULT_TARGET, new InterpreterState(2 * foo, 2 * bar), null, frame);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }
    }

    public static class BeforeTransferInfiniteLoop extends BytecodeOSRTestNode {
        boolean callbackInvoked = false;

        @Override
        public Object executeOSR(VirtualFrame osrFrame, int target, Object interpreterState) {
            assertEquals(true, callbackInvoked);
            return execute(osrFrame);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            // This node only terminates in compiled code.
            while (true) {
                if (CompilerDirectives.inCompiledCode()) {
                    return 42;
                }
                if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                    Object result = BytecodeOSRNode.tryOSR(this, DEFAULT_TARGET, null, () -> {
                        callbackInvoked = true;
                    }, frame);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }
    }

    public abstract static class StackWalkingFixedIterationLoop extends FixedIterationLoop {
        public StackWalkingFixedIterationLoop(FrameDescriptor.Builder frameBuilder) {
            super(frameBuilder);
        }

        @Override
        protected Object executeLoop(VirtualFrame frame, int numIterations) {
            try {
                for (int i = frame.getInt(indexSlot); i < numIterations; i++) {
                    frame.setInt(indexSlot, i);
                    checkStackTrace(i);
                    if (i + 1 < numIterations) { // back-edge will be taken
                        if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                            Object result = BytecodeOSRNode.tryOSR(this, DEFAULT_TARGET, null, null, frame);
                            if (result != null) {
                                return result;
                            }
                        }
                    }
                }
                return CompilerDirectives.inCompiledCode() ? OSR_RESULT : NORMAL_RESULT;
            } catch (FrameSlotTypeException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }

        abstract void checkStackTrace(int index);
    }

    public static class CheckStackWalkCallTarget extends StackWalkingFixedIterationLoop {
        public CheckStackWalkCallTarget(FrameDescriptor.Builder frameBuilder) {
            super(frameBuilder);
        }

        @Override
        void checkStackTrace(int index) {
            Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<Void>() {
                private boolean first = true;

                @Override
                public Void visitFrame(FrameInstance frameInstance) {
                    BytecodeOSRMetadata metadata = getGraalOSRMetadata();
                    if (metadata != null) {
                        // We should never see the OSR call target in a stack trace.
                        Assert.assertTrue(metadata.getOSRCompilations() == null ||
                                        metadata.getOSRCompilations().get(DEFAULT_TARGET_AS_LONG) != frameInstance.getCallTarget());
                    }
                    if (first) {
                        first = false;
                    } else {
                        Assert.assertNotNull(frameInstance.getCallNode());
                    }
                    return null;
                }
            });
        }
    }

    public static class CheckStackWalkFrame extends StackWalkingFixedIterationLoop {
        public CallTarget callTarget; // call target containing this node (must be set after
                                      // construction due to circular dependence)

        public CheckStackWalkFrame(FrameDescriptor.Builder frameBuilder) {
            super(frameBuilder);
        }

        @Override
        void checkStackTrace(int index) {
            Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<Void>() {
                @Override
                public Void visitFrame(FrameInstance frameInstance) {
                    if (frameInstance.getCallTarget() == callTarget) {
                        try {
                            // The OSR frame will be up to date; the parent frame will not. We
                            // should get the OSR frame here.
                            int indexInFrame = frameInstance.getFrame(FrameInstance.FrameAccess.READ_ONLY).getInt(indexSlot);
                            assertEquals(index, indexInFrame);
                            if (CompilerDirectives.inCompiledCode()) {
                                Assert.assertTrue(frameInstance.isVirtualFrame());
                            }
                        } catch (FrameSlotTypeException e) {
                            throw new IllegalStateException("Error accessing index slot");
                        }
                    }
                    return null;
                }
            });
        }
    }

    public static class CheckStackWalkFrameNested extends CheckStackWalkFrame {
        // Trigger a deoptimization once so that there are multiple OSR nodes in the call stack.
        @CompilationFinal public boolean hasDeoptimizedYet;

        public CheckStackWalkFrameNested(FrameDescriptor.Builder frameBuilder) {
            super(frameBuilder);
            hasDeoptimizedYet = false;
        }

        @TruffleBoundary
        void boundaryCall() {
        }

        @Override
        void checkStackTrace(int index) {
            if (CompilerDirectives.inCompiledCode() && !hasDeoptimizedYet) {
                // the boundary call prevents Truffle from moving the deopt earlier,
                // which ensures this branch is taken.
                boundaryCall();
                CompilerDirectives.transferToInterpreterAndInvalidate();
                hasDeoptimizedYet = true;
            }
            super.checkStackTrace(index);
        }
    }

    public static class CheckGetCallerFrameSkipsOSR extends FixedIterationLoop {
        CallTarget caller; // set after construction

        public CheckGetCallerFrameSkipsOSR(FrameDescriptor.Builder frameDescriptor) {
            super(frameDescriptor);
        }

        @Override
        protected Object executeLoop(VirtualFrame frame, int numIterations) {
            try {
                for (int i = frame.getInt(indexSlot); i < numIterations; i++) {
                    frame.setInt(indexSlot, i);
                    checkCallerFrame();
                    if (i + 1 < numIterations) { // back-edge will be taken
                        if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                            Object result = BytecodeOSRNode.tryOSR(this, DEFAULT_TARGET, null, null, frame);
                            if (result != null) {
                                return result;
                            }
                        }
                    }
                }
                return CompilerDirectives.inCompiledCode() ? OSR_RESULT : NORMAL_RESULT;
            } catch (FrameSlotTypeException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }

        @TruffleBoundary
        void checkCallerFrame() {
            Assert.assertEquals(caller, Truffle.getRuntime().iterateFrames((f) -> f.getCallTarget(), 1));
        }

        public static class Caller extends RootNode {
            CallTarget toCall;

            protected Caller(CallTarget toCall) {
                super(null);
                this.toCall = toCall;
            }

            @Override
            public Object execute(VirtualFrame frame) {
                return toCall.call(frame.getArguments());
            }
        }
    }

    public static class PreserveFirstFrameArgumentNode extends BytecodeOSRTestNode {
        static final Object EXPECTED_FIRST_ARG = new Object();

        @Override
        public Object executeOSR(VirtualFrame osrFrame, int target, Object interpreterState) {
            return execute(osrFrame);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            // This node only terminates in compiled code.
            while (true) {
                assertEquals(EXPECTED_FIRST_ARG, frame.getArguments()[0]);
                if (CompilerDirectives.inCompiledCode()) {
                    return 42;
                }
                if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                    Object result = BytecodeOSRNode.tryOSR(this, DEFAULT_TARGET, null, null, frame);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }

        @Override
        public Object[] storeParentFrameInArguments(VirtualFrame parentFrame) {
            // This node is always called with 2 args, we preserve the first
            Object[] arguments = parentFrame.getArguments();
            arguments[1] = parentFrame;
            return arguments;
        }

        @Override
        public Frame restoreParentFrameFromArguments(Object[] arguments) {
            return (Frame) arguments[1];
        }
    }

    public static class FrameTransferringNode extends BytecodeOSRTestNode {
        @CompilationFinal int booleanSlot;
        @CompilationFinal int byteSlot;
        @CompilationFinal int doubleSlot;
        @CompilationFinal int floatSlot;
        @CompilationFinal int intSlot;
        @CompilationFinal int longSlot;
        @CompilationFinal int objectSlot;
        @CompilationFinal Object o1;
        @CompilationFinal Object o2;

        public FrameTransferringNode(FrameDescriptor.Builder builder) {
            booleanSlot = builder.addSlot(FrameSlotKind.Boolean, "booleanValue", null);
            byteSlot = builder.addSlot(FrameSlotKind.Byte, "byteValue", null);
            doubleSlot = builder.addSlot(FrameSlotKind.Double, "doubleValue", null);
            floatSlot = builder.addSlot(FrameSlotKind.Float, "floatValue", null);
            intSlot = builder.addSlot(FrameSlotKind.Int, "intValue", null);
            longSlot = builder.addSlot(FrameSlotKind.Long, "longValue", null);
            objectSlot = builder.addSlot(FrameSlotKind.Object, "objectValue", null);

            o1 = new Object();
            o2 = new Object();
        }

        @Override
        public Object executeOSR(VirtualFrame osrFrame, int target, Object interpreterState) {
            checkRegularState(osrFrame);
            setOSRState(osrFrame);
            checkInCompiledCode();
            return 42;
        }

        @Override
        public void copyIntoOSRFrame(VirtualFrame osrFrame, VirtualFrame parentFrame, int target, Object targetMetadata) {
            super.copyIntoOSRFrame(osrFrame, parentFrame, target, targetMetadata);
            // Copying should not trigger a deopt.
            checkInCompiledCode();
        }

        @Override
        public void restoreParentFrame(VirtualFrame osrFrame, VirtualFrame parentFrame) {
            super.restoreParentFrame(osrFrame, parentFrame);
            // Frame restoration is done in interpreter to get smaller graphs.
            checkInInterpreter();
        }

        @Override
        public Object execute(VirtualFrame frame) {
            checkInInterpreter();
            setRegularState(frame);
            return executeLoop(frame);
        }

        public Object executeLoop(VirtualFrame frame) {
            // This node only terminates in compiled code.
            while (true) {
                if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                    Object result = BytecodeOSRNode.tryOSR(this, DEFAULT_TARGET, null, null, frame);
                    if (result != null) {
                        checkOSRState(frame);
                        return result;
                    }
                }
            }
        }

        public void setRegularState(VirtualFrame frame) {
            frame.setBoolean(booleanSlot, true);
            frame.setByte(byteSlot, Byte.MIN_VALUE);
            frame.setDouble(doubleSlot, Double.MIN_VALUE);
            frame.setFloat(floatSlot, Float.MIN_VALUE);
            frame.setInt(intSlot, Integer.MIN_VALUE);
            frame.setLong(longSlot, Long.MIN_VALUE);
            frame.setObject(objectSlot, o1);
        }

        public void checkRegularState(VirtualFrame frame) {
            try {
                assertEquals(true, frame.getBoolean(booleanSlot));
                assertEquals(Byte.MIN_VALUE, frame.getByte(byteSlot));
                assertDoubleEquals(Double.MIN_VALUE, frame.getDouble(doubleSlot));
                assertDoubleEquals(Float.MIN_VALUE, frame.getFloat(floatSlot));
                assertEquals(Integer.MIN_VALUE, frame.getInt(intSlot));
                assertEquals(Long.MIN_VALUE, frame.getLong(longSlot));
                assertEquals(o1, frame.getObject(objectSlot));
            } catch (FrameSlotTypeException ex) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }

        public void setOSRState(VirtualFrame frame) {
            frame.setBoolean(booleanSlot, false);
            frame.setByte(byteSlot, Byte.MAX_VALUE);
            frame.setDouble(doubleSlot, Double.MAX_VALUE);
            frame.setFloat(floatSlot, Float.MAX_VALUE);
            frame.setInt(intSlot, Integer.MAX_VALUE);
            frame.setLong(longSlot, Long.MAX_VALUE);
            frame.setObject(objectSlot, o2);
        }

        public void checkOSRState(VirtualFrame frame) {
            try {
                assertEquals(false, frame.getBoolean(booleanSlot));
                assertEquals(Byte.MAX_VALUE, frame.getByte(byteSlot));
                assertDoubleEquals(Double.MAX_VALUE, frame.getDouble(doubleSlot));
                assertDoubleEquals(Float.MAX_VALUE, frame.getFloat(floatSlot));
                assertEquals(Integer.MAX_VALUE, frame.getInt(intSlot));
                assertEquals(Long.MAX_VALUE, frame.getLong(longSlot));
                assertEquals(o2, frame.getObject(objectSlot));
            } catch (FrameSlotTypeException ex) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }
    }

    public static class FrameTransferringWithStaticAccessNode extends BytecodeOSRTestNode {
        @CompilationFinal int booleanSlot;
        @CompilationFinal int byteSlot;
        @CompilationFinal int doubleSlot;
        @CompilationFinal int floatSlot;
        @CompilationFinal int intSlot;
        @CompilationFinal int longSlot;
        @CompilationFinal int objectSlot;
        @CompilationFinal Object o1;
        @CompilationFinal Object o2;

        public FrameTransferringWithStaticAccessNode(FrameDescriptor.Builder builder) {
            booleanSlot = builder.addSlot(FrameSlotKind.Static, "booleanValue", null);
            byteSlot = builder.addSlot(FrameSlotKind.Static, "byteValue", null);
            doubleSlot = builder.addSlot(FrameSlotKind.Static, "doubleValue", null);
            floatSlot = builder.addSlot(FrameSlotKind.Static, "floatValue", null);
            intSlot = builder.addSlot(FrameSlotKind.Static, "intValue", null);
            longSlot = builder.addSlot(FrameSlotKind.Static, "longValue", null);
            objectSlot = builder.addSlot(FrameSlotKind.Static, "objectValue", null);

            o1 = new Object();
            o2 = new Object();
        }

        @Override
        public Object executeOSR(VirtualFrame osrFrame, int target, Object interpreterState) {
            checkRegularState(osrFrame);
            setOSRState(osrFrame);
            return 42;
        }

        @Override
        public void copyIntoOSRFrame(VirtualFrame osrFrame, VirtualFrame parentFrame, int target, Object targetMetadata) {
            super.copyIntoOSRFrame(osrFrame, parentFrame, target, targetMetadata);
            // Copying should not trigger a deopt.
            checkInCompiledCode();
        }

        @Override
        public void restoreParentFrame(VirtualFrame osrFrame, VirtualFrame parentFrame) {
            checkInCompiledCode();
            super.restoreParentFrame(osrFrame, parentFrame);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            checkInInterpreter();
            setRegularState(frame);
            return executeLoop(frame);
        }

        public Object executeLoop(VirtualFrame frame) {
            // This node only terminates in compiled code.
            while (true) {
                if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                    Object result = BytecodeOSRNode.tryOSR(this, DEFAULT_TARGET, null, null, frame);
                    if (result != null) {
                        checkOSRState(frame);
                        return result;
                    }
                }
            }
        }

        public void setRegularState(VirtualFrame frame) {
            frame.setBooleanStatic(booleanSlot, true);
            frame.setByteStatic(byteSlot, Byte.MIN_VALUE);
            frame.setDoubleStatic(doubleSlot, Double.MIN_VALUE);
            frame.setFloatStatic(floatSlot, Float.MIN_VALUE);
            frame.setIntStatic(intSlot, Integer.MIN_VALUE);
            frame.setLongStatic(longSlot, Long.MIN_VALUE);
            frame.setObjectStatic(objectSlot, o1);
        }

        public void checkRegularState(VirtualFrame frame) {
            try {
                assertEquals(true, frame.getBooleanStatic(booleanSlot));
                assertEquals(Byte.MIN_VALUE, frame.getByteStatic(byteSlot));
                assertDoubleEquals(Double.MIN_VALUE, frame.getDoubleStatic(doubleSlot));
                assertDoubleEquals(Float.MIN_VALUE, frame.getFloatStatic(floatSlot));
                assertEquals(Integer.MIN_VALUE, frame.getIntStatic(intSlot));
                assertEquals(Long.MIN_VALUE, frame.getLongStatic(longSlot));
                assertEquals(o1, frame.getObjectStatic(objectSlot));
            } catch (FrameSlotTypeException ex) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }

        public void setOSRState(VirtualFrame frame) {
            frame.setBooleanStatic(booleanSlot, false);
            frame.setByteStatic(byteSlot, Byte.MAX_VALUE);
            frame.setDoubleStatic(doubleSlot, Double.MAX_VALUE);
            frame.setFloatStatic(floatSlot, Float.MAX_VALUE);
            frame.setIntStatic(intSlot, Integer.MAX_VALUE);
            frame.setLongStatic(longSlot, Long.MAX_VALUE);
            frame.setObjectStatic(objectSlot, o2);
        }

        public void checkOSRState(VirtualFrame frame) {
            try {
                assertEquals(false, frame.getBooleanStatic(booleanSlot));
                assertEquals(Byte.MAX_VALUE, frame.getByteStatic(byteSlot));
                assertDoubleEquals(Double.MAX_VALUE, frame.getDoubleStatic(doubleSlot));
                assertDoubleEquals(Float.MAX_VALUE, frame.getFloatStatic(floatSlot));
                assertEquals(Integer.MAX_VALUE, frame.getIntStatic(intSlot));
                assertEquals(Long.MAX_VALUE, frame.getLongStatic(longSlot));
                assertEquals(o2, frame.getObjectStatic(objectSlot));
            } catch (FrameSlotTypeException ex) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }
    }

    public static class FrameTransferringNodeWithMergingStaticAccess extends FrameTransferringWithStaticAccessNode {
        private boolean true1 = true;
        private boolean true2 = true;

        public FrameTransferringNodeWithMergingStaticAccess(FrameDescriptor.Builder builder) {
            super(builder);
        }

        @Override
        public Object executeOSR(VirtualFrame osrFrame, int target, Object interpreterState) {
            checkRegularState(osrFrame);
            if (true1) {
                if (true2) {
                    setOSRState(osrFrame);
                }
                // Merges regular state with OSR state.
                boundaryCall();
            }
            // Merges previously merged state with regular state.
            boundaryCall();
            return 42;
        }
    }

    public static class FrameTransferringNodeWithTagUpdate extends FrameTransferringNode {
        public FrameTransferringNodeWithTagUpdate(FrameDescriptor.Builder frameDescriptor) {
            super(frameDescriptor);
        }

        @Override
        public void setOSRState(VirtualFrame frame) {
            super.setOSRState(frame);
            frame.setObject(intSlot, o2);
        }

        @Override
        public void checkOSRState(VirtualFrame frame) {
            try {
                assertEquals(false, frame.getBoolean(booleanSlot));
                assertEquals(Byte.MAX_VALUE, frame.getByte(byteSlot));
                assertDoubleEquals(Double.MAX_VALUE, frame.getDouble(doubleSlot));
                assertDoubleEquals(Float.MAX_VALUE, frame.getFloat(floatSlot));
                assertEquals(o2, frame.getObject(intSlot));
                assertEquals(Long.MAX_VALUE, frame.getLong(longSlot));
                assertEquals(o2, frame.getObject(objectSlot));
            } catch (FrameSlotTypeException ex) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }
    }

    public static class FrameTransferringNodeWithUninitializedSlots extends FrameTransferringNode {
        final Object defaultValue;

        public FrameTransferringNodeWithUninitializedSlots(FrameDescriptor.Builder frameDescriptor, Object defaultValue) {
            super(frameDescriptor);
            this.defaultValue = defaultValue;
        }

        @Override
        public void setRegularState(VirtualFrame frame) {
            frame.setBoolean(booleanSlot, true);
            // everything else is uninitialized
        }

        @Override
        public void checkRegularState(VirtualFrame frame) {
            try {
                assertEquals(true, frame.getBoolean(booleanSlot));
                // these slots are uninitialized
                assertEquals(defaultValue, frame.getObject(byteSlot));
                assertEquals(defaultValue, frame.getObject(byteSlot));
                assertEquals(defaultValue, frame.getObject(doubleSlot));
                assertEquals(defaultValue, frame.getObject(floatSlot));
                assertEquals(defaultValue, frame.getObject(intSlot));
                assertEquals(defaultValue, frame.getObject(longSlot));
                assertEquals(defaultValue, frame.getObject(objectSlot));
            } catch (FrameSlotTypeException ex) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }

        @Override
        public void setOSRState(VirtualFrame frame) {
            frame.setBoolean(booleanSlot, false);
            // everything else is uninitialized
        }

        @Override
        public void checkOSRState(VirtualFrame frame) {
            try {
                assertEquals(false, frame.getBoolean(booleanSlot));
                // these slots are uninitialized
                assertEquals(defaultValue, frame.getObject(byteSlot));
                assertEquals(defaultValue, frame.getObject(byteSlot));
                assertEquals(defaultValue, frame.getObject(doubleSlot));
                assertEquals(defaultValue, frame.getObject(floatSlot));
                assertEquals(defaultValue, frame.getObject(intSlot));
                assertEquals(defaultValue, frame.getObject(longSlot));
                assertEquals(defaultValue, frame.getObject(objectSlot));
            } catch (FrameSlotTypeException ex) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }
    }

    public static class FrameTransferringNodeWithUninitializedStaticSlots extends FrameTransferringWithStaticAccessNode {
        final Object defaultValue;

        public FrameTransferringNodeWithUninitializedStaticSlots(FrameDescriptor.Builder frameDescriptor, Object defaultValue) {
            super(frameDescriptor);
            this.defaultValue = defaultValue;
        }

        private void ensureUninitReturnsDefault(VirtualFrame frame, int index) {
            assertEquals(defaultValue, frame.getObjectStatic(index));
            assertEquals((byte) 0, frame.getByteStatic(index));
            assertEquals(false, frame.getBooleanStatic(index));
            assertEquals(0, frame.getIntStatic(index));
            assertEquals(0L, frame.getLongStatic(index));
            assertEquals(0f, frame.getFloatStatic(index));
            assertEquals(0d, frame.getDoubleStatic(index));
        }

        @Override
        public void setRegularState(VirtualFrame frame) {
            frame.setByteStatic(byteSlot, (byte) 1);
            // everything else is uninitialized
        }

        @Override
        public void checkRegularState(VirtualFrame frame) {
            try {
                assertEquals((byte) 1, frame.getByteStatic(byteSlot));
                // these slots are uninitialized
                ensureUninitReturnsDefault(frame, booleanSlot);
                ensureUninitReturnsDefault(frame, doubleSlot);
                ensureUninitReturnsDefault(frame, floatSlot);
                ensureUninitReturnsDefault(frame, intSlot);
                ensureUninitReturnsDefault(frame, longSlot);
                ensureUninitReturnsDefault(frame, objectSlot);
            } catch (FrameSlotTypeException ex) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }

        @Override
        public void setOSRState(VirtualFrame frame) {
            frame.setByteStatic(byteSlot, (byte) 2);
            // everything else is uninitialized
        }

        @Override
        public void checkOSRState(VirtualFrame frame) {
            try {
                assertEquals((byte) 2, frame.getByteStatic(byteSlot));
                // these slots are uninitialized
                ensureUninitReturnsDefault(frame, booleanSlot);
                ensureUninitReturnsDefault(frame, doubleSlot);
                ensureUninitReturnsDefault(frame, floatSlot);
                ensureUninitReturnsDefault(frame, intSlot);
                ensureUninitReturnsDefault(frame, longSlot);
                ensureUninitReturnsDefault(frame, objectSlot);
            } catch (FrameSlotTypeException ex) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }
    }

    public static class OSRDisablingTransferringNode extends FrameTransferringNode {
        @CompilationFinal int staticSlot;

        public OSRDisablingTransferringNode(FrameDescriptor.Builder builder) {
            super(builder);
            staticSlot = builder.addSlot(FrameSlotKind.Static, "static", null);
        }

        @Override
        public void setRegularState(VirtualFrame frame) {
            super.setRegularState(frame);
            frame.setIntStatic(staticSlot, Integer.MIN_VALUE);
        }

        @Override
        public void checkRegularState(VirtualFrame frame) {
            super.checkRegularState(frame);
            assertEquals(Integer.MIN_VALUE, frame.getIntStatic(staticSlot));
        }

        @Override
        public void setOSRState(VirtualFrame frame) {
            super.setOSRState(frame);
            frame.setIntStatic(staticSlot, Integer.MAX_VALUE);
        }

        @Override
        public void checkOSRState(VirtualFrame frame) {
            super.checkOSRState(frame);
            assertEquals(Integer.MAX_VALUE, frame.getIntStatic(staticSlot));
        }

        @Override
        public void restoreParentFrame(VirtualFrame osrFrame, VirtualFrame parentFrame) {
            // Make sure disabling is done out of compiled code.
            CompilerDirectives.transferToInterpreterAndInvalidate();

            getGraalOSRMetadata().forceDisable();
            assertEquals(true, getGraalOSRMetadata().isDisabled());

            super.restoreParentFrame(osrFrame, parentFrame);
        }
    }

    public static class RecursiveBytecodeOSRTestNode extends BytecodeOSRTestNode {
        private static final Object RETURN_VALUE = "Success";
        private static final Object FAIL_VALUE = "No exception thrown";

        @Override
        Object execute(VirtualFrame frame) {
            try {
                doExecute(frame);
            } catch (AssertionError e) {
                if (e.getMessage().contains("Max OSR compilation re-attempts reached")) {
                    return RETURN_VALUE;
                }
                throw e;
            }
            return FAIL_VALUE;
        }

        Object doExecute(VirtualFrame frame) {
            while (true) {
                if (CompilerDirectives.inCompiledCode()) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                }
                if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                    Object result = BytecodeOSRNode.tryOSR(this, DEFAULT_TARGET, null, null, frame);
                    if (result != null) {
                        return result;
                    }
                }
            }
        }

        @Override
        public Object executeOSR(VirtualFrame osrFrame, int target, Object interpreterState) {
            return doExecute(osrFrame);
        }
    }

    public static class BytecodeNode extends BytecodeOSRTestNode implements BytecodeOSRNode {
        @CompilationFinal(dimensions = 1) private final byte[] bytecodes;
        private final int regsOffset;
        private final int regsCount;

        boolean compiled;

        public static class Bytecode {
            public static final byte RETURN = 0;
            public static final byte INC = 1;
            public static final byte DEC = 2;
            public static final byte JMPNONZERO = 3;
            public static final byte COPY = 4;
        }

        public BytecodeNode(int numLocals, FrameDescriptor.Builder frameDescriptor, byte[] bytecodes) {
            this.bytecodes = bytecodes;
            this.regsOffset = frameDescriptor.addSlots(numLocals, FrameSlotKind.Int);
            this.regsCount = numLocals;
            this.compiled = false;
        }

        @Override
        protected void setInt(Frame frame, int stackIndex, int value) {
            frame.setInt(regsOffset + stackIndex, value);
        }

        @Override
        protected int getInt(Frame frame, int stackIndex) {
            try {
                return frame.getInt(regsOffset + stackIndex);
            } catch (FrameSlotTypeException e) {
                throw new IllegalStateException("Error accessing stack slot " + stackIndex);
            }
        }

        @Override
        @ExplodeLoop
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            for (int i = 0; i < regsCount; i++) {
                if (i < args.length) {
                    frame.setInt(regsOffset + i, (Integer) args[i]);
                } else {
                    frame.setInt(regsOffset + i, 0);
                }
            }

            return executeFromBCI(frame, 0);
        }

        @Override
        @ExplodeLoop
        public void copyIntoOSRFrame(VirtualFrame osrFrame, VirtualFrame parentFrame, int target, Object targetMetadata) {
            for (int i = 0; i < regsCount; i++) {
                setInt(osrFrame, i, getInt(parentFrame, i));
            }
        }

        @Override
        public Object executeOSR(VirtualFrame osrFrame, int target, Object interpreterState) {
            return executeFromBCI(osrFrame, target);
        }

        @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.MERGE_EXPLODE)
        public Object executeFromBCI(VirtualFrame frame, int startBCI) {
            this.compiled = CompilerDirectives.inCompiledCode();
            CompilerAsserts.partialEvaluationConstant(startBCI);
            int bci = startBCI;
            while (true) {
                CompilerAsserts.partialEvaluationConstant(bci);
                switch (bytecodes[bci]) {
                    case Bytecode.RETURN: {
                        byte idx = bytecodes[bci + 1];
                        return getInt(frame, idx);
                    }
                    case Bytecode.INC: {
                        byte idx = bytecodes[bci + 1];
                        setInt(frame, idx, getInt(frame, idx) + 1);

                        bci = bci + 2;
                        continue;
                    }
                    case Bytecode.DEC: {
                        byte idx = bytecodes[bci + 1];
                        setInt(frame, idx, getInt(frame, idx) - 1);

                        bci = bci + 2;
                        continue;
                    }
                    case Bytecode.JMPNONZERO: {
                        byte idx = bytecodes[bci + 1];
                        int value = getInt(frame, idx);
                        if (value != 0) {
                            int target = bci + bytecodes[bci + 2];
                            if (target < bci) { // back-edge
                                if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                                    Object result = BytecodeOSRNode.tryOSR(this, target, null, null, frame);
                                    if (result != null) {
                                        return result;
                                    }
                                }
                            }
                            bci = target;
                        } else {
                            bci = bci + 3;
                        }
                        continue;
                    }
                    case Bytecode.COPY: {
                        byte src = bytecodes[bci + 1];
                        byte dest = bytecodes[bci + 2];
                        setInt(frame, dest, getInt(frame, src));
                        bci = bci + 3;
                    }
                }
            }
        }
    }

    /**
     * Copy of the {@link FixedIterationLoopWithChild} as a {@link RootNode}. Used to test node
     * invalidation edge case.
     */
    static class FixedIterationLoopRootNode extends RootNode implements BytecodeOSRNode {
        public static final int DEFAULT_TARGET = -1;
        static final String OSR_RESULT = "osr result";
        static final String NORMAL_RESULT = "normal result";

        @Child Node child;
        @CompilationFinal Object osrMetadata;
        @CompilationFinal int indexSlot;
        @CompilationFinal int numIterationsSlot;

        protected FixedIterationLoopRootNode(FrameDescriptor.Builder fdBuilder, Node child, int indexSlot, int numIterationsSlot) {
            super(null, fdBuilder.build());
            this.child = child;
            this.indexSlot = indexSlot;
            this.numIterationsSlot = numIterationsSlot;

        }

        @Override
        public Object getOSRMetadata() {
            return osrMetadata;
        }

        @Override
        public void setOSRMetadata(Object osrMetadata) {
            this.osrMetadata = osrMetadata;
        }

        BytecodeOSRMetadata getGraalOSRMetadata() {
            return (BytecodeOSRMetadata) getOSRMetadata();
        }

        protected int getInt(Frame frame, int frameSlot) {
            try {
                return frame.getInt(frameSlot);
            } catch (FrameSlotTypeException e) {
                throw new IllegalStateException("Error accessing frame slot " + frameSlot);
            }
        }

        protected void setInt(Frame frame, int frameSlot, int value) {
            frame.setInt(frameSlot, value);
        }

        @Override
        public void copyIntoOSRFrame(VirtualFrame osrFrame, VirtualFrame parentFrame, int target, Object targetMetadata) {
            setInt(osrFrame, indexSlot, getInt(parentFrame, indexSlot));
            setInt(osrFrame, numIterationsSlot, getInt(parentFrame, numIterationsSlot));
        }

        @Override
        public Object executeOSR(VirtualFrame osrFrame, int target, Object interpreterState) {
            return executeLoop(osrFrame, getInt(osrFrame, numIterationsSlot));
        }

        @Override
        public Object execute(VirtualFrame frame) {
            frame.setInt(indexSlot, 0);
            int numIterations = (Integer) frame.getArguments()[0];
            frame.setInt(numIterationsSlot, numIterations);
            return executeLoop(frame, numIterations);
        }

        protected Object executeLoop(VirtualFrame frame, int numIterations) {
            int loopIterations = 0;
            try {
                for (int i = frame.getInt(indexSlot); i < numIterations; i++) {
                    loopIterations++;
                    frame.setInt(indexSlot, i);
                    if (i + 1 < numIterations) { // back-edge will be taken
                        if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                            Object result = BytecodeOSRNode.tryOSR(this, DEFAULT_TARGET, null, null, frame);
                            if (result != null) {
                                return result;
                            }
                        }
                    }
                }
                return CompilerDirectives.inCompiledCode() ? OSR_RESULT : NORMAL_RESULT;
            } catch (FrameSlotTypeException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            } finally {
                LoopNode.reportLoopCount(this, loopIterations);
            }
        }
    }

    /**
     * This node uses a long to encode its interpreter target. Unlike FixedIterationLoop, this one
     * encodes "i" in the target (rather than the frame). It also stuffs the expected result into
     * the upper 4 bytes of the target.
     */
    public static class FixedIterationLoopLongTarget extends BytecodeOSRTestNode {
        @CompilationFinal int numIterationsSlot;
        @CompilationFinal boolean prepared = false;

        public FixedIterationLoopLongTarget(FrameDescriptor.Builder builder) {
            numIterationsSlot = builder.addSlot(FrameSlotKind.Int, "numIterations", null);
        }

        @Override
        public void copyIntoOSRFrame(VirtualFrame osrFrame, VirtualFrame parentFrame, long target, Object targetMetadata) {
            if ((int) target != OSR_THRESHOLD - 1) {
                // validate that the correct target is passed
                throw CompilerDirectives.shouldNotReachHere();
            }
            setInt(osrFrame, numIterationsSlot, getInt(parentFrame, numIterationsSlot));
        }

        @Override
        public void copyIntoOSRFrame(VirtualFrame osrFrame, VirtualFrame parentFrame, int target, Object targetMetadata) {
            // ensure this overload is *not* called.
            throw CompilerDirectives.shouldNotReachHere();
        }

        @Override
        public void prepareOSR(long target) {
            if ((int) target != OSR_THRESHOLD - 1) {
                // validate that the correct target is passed
                throw CompilerDirectives.shouldNotReachHere();
            }
            prepared = true;
        }

        @Override
        public void prepareOSR(int target) {
            // ensure this overload is *not* called.
            throw CompilerDirectives.shouldNotReachHere();
        }

        @Override
        public Object executeOSR(VirtualFrame osrFrame, long target, Object interpreterState) {
            return executeLoop(osrFrame, target);
        }

        @Override
        public Object executeOSR(VirtualFrame osrFrame, int target, Object interpreterState) {
            // ensure this overload is *not* called.
            throw CompilerDirectives.shouldNotReachHere();
        }

        @Override
        public Object execute(VirtualFrame frame) {
            frame.setInt(numIterationsSlot, (Integer) frame.getArguments()[0]);
            int compiledResult = (Integer) frame.getArguments()[1];
            // encoding: [compiledResult: 32] | [i: 32]
            long target = ((compiledResult & 0xFFFFFFFFL) << 32) | 0;
            return executeLoop(frame, target);
        }

        protected Object executeLoop(VirtualFrame frame, long target) {
            int numIterations = getInt(frame, numIterationsSlot);
            try {
                for (int i = (int) target; i < numIterations; i++) {
                    if (i + 1 < numIterations) { // back-edge will be taken
                        if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                            long osrState = (target & 0xFFFFFFFF00000000L) | (i & 0xFFFFFFFFL);
                            Object result = BytecodeOSRNode.tryOSR(this, osrState, null, null, frame);
                            if (result != null) {
                                return result;
                            }
                        }
                    }
                }
                return prepared && CompilerDirectives.inCompiledCode() ? (int) (target >>> 32) : -123;
            } catch (FrameSlotTypeException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }
    }

    /**
     * Same as {@link FixedIterationLoopLongTarget}, except it calls into the int overload of
     * {@link BytecodeOSRNode#tryOSR} with the loop index (i.e., the "compiledResult" is lost). This
     * behaviour is nonsensical but demonstrates that the int value will just be widened and passed
     * to the hooks as usual.
     */
    public static class FixedIterationLoopLongTargetUsesIntOverload extends FixedIterationLoopLongTarget {

        public FixedIterationLoopLongTargetUsesIntOverload(Builder builder) {
            super(builder);
        }

        @Override
        protected Object executeLoop(VirtualFrame frame, long target) {
            int numIterations = getInt(frame, numIterationsSlot);
            try {
                for (int i = (int) target; i < numIterations; i++) {
                    if (i + 1 < numIterations) { // back-edge will be taken
                        if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                            Object result = BytecodeOSRNode.tryOSR(this, i, null, null, frame);
                            if (result != null) {
                                return result;
                            }
                        }
                    }
                }
                return prepared && CompilerDirectives.inCompiledCode() ? (int) (target >>> 32) : -123;
            } catch (FrameSlotTypeException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }
    }

    /**
     * Base class that calls the long overload of tryOSR. Child classes written without long
     * overloads for certain hooks should cause compilation to fail gracefully.
     */
    public abstract static class FixedIterationLoopLongTargetBadOverload extends BytecodeOSRTestNode {
        @CompilationFinal int numIterationsSlot;
        static final String OSR_RESULT = "osr result";
        static final String NORMAL_RESULT = "normal result";

        public FixedIterationLoopLongTargetBadOverload(FrameDescriptor.Builder builder) {
            numIterationsSlot = builder.addSlot(FrameSlotKind.Long, "numIterations", null);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            int numIterations = (Integer) frame.getArguments()[0];
            // Offset the counter by Integer.MAX_VALUE so we encounter long-sized values.
            long numIterationsAsLong = (long) numIterations + Integer.MAX_VALUE;
            frame.setLong(numIterationsSlot, numIterationsAsLong);
            return executeLoop(frame, Integer.MAX_VALUE);
        }

        public Object executeLoop(VirtualFrame frame, long target) {
            long numIterations = frame.getLong(numIterationsSlot);
            try {
                for (long i = target; i < numIterations; i++) {
                    if (i + 1 < numIterations) { // back-edge will be taken
                        if (BytecodeOSRNode.pollOSRBackEdge(this, 1)) {
                            Object result = BytecodeOSRNode.tryOSR(this, i, null, null, frame);
                            if (result != null) {
                                return result;
                            }
                        }
                    }
                }
                return CompilerDirectives.inCompiledCode() ? OSR_RESULT : NORMAL_RESULT;
            } catch (FrameSlotTypeException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("Error accessing index slot");
            }
        }
    }

    /**
     * This class is missing the prepareOSR long overload.
     */
    public static class FixedIterationLoopLongTargetLoopBadOverload1 extends FixedIterationLoopLongTargetBadOverload {

        public FixedIterationLoopLongTargetLoopBadOverload1(Builder builder) {
            super(builder);
        }

        @Override
        public void copyIntoOSRFrame(VirtualFrame osrFrame, VirtualFrame parentFrame, long target, Object targetMetadata) {
            transferOSRFrame(osrFrame, parentFrame, target, targetMetadata);
        }

        @Override
        public Object executeOSR(VirtualFrame osrFrame, long target, Object interpreterState) {
            return executeLoop(osrFrame, target);
        }
    }

    /**
     * This class is missing the copyIntoOSRFrame long overload.
     */
    public static class FixedIterationLoopLongTargetLoopBadOverload2 extends FixedIterationLoopLongTargetBadOverload {

        public FixedIterationLoopLongTargetLoopBadOverload2(Builder builder) {
            super(builder);
        }

        @Override
        public void prepareOSR(long target) {
            // do nothing
        }

        @Override
        public Object executeOSR(VirtualFrame osrFrame, long target, Object interpreterState) {
            return executeLoop(osrFrame, target);
        }
    }

    /**
     * This class is missing the executeOSR long overload.
     */
    public static class FixedIterationLoopLongTargetLoopBadOverload3 extends FixedIterationLoopLongTargetBadOverload {

        public FixedIterationLoopLongTargetLoopBadOverload3(Builder builder) {
            super(builder);
        }

        @Override
        public void prepareOSR(long target) {
            // do nothing
        }

        @Override
        public void copyIntoOSRFrame(VirtualFrame osrFrame, VirtualFrame parentFrame, long target, Object targetMetadata) {
            transferOSRFrame(osrFrame, parentFrame, target, targetMetadata);
        }

    }
}
