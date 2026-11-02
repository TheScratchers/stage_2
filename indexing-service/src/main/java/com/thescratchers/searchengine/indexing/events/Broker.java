package com.thescratchers.searchengine.indexing.events;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Names and settings shared by the Indexing Service's broker clients. */
final class Broker {

    /** Queue of {@code BookDownloaded} events, consumed by competing Indexing workers. */
    static final String DOWNLOADED_QUEUE = "books.downloaded";

    /** Topic of {@code BookIndexed} events, read by every Search replica. */
    static final String INDEXED_TOPIC = "books.indexed";

    /** Event payloads use {@code snake_case} field names, as every JSON of the project. */
    static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private Broker() {
    }

    /**
     * Wraps {@code brokerUrl} in ActiveMQ's {@code failover:} transport, which connects in the
     * background and reconnects with a growing delay after a broker restart. A URL that already
     * uses that transport is returned unchanged.
     */
    static String failoverUrl(String brokerUrl) {
        if (brokerUrl.startsWith("failover:")) {
            return brokerUrl;
        }
        return "failover:(" + brokerUrl + ")?initialReconnectDelay=1000&maxReconnectDelay=10000";
    }
}
