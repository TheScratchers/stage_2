package com.thescratchers.searchengine.indexing.events;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thescratchers.searchengine.indexing.BookIndexer.IndexResult;
import jakarta.jms.MessageConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class BookIndexedPublisherTest {

    @TempDir
    Path tempDir;

    @Test
    void publishesBookIndexedOnTheTopic() throws Exception {
        try (TestBroker broker = new TestBroker(tempDir);
             BookIndexedPublisher publisher = new BookIndexedPublisher(broker.url())) {
            MessageConsumer indexed = broker.subscribeIndexed();

            publisher.onIndexed(new IndexResult(1342, 6730, 128559));

            JsonObject event = JsonParser.parseString(TestBroker.text(indexed.receive(30_000))).getAsJsonObject();
            assertEquals("BookIndexed", event.get("event").getAsString());
            assertEquals(1342, event.get("book_id").getAsInt());
            assertEquals(6730, event.get("terms").getAsInt());
        }
    }

    @Test
    void aBrokerThatIsDownDoesNotFailOrBlockTheCaller() throws Exception {
        String unreachable;
        try (TestBroker broker = new TestBroker(tempDir)) {
            unreachable = broker.url();
        }

        try (BookIndexedPublisher publisher = new BookIndexedPublisher(unreachable)) {
            assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> publisher.onIndexed(new IndexResult(1342, 6730, 128559)));
        }
    }
}
