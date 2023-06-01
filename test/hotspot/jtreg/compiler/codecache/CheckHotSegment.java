/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
 * @test CheckHotSegment
 * @run testng/othervm CheckHotSegment
 * @requires vm.flavor == "server"
 * @summary Test of Hot CodeCache segment
 */

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.util.Iterator;
import java.util.ArrayList;
import jdk.test.lib.dcmd.JMXExecutor;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import org.testng.annotations.Test;

// Test runs a CheckHotSegment.main() in a separate VM. A separate VM runs the
// Compiler.codecache and Compiler.codelist jcmd commands. The output of these
// commands is parsed in a parent process: raw nmethod addresses are compared
// to CodeHeap segment ranges to find a segment where the method was installed.
// The child VM is started with different options to check where its methods are located.
// The goal is to test CompilerDirectives and CompilerControl: all given nmethods
// are in extrahot, none are is in any other segment.
public class CheckHotSegment {

    class CodeHeap {
        final static String SEGMENT_HOT = "extra-hot";
        final static String SEGMENT_NONPROF = "non-profiled nmethods";
        final static String SEGMENT_PROFILED = "profiled nmethods";
        final static String SEGMENT_NONNMETHOD = "non-nmethods";

        long hotSegmentStart = 0;
        long hotSegmentEnd = 0;
        long c1SegmentStart = 0;
        long c1SegmentEnd = 0;
        long c2SegmentStart = 0;
        long c2SegmentEnd = 0;
        long nonmethodSegmentStart = 0;
        long nonmethodSegmentEnd = 0;

        ArrayList<String> hotSegmentMethods = new ArrayList<String>();
        ArrayList<String> c1SegmentMethods = new ArrayList<String>();
        ArrayList<String> c2SegmentMethods = new ArrayList<String>();
        ArrayList<String> nonnmethodSegmentMethods = new ArrayList<String>();

        void addSegment(String name, long start, long end) {
            if (SEGMENT_HOT.equals(name)) {
                hotSegmentStart = start;
                hotSegmentEnd = end;
            } else
            if (SEGMENT_NONPROF.equals(name)) {
                c1SegmentStart = start;
                c1SegmentEnd = end;
            } else
            if (SEGMENT_PROFILED.equals(name)) {
                c2SegmentStart = start;
                c2SegmentEnd = end;
            } else
            if (SEGMENT_NONNMETHOD.equals(name)) {
                nonmethodSegmentStart = start;
                nonmethodSegmentEnd = end;
            } else
            // -XX:-SegmentedCodeCache case: DCMD.Codecache reports no segment name, just "CodeCache: size=.."
            if ("CodeCache".equals(name)) {
                // let us put all the CodeCache stuff into c1 Segment
                c1SegmentStart = start;
                c1SegmentEnd = end;
            } else
            // -XX:-SegmentedCodeCache -XX:+ExtraHotCodeCache case: DCMD reports "ExtraHotCache" and "CodeCache" segments
            if ("ExtraHotCache".equals(name)) {
                hotSegmentStart = start;
                hotSegmentEnd = end;
            } else {
                System.out.println("UNEXPECTED SEGMENT: >" + name + "<");
            }
        }
        boolean addMethod(long addr, String name) {
            if (addr >= hotSegmentStart && addr <= hotSegmentEnd) {
                hotSegmentMethods.add(name);
            } else if (addr >= c2SegmentStart && addr <= c2SegmentEnd) {
                c1SegmentMethods.add(name);
            } else if (addr >= c1SegmentStart && addr <= c1SegmentEnd) {
                c2SegmentMethods.add(name);
            } else if (addr >= nonmethodSegmentStart && addr <= nonmethodSegmentEnd) {
                nonnmethodSegmentMethods.add(name);
            } else {
                return false;
            }
            return true;
        }
        String getSegmentName(long addr) {
            if (addr >= hotSegmentStart && addr <= hotSegmentEnd) return SEGMENT_HOT;
            if (addr >= c2SegmentStart && addr <= c2SegmentEnd) return SEGMENT_NONPROF;
            if (addr >= c1SegmentStart && addr <= c1SegmentEnd) return SEGMENT_PROFILED;
            if (addr >= nonmethodSegmentStart && addr <= nonmethodSegmentEnd) return SEGMENT_NONNMETHOD;
            return "UNKNOWN";
        }
    }

