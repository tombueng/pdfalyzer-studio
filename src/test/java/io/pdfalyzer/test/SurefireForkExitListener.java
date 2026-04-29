package io.pdfalyzer.test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

/**
 * Forces the surefire fork JVM to exit cleanly after the JUnit test plan
 * finishes, even when @SpringBootTest leaves embedded-Tomcat non-daemon
 * threads behind (container-0, Catalina-utility-N, scheduling-N).
 *
 * Background: Spring registers a JVM shutdown hook to close cached
 * contexts (which would stop Tomcat), but the hook only fires when the
 * JVM begins to exit — and Tomcat's container-0 thread is deliberately
 * non-daemon, so the JVM never starts exiting on its own. Surefire then
 * trips its forkedProcessExitTimeoutInSeconds and reports
 * "There was a timeout in the fork" despite zero test failures.
 *
 * Strategy: schedule a Runtime.halt(0) on a daemon thread so it runs
 * AFTER surefire has finished its goodbye IPC protocol (otherwise
 * surefire reports "The forked VM terminated without properly saying
 * goodbye" — a crash). The daemon thread also lets the JVM exit
 * normally if (unexpectedly) all non-daemon threads do release on
 * their own — in that case halt never fires.
 *
 * Registered via:
 *   src/test/resources/META-INF/services/org.junit.platform.launcher.TestExecutionListener
 */
public class SurefireForkExitListener implements TestExecutionListener {

    private static final long HALT_DELAY_MS = 3_000L;

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        List<Thread> nonDaemon = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t == Thread.currentThread() || t.isDaemon()) {
                continue;
            }
            ThreadGroup g = t.getThreadGroup();
            if (g != null && "system".equals(g.getName())) {
                continue;
            }
            nonDaemon.add(t);
        }

        if (nonDaemon.isEmpty()) {
            return;
        }

        nonDaemon.sort(Comparator.comparing(Thread::getName));
        StringBuilder sb = new StringBuilder("Non-daemon threads still alive at test plan end (will halt JVM in ")
                .append(HALT_DELAY_MS).append("ms to avoid surefire fork timeout):");
        for (Thread t : nonDaemon) {
            sb.append("\n  - ").append(t.getName()).append(" [").append(t.getState()).append(']');
        }
        System.err.println(sb);
        System.err.flush();

        Thread halter = new Thread(() -> {
            try {
                Thread.sleep(HALT_DELAY_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Runtime.getRuntime().halt(0);
        }, "surefire-fork-exit-halter");
        halter.setDaemon(true);
        halter.start();
    }
}
