/*
<<<<<<<< HEAD:test/hotspot/jtreg/compiler/lib/ir_framework/driver/irmatching/parser/Block.java
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
========
 * Copyright (c) 2022, Red Hat, Inc. All rights reserved.
>>>>>>>> master:test/hotspot/jtreg/compiler/allocation/TestAllocArrayAfterAllocNoUse.java
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

<<<<<<<< HEAD:test/hotspot/jtreg/compiler/lib/ir_framework/driver/irmatching/parser/Block.java
package compiler.lib.ir_framework.driver.irmatching.parser;

import java.util.List;

/**
 * Class representing a PrintIdeal or PrintOptoAssembly output block read from the hotspot_pid* file.
 */
record Block(String output, List<String> testClassCompilations) {
    public String getOutput() {
        return output;
    }

    public boolean containsTestClassCompilations() {
        return !testClassCompilations.isEmpty();
    }

    public List<String> getTestClassCompilations() {
        return testClassCompilations;
========
/**
 * @test
 * @bug 8279125
 * @summary fatal error: no reachable node should have no use
 * @requires vm.flavor == "server"
 *
 * @run main/othervm -XX:-BackgroundCompilation -XX:-DoEscapeAnalysis TestAllocArrayAfterAllocNoUse
 *
 */

public class TestAllocArrayAfterAllocNoUse {
    private static Object field;

    public static void main(String[] args) {
        for (int i = 0; i < 20_000; i++) {
            test();
        }
    }

    private static void test() {
        try {
            final TestAllocArrayAfterAllocNoUse o = new TestAllocArrayAfterAllocNoUse();
        } catch (Exception e) {
            final int[] array = new int[100];
            field = array;
        }

>>>>>>>> master:test/hotspot/jtreg/compiler/allocation/TestAllocArrayAfterAllocNoUse.java
    }
}
