/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.replacements.SnippetTemplate.DEFAULT_REPLACER;

import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.api.replacements.Snippet.ConstantParameter;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.replacements.SnippetTemplate.AbstractTemplates;
import jdk.graal.compiler.replacements.SnippetTemplate.Arguments;
import jdk.graal.compiler.replacements.SnippetTemplate.SnippetInfo;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.replacements.nodes.CStringConstant;
import jdk.graal.compiler.replacements.nodes.LogNode;
import jdk.graal.compiler.word.Word;

/**
 * Collection of snippets to lower {@link LogNode} with different input edge constellations.
 */
public class LogSnippets implements Snippets {

    @Snippet
    public static void print(@ConstantParameter Word message) {
        Log.print(message);
    }

    @Snippet
    public static void printf1(@ConstantParameter Word message, long l1) {
        Log.printf(message, l1);
    }

    @Snippet
    public static void printf2(@ConstantParameter Word message, long l1, long l2) {
        Log.printf(message, l1, l2);
    }

    @Snippet
    public static void printf3(@ConstantParameter Word message, long l1, long l2, long l3) {
        Log.printf(message, l1, l2, l3);
    }

    public static class Templates extends AbstractTemplates {

        private final SnippetInfo print;
        private final SnippetInfo printf1;
        private final SnippetInfo printf2;
        private final SnippetInfo printf3;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, HotSpotProviders providers) {
            super(options, providers);

            this.print = snippet(providers, LogSnippets.class, "print");
            this.printf1 = snippet(providers, LogSnippets.class, "printf1");
            this.printf2 = snippet(providers, LogSnippets.class, "printf2");
            this.printf3 = snippet(providers, LogSnippets.class, "printf3");
        }

        public void lower(LogNode logNode, LoweringTool tool) {
            StructuredGraph graph = logNode.graph();
            SnippetInfo info = print;
            if (logNode.getL3() != null) {
                info = printf3;
            } else if (logNode.getL2() != null) {
                info = printf2;
            } else if (logNode.getL1() != null) {
                info = printf1;
            }
            Arguments args = new Arguments(info, graph.getGuardsStage(), tool.getLoweringStage());
            args.add("message", graph.unique(new ConstantNode(new CStringConstant(logNode.message()), StampFactory.pointer())));
            if (logNode.getL1() != null) {
                args.add("l1", logNode.getL1());
            }
            if (logNode.getL2() != null) {
                args.add("l2", logNode.getL2());
            }
            if (logNode.getL3() != null) {
                args.add("l3", logNode.getL3());
            }
            template(tool, logNode, args).instantiate(tool.getMetaAccess(), logNode, DEFAULT_REPLACER, args);
        }
    }
}
