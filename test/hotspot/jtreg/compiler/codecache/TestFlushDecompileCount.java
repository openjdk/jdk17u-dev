/*
 * Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
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

/*
 * @test
 * @bug 8397140
 * @summary Sweeper flushes and tenured traps should not count toward PerMethodRecompilationCutoff
 * @requires vm.compiler2.enabled & vm.opt.DeoptimizeALot != true
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   compiler.codecache.TestFlushDecompileCount
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:PerMethodRecompilationCutoff=3
 *                   compiler.codecache.TestFlushDecompileCount
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *                   -XX:PerMethodRecompilationCutoff=3 -XX:+StressCodeAging -XX:HotMethodDetectionLimit=10
 *                   compiler.codecache.TestFlushDecompileCount
 */

package compiler.codecache;

import java.lang.reflect.Method;
import jdk.test.whitebox.WhiteBox;

public class TestFlushDecompileCount {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final int COMP_LEVEL_FULL_OPTIMIZATION = 4;

    static int m(int x) {
        return x + 1;
    }

    public static void main(String[] args) throws Exception {
        Method method = TestFlushDecompileCount.class.getDeclaredMethod("m", int.class);

        for (int round = 0; round < 6; round++) {
            compile(method);
            // With -XX:+StressCodeAging, this hits a tenured trap.
            for (int i = 0; i < 20; i++) {
                m(i);
            }
            compile(method);
            flush(method);
        }
    }

    static void compile(Method method) {
        if (WB.getMethodCompilationLevel(method) != COMP_LEVEL_FULL_OPTIMIZATION) {
            WB.enqueueMethodForCompilation(method, COMP_LEVEL_FULL_OPTIMIZATION);
            while (WB.isMethodQueuedForCompilation(method)) {
                Thread.onSpinWait();
            }
        }
        if (WB.getMethodCompilationLevel(method) != COMP_LEVEL_FULL_OPTIMIZATION) {
            throw new RuntimeException("m() can no longer be compiled by C2");
        }
    }

    static void flush(Method method) {
        for (int i = 0; WB.isMethodCompiled(method); i++) {
            if (i == 10_000) {
                throw new RuntimeException("m() was not flushed by the sweeper");
            }
            WB.forceNMethodSweep();
        }
    }
}
