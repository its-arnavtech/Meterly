package com.meterly.validation.report;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Accumulates {@link CheckResult}s for a run, prints a live summary as they land, and renders the
 * Markdown report contract §9 requires (environment + a summary table + per-check detail).
 */
public final class ValidationReport {

    private final List<CheckResult> results = new ArrayList<>();
    private final Environment environment = Environment.capture();
    private final Instant startedAt = Instant.now();
    private final PrintStream out;

    public ValidationReport(PrintStream out) {
        this.out = out;
    }

    public void record(CheckResult result) {
        results.add(result);
        out.printf("[%s] %s - %s%n", result.passed() ? "PASS" : "FAIL", result.name(), result.message());
    }

    public boolean allPassed() {
        return !results.isEmpty() && results.stream().allMatch(CheckResult::passed);
    }

    public void printSummary() {
        out.println();
        out.println("==================== Meterly validation summary ====================");
        for (CheckResult r : results) {
            out.printf("  %-6s %s%n", r.passed() ? "PASS" : "FAIL", r.name());
        }
        long passed = results.stream().filter(CheckResult::passed).count();
        out.printf("%d/%d checks passed%n", passed, results.size());
        out.println("======================================================================");
    }

    public void writeMarkdown(Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, render());
        out.println("Report written to " + path.toAbsolutePath());
    }

    private String render() {
        StringBuilder md = new StringBuilder();
        Duration elapsed = Duration.between(startedAt, Instant.now());
        md.append("# Meterly validation report\n\n");
        md.append("Generated: ").append(DateTimeFormatter.ISO_INSTANT.format(Instant.now())).append("\n\n");
        md.append("Run started: ").append(DateTimeFormatter.ISO_INSTANT.format(startedAt))
                .append(", duration ").append(elapsed.toSeconds()).append("s\n\n");

        md.append("## Environment\n\n");
        md.append("| Field | Value |\n|---|---|\n");
        md.append("| CPU cores | ").append(environment.availableProcessors()).append(" |\n");
        md.append("| RAM | ").append(environment.formatMemory()).append(" |\n");
        md.append("| OS | ").append(environment.osName()).append(' ').append(environment.osVersion())
                .append(" (").append(environment.osArch()).append(") |\n");
        md.append("| Java | ").append(environment.javaVersion()).append(" |\n");
        md.append("| Docker | ").append(environment.dockerInfo()).append(" |\n\n");

        md.append("## Summary\n\n");
        md.append("| Result | Check | Message |\n|---|---|---|\n");
        for (CheckResult r : results) {
            md.append("| ").append(r.passed() ? "PASS" : "FAIL").append(" | ").append(r.name())
                    .append(" | ").append(escape(r.message())).append(" |\n");
        }
        long passed = results.stream().filter(CheckResult::passed).count();
        md.append("\n**").append(passed).append('/').append(results.size()).append(" checks passed**\n\n");

        md.append("## Detail\n\n");
        for (CheckResult r : results) {
            md.append("### ").append(r.passed() ? "PASS" : "FAIL").append(" - ").append(r.name()).append("\n\n");
            md.append(r.message()).append("\n\n");
            if (!r.metrics().isEmpty()) {
                md.append("| Metric | Value |\n|---|---|\n");
                for (Map.Entry<String, String> e : r.metrics().entrySet()) {
                    md.append("| ").append(e.getKey()).append(" | ").append(escape(e.getValue())).append(" |\n");
                }
                md.append('\n');
            }
        }
        md.append("---\n\nThis report reflects one run on the environment above; latency figures are a ")
                .append("distribution observed here, not an unconditional guarantee (docs/api-contract.md §9).\n");
        return md.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
