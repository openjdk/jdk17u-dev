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
 * @library /test/lib
 * @requires os.family == "linux"
 * @run main/othervm/timeout=120 -Djdk.net.usePlainSocketImpl B8312065
 */

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import sun.misc.Signal;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class B8312065 {
    private static volatile boolean success = false;
    private static Recording recording = null;
    private static volatile boolean isSendSignal = true;
    private static volatile boolean signalReceived = false;
    private static final boolean debug = true;

    public static void main(String[] args) throws Exception {
        // If the thread executing java.net.PlainSocketImpl.socketConnect receives continuous signals,
        // the thread will be blocked for a long time (about 2 minutes).
        // To test in the sub-thread, the main thread can detect the execution time of the sub-thread to avoid the
        // test duration being too long.
        Thread t = new Thread(() -> {
            try {
                task();
            } catch (Exception e) {
                error(e.getMessage(), e);
            }
        });
        t.setDaemon(true);
        t.start();

        int timeoutSeconds = 10;
        t.join(timeoutSeconds * 1000);
        if (t.isAlive()) {
            throw new RuntimeException("Test Failed: " + timeoutSeconds +
                    " seconds have passed and it has not timed out");
        }

        if (!success) {
            throw new RuntimeException("Test Failed");
        }
    }

    private static void task() throws IOException, ParseException, InterruptedException {
        Thread.currentThread().setName("B8312065");

        // Find OS thread ID of the current thread
        long osThreadId = getOSThreadId();
        if (osThreadId == 0) {
            throw new RuntimeException("Failed to get operating system thread id");
        }

        Thread t = startSendingSignalToThread(osThreadId);

        test();

        isSendSignal = false;
        t.join();
    }

    private static Thread startSendingSignalToThread(long osThreadId) throws InterruptedException {
        // Setup SIGPROF handler
        Signal.handle(new Signal("PROF"), (signal) -> {
            signalReceived = true;
        });

        CountDownLatch latch = new CountDownLatch(1);

        // Send SIGPROF to the thread every second
        Thread t = new Thread(() -> {
            while (isSendSignal) {
                try {
                    Runtime.getRuntime().exec("kill -SIGPROF " + osThreadId).waitFor();
                    Thread.sleep(1000);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }
        });
        t.setDaemon(true);
        t.start();

        latch.await();
        if (!signalReceived) {
            throw new RuntimeException("failed to send signal");
        }
        return t;
    }

    static void test() {
        Socket socket = null;
        long startMillis = millisTime();
        int timeoutMillis = 2000;
        try {
            socket = new Socket();
            connectWithTimeout(socket, timeoutMillis);
            error("connected successfully!");
        } catch (SocketTimeoutException socketTimeout) {
            long duration = millisTime() - startMillis;
            long min = timeoutMillis - 100;
            long max = timeoutMillis + 2000;
            if (duration < min) {
                error("Duration " + duration + "ms, expected >= " + min + "ms");
            } else if (duration > max) {
                error("Duration " + duration + "ms, expected <= " + max + "ms");
            } else {
                debug("Passed: Received: " + socketTimeout + ", duration " + duration + " millis");
                passed();
            }
        } catch (Exception exception) {
            error("Connect timeout test failed", exception);
        } finally {
            close(socket);
        }
    }

    static void connectWithTimeout(Socket socket, int timeout) throws IOException {
        // There is no good way to mock SocketTimeoutException, just assume 192.168.255.255 is not in use.
        socket.connect(new InetSocketAddress("192.168.255.255", 8080), timeout);
    }

    /**
     * Returns the current time in milliseconds.
     */
    private static long millisTime() {
        long now = System.nanoTime();
        return TimeUnit.MILLISECONDS.convert(now, TimeUnit.NANOSECONDS);
    }

    /**
     * The JFR event records the OS thread ID, which is a reliable way.
     * Another way is use /proc/thread-self, but unfortunately, it's not supported until linux 3.17
     * @return Operating System Thread id
     * @throws IOException
     * @throws ParseException
     */
    private static long getOSThreadId() throws IOException, ParseException {
        startJFR();

        sleep(); // trigger jdk.ThreadSleep Event

        Path jfrPath = null;
        try {
            jfrPath = Files.createTempFile("B8312065", ".jfr");
            recording.dump(jfrPath);
            RecordingFile file = new RecordingFile(jfrPath);
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if ("jdk.ThreadSleep".equals(event.getEventType().getName())) {
                    RecordedThread thread = event.getThread();
                    if (thread.getJavaName().equals("B8312065")) {
                        return thread.getOSThreadId();
                    }
                }
            }
        } finally {
            if (jfrPath != null) {
                jfrPath.toFile().delete();
            }
            recording.stop();
        }

        return 0L;
    }

    private static void startJFR() throws IOException, ParseException {
        Configuration recordingConfig = Configuration.getConfiguration("default");
        recording = new Recording(recordingConfig);
        recording.start();
    }

    private static void sleep() {
        try {
            Thread.sleep(1000);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void debug(String message) {
        if (debug) {
            System.out.println(message);
        }
    }

    static void unexpected(Exception e ) {
        System.out.println("Unexpected Exception: " + e);
    }

    static void close(Closeable closeable) {
        if (closeable != null) try { closeable.close(); } catch (IOException e) {unexpected(e);}
    }

    static void error(String message) {
        System.out.println(message);
    }

    static void error(String message, Exception e) {
        System.out.println(message);
        e.printStackTrace();
    }

    static void passed() {
        success = true;
    }
}