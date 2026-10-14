package com.thescratchers.searchengine.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SyntheticDataGenerator {

    public static final int SYNTHETIC_ID_OFFSET = 1_000_000;
    public static final int[] MANDATORY_SCALES  = {1_000, 10_000, 100_000};

    private SyntheticDataGenerator() {}

    public static List<Integer> generateScale(List<Integer> baseBookIds, int targetScale) {
        validateInput(baseBookIds, targetScale);

        System.out.printf("[SYNTHETIC] Starting generation for scale %,d (base: %d real books)...%n",
                targetScale, baseBookIds.size());

        long startTime = System.nanoTime();
        List<Integer> result = buildIdList(baseBookIds, targetScale);
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

        int syntheticCount = result.size() - Math.min(baseBookIds.size(), targetScale);
        System.out.printf("[SYNTHETIC] Scale %,d generated in %d ms (real: %d, synthetic: %d, total: %d)%n",
                targetScale, elapsedMs,
                Math.min(baseBookIds.size(), targetScale),
                syntheticCount,
                result.size());

        return result;
    }

    public static List<List<Integer>> generateAllScales(List<Integer> baseBookIds) {
        System.out.println("[SYNTHETIC] ============================================");
        System.out.println("[SYNTHETIC] Running all mandatory scales...");
        System.out.println("[SYNTHETIC] ============================================");

        long globalStart = System.nanoTime();
        List<List<Integer>> results = new ArrayList<>(MANDATORY_SCALES.length);

        for (int scale : MANDATORY_SCALES) {
            results.add(generateScale(baseBookIds, scale));
        }

        long totalMs = (System.nanoTime() - globalStart) / 1_000_000;
        System.out.printf("[SYNTHETIC] All scales completed in %d ms total.%n", totalMs);
        System.out.println("[SYNTHETIC] ============================================");

        return Collections.unmodifiableList(results);
    }

    private static List<Integer> buildIdList(List<Integer> baseBookIds, int targetScale) {
        List<Integer> result = new ArrayList<>(targetScale);

        int realCount = Math.min(baseBookIds.size(), targetScale);
        for (int i = 0; i < realCount; i++) {
            result.add(baseBookIds.get(i));
        }

        if (result.size() >= targetScale) {
            return Collections.unmodifiableList(result.subList(0, targetScale));
        }

        int base   = baseBookIds.size();
        int needed = targetScale - result.size();
        int round  = 0;
        int pos    = 0;

        for (int i = 0; i < needed; i++) {
            int synId = SYNTHETIC_ID_OFFSET + round * base + pos;
            result.add(synId);
            pos++;
            if (pos >= base) {
                pos = 0;
                round++;
            }
        }

        return Collections.unmodifiableList(result);
    }

    private static void validateInput(List<Integer> baseBookIds, int targetScale) {
        if (baseBookIds == null || baseBookIds.isEmpty()) {
            throw new IllegalArgumentException("[SYNTHETIC] baseBookIds must not be null or empty.");
        }
        if (targetScale < 1) {
            throw new IllegalArgumentException("[SYNTHETIC] targetScale must be >= 1, got: " + targetScale);
        }
    }
}