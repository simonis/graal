/*
 * Copyright (c) 2011, 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.virtual.phases.ea;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.debug.DebugCloseable;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.ControlSplitNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.FloatingNode;
import jdk.graal.compiler.nodes.java.MonitorIdNode;
import jdk.graal.compiler.nodes.virtual.AllocatedObjectNode;
import jdk.graal.compiler.nodes.virtual.CommitAllocationNode;
import jdk.graal.compiler.nodes.virtual.LockState;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.virtual.phases.ea.EffectList.Effect;

public abstract class PartialEscapeBlockState<T extends PartialEscapeBlockState<T>> extends EffectsBlockState<T> {

    private static final ObjectState[] EMPTY_ARRAY = new ObjectState[0];

    /**
     * This array contains the state of all virtual objects, indexed by
     * {@link VirtualObjectNode#getObjectId()}. Entries in this array may be null if the
     * corresponding virtual object is not alive or reachable currently.
     */
    private ObjectState[] objectStates;

    public boolean contains(VirtualObjectNode value) {
        for (ObjectState state : objectStates) {
            if (state != null && state.isVirtual() && state.getEntries() != null) {
                for (ValueNode entry : state.getEntries()) {
                    if (entry == value) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static final class RefCount {
        private int refCount = 1;
    }

    /**
     * Usage count for the objectStates array, to avoid unnecessary copying.
     */
    private RefCount arrayRefCount;

    private final OptionValues options;
    private final DebugContext debug;

    /**
     * Final subclass of PartialEscapeBlockState, for performance and to make everything behave
     * nicely with generics.
     */
    public static final class Final extends PartialEscapeBlockState<Final> {

        public Final(OptionValues options, DebugContext debug) {
            super(options, debug);
        }

        public Final(Final other) {
            super(other);
        }
    }

    protected PartialEscapeBlockState(OptionValues options, DebugContext debug) {
        objectStates = EMPTY_ARRAY;
        arrayRefCount = new RefCount();
        this.options = options;
        this.debug = debug;
    }

    @SuppressWarnings("this-escape")
    protected PartialEscapeBlockState(PartialEscapeBlockState<T> other) {
        super(other);
        adoptAddObjectStates(other);
        options = other.options;
        debug = other.debug;
    }

    public ObjectState getObjectState(int object) {
        ObjectState state = objectStates[object];
        assert state != null;
        return state;
    }

    public ObjectState getObjectStateOptional(int object) {
        return object >= objectStates.length ? null : objectStates[object];
    }

    public boolean hasObjectState(int object) {
        return object >= 0 && object < objectStates.length && objectStates[object] != null;
    }

    /**
     * Asserts that the given virtual object is available/reachable in the current state.
     */
    public ObjectState getObjectState(VirtualObjectNode object) {
        ObjectState state = objectStates[object.getObjectId()];
        assert state != null;
        return state;
    }

    public ObjectState getObjectStateOptional(VirtualObjectNode object) {
        int id = object.getObjectId();
        return id >= objectStates.length ? null : objectStates[id];
    }

    private ObjectState[] getObjectStateArrayForModification() {
        if (arrayRefCount.refCount > 1) {
            objectStates = objectStates.clone();
            arrayRefCount.refCount--;
            arrayRefCount = new RefCount();
        }
        return objectStates;
    }

    private ObjectState getObjectStateForModification(int object) {
        ObjectState[] array = getObjectStateArrayForModification();
        ObjectState objectState = array[object];
        if (objectState.copyOnWrite) {
            array[object] = objectState = objectState.cloneState();
        }
        return objectState;
    }

    public void setEntry(int object, int entryIndex, ValueNode value) {
        if (objectStates[object].getEntry(entryIndex) != value) {
            getObjectStateForModification(object).setEntry(entryIndex, value);
        }
    }

    public void escape(int object, ValueNode materialized) {
        getObjectStateForModification(object).escape(materialized);
    }

    public boolean isNonImmediateRecursiveLock(int object, MonitorIdNode monitorId) {
        ObjectState state = getObjectStateForModification(object);
        if (state.hasLocks()) {
            return state.getLocks().monitorId.getLockDepth() < monitorId.getLockDepth() - 1;
        }
        return false;
    }

    public void addLock(int object, MonitorIdNode monitorId) {
        getObjectStateForModification(object).addLock(monitorId);
    }

    public MonitorIdNode removeLock(int object) {
        return getObjectStateForModification(object).removeLock();
    }

    public void setEnsureVirtualized(int object, boolean ensureVirtualized) {
        if (objectStates[object].getEnsureVirtualized() != ensureVirtualized) {
            getObjectStateForModification(object).setEnsureVirtualized(ensureVirtualized);
        }
    }

    public void updateMaterializedValue(int object, ValueNode value) {
        if (objectStates[object].getMaterializedValue() != value) {
            getObjectStateForModification(object).updateMaterializedValue(value);
        }
    }

    /**
     * Materializes the given virtual object and produces the necessary effects in the effects list.
     * This transitively also materializes all other virtual objects that are reachable from the
     * entries.
     */
    @SuppressWarnings("try")
    public void materializeBefore(FixedNode fixed, VirtualObjectNode virtual, boolean requiresStrictLockOrder, ArrayList<VirtualObjectNode> virtualObjects, GraphEffectList materializeEffects) {
        PartialEscapeClosure.COUNTER_MATERIALIZATIONS.increment(fixed.getDebug());
        List<AllocatedObjectNode> objects = new ArrayList<>(2);
        List<ValueNode> values = new ArrayList<>(8);
        List<List<MonitorIdNode>> locks = new ArrayList<>();
        List<ValueNode> otherAllocations = new ArrayList<>(2);
        List<Boolean> ensureVirtual = new ArrayList<>(2);
        materializeWithCommit(fixed, virtual, objects, locks, values, ensureVirtual, otherAllocations, requiresStrictLockOrder, virtualObjects, materializeEffects);
        /*
         * because all currently virtualized allocations will be materialized in 1 commit alloc node
         * with barriers, we ignore other allocations as we only process new instance and commit
         * allocation nodes
         */
        materializeEffects.addAllocationDelta(objects.size() > 0 ? -1 : 0);
        materializeEffects.addVirtualizationDelta(-(objects.size() + otherAllocations.size()));
        materializeEffects.add(new Effect("materializeBefore") {
            @Override
            public void apply(StructuredGraph graph, ArrayList<Node> obsoleteNodes) {
                for (ValueNode alloc : otherAllocations) {
                    ValueNode otherAllocation = graph.addOrUniqueWithInputs(alloc);
                    if (otherAllocation instanceof FixedWithNextNode) {
                        graph.addBeforeFixed(fixed, (FixedWithNextNode) otherAllocation);
                    } else {
                        assert otherAllocation instanceof FloatingNode : Assertions.errorMessage(otherAllocation, fixed, virtual, materializeEffects);
                    }
                }
                if (!objects.isEmpty()) {
                    CommitAllocationNode commit;
                    if (fixed.predecessor() instanceof CommitAllocationNode) {
                        commit = (CommitAllocationNode) fixed.predecessor();
                    } else {
                        try (DebugCloseable context = graph.withNodeSourcePosition(NodeSourcePosition.placeholder(graph.method()))) {
                            commit = graph.add(new CommitAllocationNode());
                            if (fixed.predecessor() != null && fixed.predecessor() instanceof FixedWithNextNode) {
                                graph.addBeforeFixed(fixed, commit);
                            } else {
                                if (fixed instanceof AbstractBeginNode abs && abs.predecessor() instanceof ControlSplitNode) {
                                    /*
                                     * We have an abstract begin node (for example a loop exit)
                                     * directly as a split successor. We cannot just insert the node
                                     * here. We have to build a new node after which we can insert.
                                     */
                                    BeginNode b = graph.add(new BeginNode());
                                    fixed.replaceAtPredecessor(b);
                                    b.setNext(fixed);
                                    graph.addBeforeFixed(fixed, commit);
                                } else {
                                    throw GraalError.shouldNotReachHere("Complex control flow pattern - cannot easily insert materialization before " + fixed);
                                }
                            }
                        }
                    }
                    for (AllocatedObjectNode obj : objects) {
                        graph.addWithoutUnique(obj);
                        commit.getVirtualObjects().add(obj.getVirtualObject());
                        obj.setCommit(commit);
                    }
                    for (ValueNode value : values) {
                        commit.getValues().add(graph.addOrUniqueWithInputs(value));
                    }
                    for (List<MonitorIdNode> monitorIds : locks) {
                        commit.addLocks(monitorIds);
                    }
                    commit.getEnsureVirtual().addAll(ensureVirtual);

                    assert commit.usages().filter(AllocatedObjectNode.class).count() == commit.getUsageCount() : Assertions.errorMessage(commit, commit.usages(), commit.getUsageCount());
                    List<AllocatedObjectNode> materializedValues = commit.usages().filter(AllocatedObjectNode.class).snapshot();
                    for (int i = 0; i < commit.getValues().size(); i++) {
                        if (materializedValues.contains(commit.getValues().get(i))) {
                            commit.getValues().set(i, ((AllocatedObjectNode) commit.getValues().get(i)).getVirtualObject());
                        }
                    }
                }
            }

            @Override
            void format(StringBuilder str) {
                format(str, new String[]{"otherAllocations", "fixed", "objects", "values", "locks", "ensureVirtual"},
                                new Object[]{otherAllocations, fixed, objects, values, locks, ensureVirtual});
            }
        });
    }

    private void materializeWithCommit(FixedNode fixed, VirtualObjectNode virtual, List<AllocatedObjectNode> objects, List<List<MonitorIdNode>> locks, List<ValueNode> values,
                    List<Boolean> ensureVirtual, List<ValueNode> otherAllocations, boolean requiresStrictLockOrder, ArrayList<VirtualObjectNode> virtualObjects, GraphEffectList materializeEffects) {
        ObjectState obj = getObjectState(virtual);

        ValueNode[] entries = obj.getEntries();
        ValueNode representation = virtual.getMaterializedRepresentation(fixed, entries, obj.getLocks());
        escape(virtual.getObjectId(), representation);
        obj = getObjectState(virtual);
        PartialEscapeClosure.updateStatesForMaterialized(this, virtual, obj.getMaterializedValue());
        if (representation instanceof AllocatedObjectNode) {
            objects.add((AllocatedObjectNode) representation);
            locks.add(LockState.asList(obj.getLocks()));
            ensureVirtual.add(obj.getEnsureVirtualized());
            int pos = values.size();
            while (values.size() < pos + entries.length) {
                values.add(null);
            }
            for (int i = 0; i < entries.length; i++) {
                if (entries[i] instanceof VirtualObjectNode) {
                    VirtualObjectNode entryVirtual = (VirtualObjectNode) entries[i];
                    ObjectState entryObj = getObjectState(entryVirtual);
                    if (entryObj.isVirtual()) {
                        materializeWithCommit(fixed, entryVirtual, objects, locks, values, ensureVirtual, otherAllocations, requiresStrictLockOrder, virtualObjects, materializeEffects);
                        entryObj = getObjectState(entryVirtual);
                    }
                    values.set(pos + i, entryObj.getMaterializedValue());
                } else {
                    values.set(pos + i, entries[i]);
                }
            }

            if (requiresStrictLockOrder && obj.hasLocks()) {
                int lockDepth = obj.getMaximumLockDepth();
                for (VirtualObjectNode other : virtualObjects) {
                    if (other != virtual && hasObjectState(other.getObjectId())) {
                        ObjectState otherState = getObjectState(other);
                        if (otherState.isVirtual() && otherState.hasLocks() && otherState.getMinimumLockDepth() < lockDepth) {
                            materializeWithCommit(fixed, other, objects, locks, values, ensureVirtual, otherAllocations, requiresStrictLockOrder, virtualObjects, materializeEffects);
                        }
                    }
                }
            }

            objectMaterialized(virtual, (AllocatedObjectNode) representation, values.subList(pos, pos + entries.length));
        } else {
            VirtualUtil.trace(options, debug, "materialized %s as %s", virtual, representation);
            otherAllocations.add(representation);
            assert obj.getLocks() == null;
        }
        materializeEffects.addLog(fixed.graph().getOptimizationLog(),
                        optimizationLog -> optimizationLog.getPartialEscapeLog().objectMaterialized(virtual));
    }

    protected void objectMaterialized(VirtualObjectNode virtual, AllocatedObjectNode representation, List<ValueNode> values) {
        VirtualUtil.trace(options, debug, "materialized %s as %s with values %s", virtual, representation, values);
    }

    public void addObject(int virtual, ObjectState state) {
        ensureSize(virtual)[virtual] = state;
    }

    private ObjectState[] ensureSize(int objectId) {
        if (objectStates.length <= objectId) {
            objectStates = Arrays.copyOf(objectStates, Math.max(objectId * 2, 4));
            arrayRefCount.refCount--;
            arrayRefCount = new RefCount();
            return objectStates;
        } else {
            return getObjectStateArrayForModification();
        }
    }

    public int getStateCount() {
        return objectStates.length;
    }

    @Override
    public String toString() {
        return super.toString() + ", Object States: " + Arrays.toString(objectStates);
    }

    @Override
    public boolean equivalentTo(T other) {
        int length = Math.max(objectStates.length, other.getStateCount());
        for (int i = 0; i < length; i++) {
            ObjectState left = getObjectStateOptional(i);
            ObjectState right = other.getObjectStateOptional(i);
            if (left != right) {
                if (left == null || right == null) {
                    return false;
                }
                if (!left.equals(right)) {
                    return false;
                }
            }
        }
        return true;
    }

    public void resetObjectStates(int size) {
        objectStates = new ObjectState[size];
    }

    public static boolean identicalObjectStates(PartialEscapeBlockState<?>[] states) {
        for (int i = 1; i < states.length; i++) {
            if (states[0].objectStates != states[i].objectStates) {
                return false;
            }
        }
        return true;
    }

    public static boolean identicalObjectStates(PartialEscapeBlockState<?>[] states, int object) {
        for (int i = 1; i < states.length; i++) {
            if (states[0].objectStates[object] != states[i].objectStates[object]) {
                return false;
            }
        }
        return true;
    }

    public void adoptAddObjectStates(PartialEscapeBlockState<?> other) {
        if (objectStates != null) {
            arrayRefCount.refCount--;
        }
        objectStates = other.objectStates;
        arrayRefCount = other.arrayRefCount;

        if (arrayRefCount.refCount == 1) {
            for (ObjectState state : objectStates) {
                if (state != null) {
                    state.share();
                }
            }
        }
        arrayRefCount.refCount++;
    }
}
