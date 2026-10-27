package com.thescratchers.searchengine.benchmarks.load;

import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point of the load client used by the integration benchmarks. It runs every
 * concurrency level against the Search replicas and writes the results directory described in
 * {@link ResultWriter}. Progress and the summary table are printed to standard output.
 */
public final class LoadClient {

    private LoadClient() {
    }

    public static void main(String[] args) throws Exception {
        LoadOptions options;
        try {
            options = LoadOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage() + "\n\n" + LoadOptions.USAGE);
            System.exit(2);
            return;
        }

        ResultWriter writer = new ResultWriter(options.outputDir());
        writer.writeCommand(args, options);
        writer.writeHost();
        System.out.println("Results in " + options.outputDir().toAbsolutePath());
        System.out.println("clients  requests  errors  throughput_rps  p50_ms  p95_ms  p99_ms");

        LoadRunner runner = new LoadRunner(options);
        int errors = 0;
        for (int clients : options.clientLevels()) {
            List<Sample> samples = runner.run(clients);
            LevelSummary summary = LevelSummary.of(clients, samples, options.duration().toMillis() / 1000.0);
            writer.append(samples, summary);
            errors += summary.errors();
            System.out.println(String.format(Locale.ROOT, "%7d  %8d  %6d  %14.1f  %6.2f  %6.2f  %6.2f",
                    clients, summary.requests(), summary.errors(), summary.throughputRps(),
                    summary.p50Ms(), summary.p95Ms(), summary.p99Ms()));
        }
        if (errors > 0) {
            System.out.println(errors + " requests failed; see the status column of raw.csv");
        }
    }
}
