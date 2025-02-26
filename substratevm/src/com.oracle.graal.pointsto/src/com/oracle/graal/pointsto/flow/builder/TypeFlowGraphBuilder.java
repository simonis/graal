/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.pointsto.flow.builder;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.AlwaysEnabledPredicateFlow;
import com.oracle.graal.pointsto.flow.TypeFlow;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.typestate.PointsToStats;
import com.oracle.graal.pointsto.util.AnalysisError;
import com.oracle.svm.util.ClassUtil;

import jdk.graal.compiler.nodes.ParameterNode;

public class TypeFlowGraphBuilder {
    private final PointsToAnalysis bb;
    /**
     * The data flow sink builders are the nodes that should not be removed. They are data flow
     * sinks in the context of type flows graph that it creates, i.e., they collect useful
     * information for the analysis. Through the TypeFlowBuilder.useDependencies and
     * TypeFlowBuilder.observerDependencies links these nodes reach to their inputs, transitively,
     * and determine all the nodes that they depend on and must be retained.
     */
    private final List<TypeFlowBuilder<?>> dataFlowSinkBuilders;

    public TypeFlowGraphBuilder(PointsToAnalysis bb) {
        this.bb = bb;
        dataFlowSinkBuilders = new ArrayList<>();
    }

    /**
     * Register a type flow builder as a sink, i.e., a builder for a flow that should not be removed
     * since it is a leaf in the data flow graph. These are actual parameters (i.e., values passed
     * as parameters for calls), field stores, indexed loads, all invokes, etc.
     */
    public void registerSinkBuilder(TypeFlowBuilder<?> sinkBuilder) {
        dataFlowSinkBuilders.add(sinkBuilder);
    }

    /**
     * A list of method for which we want to retain the parameters since they are needed for
     * collecting wait/notify field info and hash code field info.
     */
    private static final List<String> waitNotifyHashCodeMethods = new ArrayList<>();
    static {
        try {
            Method wait = Object.class.getMethod("wait", long.class);
            waitNotifyHashCodeMethods.add(format(wait));

            Method notify = Object.class.getMethod("notify");
            waitNotifyHashCodeMethods.add(format(notify));

            Method notifyAll = Object.class.getMethod("notifyAll");
            waitNotifyHashCodeMethods.add(format(notifyAll));

            Method hashCode = System.class.getMethod("identityHashCode", Object.class);
            waitNotifyHashCodeMethods.add(format(hashCode));
        } catch (NoSuchMethodException e) {
            throw AnalysisError.shouldNotReachHere(e);
        }
    }

    /** Format a reflection method using the same format as JavaMethod.format("%H.%n(%p)"). */
    private static String format(Method m) {
        return m.getDeclaringClass().getName() + "." + m.getName() +
                        "(" + Arrays.stream(m.getParameterTypes()).map(Class::getName).collect(Collectors.joining(", ")) + ")";

    }

    /**
     * Check if the formal parameter is a parameter of one of the wait/notify/hashCode methods. If
     * so add it as a sink since it must be retained. Don't need to check the position of the
     * parameter, since each of the checked methods has at most one object parameter.
     */
    public void checkFormalParameterBuilder(TypeFlowBuilder<?> paramBuilder) {
        AnalysisMethod method = (AnalysisMethod) ((ParameterNode) paramBuilder.getSource()).graph().method();
        String methodFormat = method.getQualifiedName();
        for (String specialMethodFormat : waitNotifyHashCodeMethods) {
            if (methodFormat.equals(specialMethodFormat)) {
                dataFlowSinkBuilders.add(paramBuilder);
            }
        }
    }