    static String prepareCmdFile(String body) {
        File tmpDir = new File("tmp.test");
        tmpDir.mkdirs();
        File file = new File(tmpDir, "compiler.cmd");
        Writer out = null;
        try {
            out = new FileWriter(file);
            out.write(body);
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception ex) {}
        }
        return file.getAbsolutePath();
    }

    public CodeHeap runVM(String... vmOption) {

        CodeHeap codeHeap = new CodeHeap();

        ArrayList<String> command = new ArrayList<String>();
        command.add("-Xbootclasspath/a:.");
        command.add("-XX:+UnlockDiagnosticVMOptions");
        command.add("-Xcomp");
        command.add("-Xbatch");
        command.add("-XX:+ExtraHotCodeCache");
        command.addAll(java.util.Arrays.asList(vmOption));
        command.add("CheckHotSegment");
        ProcessBuilder pb = ProcessTools.createJavaProcessBuilder(command);
        try {
            OutputAnalyzer output = new OutputAnalyzer(pb.start());
            Iterator<String> lines = output.asLines().iterator();
            while (lines.hasNext()) {
                String line = lines.next();

                // parsing subsequent lines:
                //   CodeHeap 'non-profiled nmethods': size=120028Kb used=373Kb max_used=373Kb free=119654Kb
                //   bounds [0x00007f764cac9000, 0x00007f764cd39000, 0x00007f7654000000]
                if (line.matches("^CodeHeap '.*") || line.contains("Cache: size=")) {
                    String[] parts = line.split("'");
                    String segmentName = (parts.length > 1) ? parts[1] : line.substring(0, line.indexOf(": "));
                    String bounds = lines.next();
                    System.out.println("bounds: " + bounds);
                    bounds = bounds.substring(bounds.indexOf('[') + 1);
                    bounds = bounds.substring(0, bounds.indexOf(']'));
                    String[] addr = bounds.split(", ");
                    String segmentStart = addr[0];
                    String segmentEnd = addr[2];
                    long start = Long.parseLong(segmentStart.replace("0x", ""), 16);
                    long end   = Long.parseLong(segmentEnd.replace("0x", ""), 16);
                    codeHeap.addSegment(segmentName, start, end);
                }

                String nmethodPattern = ".*[0-9]+ +[0-9]+ +[0-9]+.*[0x.*].*";
                // parsing nmethod info:
                //   11 1 0 java.lang.Enum.ordinal()I [0x00007f91e0ac9610, 0x00007f91e0ac97a0 - 0x00007f91e0ac9870]
                //   12 0 0 java.lang.Object.hashCode()I [0x00007f91e0ac9910, 0x00007f91e0ac9aa0 - 0x00007f91e0ac9c88]
                if (line.matches(nmethodPattern)) {
                    String addrStr = line.substring(line.indexOf("[0x") + 3);
                    addrStr = addrStr.substring(0, addrStr.indexOf(","));
                    long addr = Long.parseLong(addrStr, 16);
                    String name = line.split(" ")[3];
                    name = name.substring(0, name.indexOf("("));
                    String fields[] = line.split("\\s+");
                    int compilationLevel = Integer.valueOf(fields[1]);
                    if (compilationLevel == 4) { // c2
                        boolean isHot = (addr >= codeHeap.hotSegmentStart) && (addr < codeHeap.hotSegmentEnd);
                        boolean ok = codeHeap.addMethod(addr, name);
                        if (!ok) {
                            System.out.println(output);
                            throw new AssertionError("nmethod does not belong to any CodeHeap segment: " + name);
                        }
                    }
                }
            }
        } catch (Exception ex) { ex.printStackTrace(); }

        return codeHeap;
    }

    // main() runs in a separate VM: here we execute jcmd commands and exit
    public static void main(String args[]) {
        JMXExecutor exec = new JMXExecutor();
        exec.execute("Compiler.codecache");
        exec.execute("Compiler.codelist");
    }

    void checkJavaMethodsBelongsToHotSegmentOnly(CodeHeap codeHeap) {
        if (codeHeap.hotSegmentMethods.isEmpty()) {
            reportError("hot segment is empty!");
        }
        codeHeap.hotSegmentMethods.forEach((name) -> {
            if (!name.startsWith("java")) { reportError("hot segment contains wrong method: " + name); }
        });
        codeHeap.c1SegmentMethods.forEach((name) -> {
            if (name.startsWith("java")) { reportError("c1 segment contains wrong name: " + name); }
        });
        codeHeap.c2SegmentMethods.forEach((name) -> {
            if (name.startsWith("java")) { reportError("c2 segment contains wrong name: " + name); }
        });
    }

    @Test
    public void testCommandFile() {
        String cmdFile = prepareCmdFile("option java*::* ExtraHot");
        CodeHeap codeHeap = runVM("-XX:+SegmentedCodeCache", "-XX:CompileCommandFile=" + cmdFile);
        checkJavaMethodsBelongsToHotSegmentOnly(codeHeap);
    }

    @Test
    public void testDirectivesFile() {
        String dirctFile = prepareCmdFile("[ { match: [ \"java*::*\" ], c2: { ExtraHot: true } } ]");
        CodeHeap codeHeap = runVM("-XX:+SegmentedCodeCache", "-XX:CompilerDirectivesFile=" + dirctFile);
        checkJavaMethodsBelongsToHotSegmentOnly(codeHeap);
    }

    @Test
    public void testSegmentedNonTiered() {
        String dirctFile = prepareCmdFile("[ { match: [ \"java*::*\" ], c2: { ExtraHot: true } } ]");
        CodeHeap codeHeap = runVM("-XX:CompilerDirectivesFile=" + dirctFile, "-XX:+SegmentedCodeCache", "-XX:-TieredCompilation");
        checkJavaMethodsBelongsToHotSegmentOnly(codeHeap);
    }

    @Test
    public void testNonsegmented() {
        String dirctFile = prepareCmdFile("[ { match: [ \"java*::*\" ], c2: { ExtraHot: true } } ]");
        CodeHeap codeHeap = runVM("-XX:CompilerDirectivesFile=" + dirctFile, "-XX:-SegmentedCodeCache");
        checkJavaMethodsBelongsToHotSegmentOnly(codeHeap);
    }

    @Test
    public void testEmptyHotSegment() {
        CodeHeap codeHeap = runVM("-XX:ExtraHotCodeHeapSize=10K");
        if (!codeHeap.hotSegmentMethods.isEmpty()) {
            reportError("hot segment must be empty!");
        }
    }

    @Test
    public void testSmallHotSegment() {
        String cmdFile = prepareCmdFile("option java*::* ExtraHot");
        CodeHeap codeHeap = runVM("-XX:+SegmentedCodeCache", "-XX:CompileCommandFile=" + cmdFile, "-XX:ExtraHotCodeHeapSize=10K");
        if (codeHeap.hotSegmentMethods.isEmpty()) {
            reportError("hot segment should not be empty");
        }
        // Hot segment is full. Fallback to NonProfiled heap.
        for (String name : codeHeap.c2SegmentMethods) {
            if (name.startsWith("java")) {
                return; // OK
            }
        }
        reportError("remaining methods must go to non-profiled segment");
    }

    void reportError(String msg) {
        System.out.println(msg);
        throw new AssertionError(msg);
    }
}
