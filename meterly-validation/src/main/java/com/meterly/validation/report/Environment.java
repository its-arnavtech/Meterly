package com.meterly.validation.report;

import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

/**
 * Captures the machine the harness ran on, so the Markdown report's numbers (especially STOMP
 * latency) are read as "measured here", never as an unconditional guarantee (contract §9 item 5).
 */
public record Environment(int availableProcessors, long totalMemoryBytes, String osName, String osVersion,
                           String osArch, String javaVersion, String dockerInfo) {

    public static Environment capture() {
        long totalMemory = -1;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            totalMemory = sunBean.getTotalMemorySize();
        }
        return new Environment(
                Runtime.getRuntime().availableProcessors(),
                totalMemory,
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.version", "unknown"),
                System.getProperty("os.arch", "unknown"),
                Runtime.version().toString(),
                dockerServerInfo());
    }

    private static String dockerServerInfo() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format",
                    "{{.Server.Version}} (API {{.Server.APIVersion}}, {{.Server.Os}}/{{.Server.Arch}})")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "unavailable (timed out)";
            }
            String output = new String(process.getInputStream().readAllBytes()).trim();
            return process.exitValue() == 0 && !output.isBlank() ? output : "unavailable";
        } catch (Exception e) {
            return "unavailable (" + e.getClass().getSimpleName() + ")";
        }
    }

    public String formatMemory() {
        return totalMemoryBytes <= 0 ? "unknown" : (totalMemoryBytes / (1024 * 1024 * 1024)) + " GiB";
    }
}