    /**
     * Materialize all reachable flows starting from the sinks and working backwards following the
     * dependency chains. Unreachable flows will be implicitly pruned.
     *
     * @return the list of type flows that need initialization
     */
    public List<TypeFlow<?>> build() {
        /* List of type flows that need to be initialized after the graph is materialized. */
        List<TypeFlow<?>> postInitFlows = new ArrayList<>();

        /* Work queue used by the iterative graph traversal. */
        HashSet<TypeFlowBuilder<?>> processed = new HashSet<>();
        ArrayDeque<TypeFlowBuilder<?>> workQueue = new ArrayDeque<>();

        /* Keep track of already materialized flows. */
        for (TypeFlowBuilder<?> sinkBuilder : dataFlowSinkBuilders) {
            if (processed.contains(sinkBuilder)) {
                /*
                 * This sink has already been processed; probably reached from another sink through
                 * the dependency chain. This is possible since the sink registration is
                 * conservative, i.e., it can register a builder as a sink even if it can be reached
                 * from another sink through the dependency chain.
                 */
                continue;
            }

            workQueue.addLast(sinkBuilder);
            while (!workQueue.isEmpty()) {
                TypeFlowBuilder<?> builder = workQueue.removeFirst();
                if (!processed.add(builder)) {
                    /* Skip if this builder was processed already. */
                    continue;
                }
                /* Materialize the builder. */
                TypeFlow<?> flow = builder.get();

                var predicate = builder.getPredicate();
                if (predicate != null) {
                    assert bb.usePredicates() : "Predicates should only be used with -H:+UsePredicates.";
                    if (predicate instanceof TypeFlowBuilder<?> singlePredicate) {
                        singlePredicate.get().addPredicated(bb, flow);
                        if (!processed.contains(singlePredicate)) {
                            workQueue.addLast(singlePredicate);
                        }
                    } else {
                        @SuppressWarnings("unchecked")
                        var predicateList = ((List<TypeFlowBuilder<?>>) predicate);
                        for (TypeFlowBuilder<?> p : predicateList) {
                            p.get().addPredicated(bb, flow);
                            if (!processed.contains(p)) {
                                workQueue.addLast(p);
                            }
                        }
                    }
                } else {
                    assert !bb.usePredicates() || flow instanceof AlwaysEnabledPredicateFlow : "Flow " + flow + " does not have a predicate.";
                    /*
                     * If there is no predicate, enable the flow immediately. However, we only want
                     * to propagate updates in the context-insensitive analysis. In the
                     * context-sensitive analysis, the original graph is used for cloning only, so
                     * we do not want to send any updates through it, hence we pass null for bb.
                     */
                    flow.enableFlow(bb.analysisPolicy().isContextSensitiveAnalysis() ? null : bb);
                }
                if (bb.isBaseLayerAnalysisEnabled()) {
                    /*
                     * GR-58387 - Currently, we force enable all the flows in the base layer, which
                     * is a workaround that should eventually be removed.
                     */
                    flow.enableFlow(bb.analysisPolicy().isContextSensitiveAnalysis() ? null : bb);
                }

                if (flow.needsInitialization()) {
                    postInitFlows.add(flow);
                }

                /* The retain reason is the sink from which it was reached. */
                PointsToStats.registerTypeFlowRetainReason(bb, flow, (sinkBuilder.isBuildingAnActualParameter() ? "ActualParam=" : "") + ClassUtil.getUnqualifiedName(sinkBuilder.getFlowClass()));

                /*
                 * Iterate over use and observer dependencies. Add them to the workQueue only if
                 * they have not been already processed.
                 */
                for (TypeFlowBuilder<?> useDependency : builder.getUseDependencies()) {
                    if (!processed.contains(useDependency)) {
                        workQueue.addLast(useDependency);
                    }
                    /* Convert the use dependency into a use data flow. */
                    bb.analysisPolicy().addOriginalUse(bb, useDependency.get(), flow);
                }
                for (TypeFlowBuilder<?> observerDependency : builder.getObserverDependencies()) {
                    if (!processed.contains(observerDependency)) {
                        workQueue.addLast(observerDependency);
                    }
                    /* Convert the observer dependency into an observer data flow. */
                    bb.analysisPolicy().addOriginalObserver(bb, observerDependency.get(), flow);
                }
            }
        }
        return postInitFlows;
    }
}
