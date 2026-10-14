package com.thescratchers.searchengine.benchmark;

import com.thescratchers.searchengine.datalake.Downloader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Helper: makes sure the 20 contract books of shared/books.txt are present in
 * java/data/datalake (downloading only the missing ones with the Downloader),
 * which BenchmarkRunner, DatalakeBenchmark and MetadataBenchmark all require.
 *
 * Run from the java/ directory:
 *   java -cp target/benchmarks.jar com.thescratchers.searchengine.benchmark.DownloadContractBooks
 */
public class DownloadContractBooks {

    public static void main(String[] args) throws Exception {
        List<Integer> ids = ContractDataset.loadBookIds();
        Map<Integer, java.nio.file.Path> bodies = ContractDataset.findFiles(".body.txt");
        Map<Integer, java.nio.file.Path> headers = ContractDataset.findFiles(".header.txt");

        Downloader downloader = new Downloader();
        List<Integer> failed = new ArrayList<>();
        int present = 0;
        for (int id : ids) {
            if (bodies.containsKey(id) && headers.containsKey(id)) {
                present++;
                continue;
            }
            if (!downloader.downloadBook(id)) failed.add(id);
        }

        System.out.println("Contract books already present: " + present + "/" + ids.size());
        if (failed.isEmpty()) {
            System.out.println("All " + ids.size() + " contract books are in " + ContractDataset.DATALAKE_ROOT);
        } else {
            System.err.println("Could not download: " + failed);
            System.exit(1);
        }
    }
}
