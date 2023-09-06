/*
 * Copyright (c) 2023, Alibaba Group Holding Limited. All Rights Reserved.
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
 * @bug 8312065
 * @summary Socket.connect does not timeout as expected when profiling (i.e. keep receiving signal)
 * @requires (os.family != "windows")
 * @compile NativeThread.java
 * @run main/othervm/native/timeout=120 -Djdk.net.usePlainSocketImpl B8312065
 */

import sun.misc.Signal;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class B8312065 {
    public static void main(String[] args) throws Exception {
        System.loadLibrary("NativeThread");

        // Setup SIGPIPE handler
        Signal.handle(new Signal("PIPE"), System.out::println);

        long osThreadId = NativeThread.getID();

        Thread t = new Thread(() -> {
            try {
                // Send SIGPIPE to the thread every second
                for (int i = 0; i < 10; i++) {
                    if (NativeThread.signal(osThreadId, NativeThread.SIGPIPE) != 0) {
                        System.out.println("Failed to send signal");
                        System.exit(1);
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                System.out.println("Test FAILED");
                System.exit(1);
            }
        });
        t.setDaemon(true);
        t.start();

        try {
            Socket socket = new Socket();
            // There is no good way to mock SocketTimeoutException, just assume 192.168.255.255 is not in use.
            socket.connect(new InetSocketAddress("192.168.255.255", 8080), 2000);
        } catch (SocketTimeoutException e) {
            System.out.println("Test passed");
        }
    }
}
