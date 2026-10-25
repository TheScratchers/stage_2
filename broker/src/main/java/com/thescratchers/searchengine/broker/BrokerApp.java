package com.thescratchers.searchengine.broker;

import com.thescratchers.searchengine.common.Config;
import org.apache.activemq.RedeliveryPolicy;
import org.apache.activemq.broker.BrokerPlugin;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.apache.activemq.broker.region.policy.RedeliveryPolicyMap;
import org.apache.activemq.broker.region.policy.SharedDeadLetterStrategy;
import org.apache.activemq.broker.util.RedeliveryPlugin;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.store.kahadb.KahaDBPersistenceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point of the embedded ActiveMQ Broker process.
 *
 * <p>Provides persistent messaging, redelivery policy (up to 5 attempts), and dead-letter queue
 * ({@code ActiveMQ.DLQ}) routing for inter-service communication ({@code books.downloaded} queue
 * and {@code books.indexed} topic).
 */
public final class BrokerApp {

    /** Name reported by the broker service. */
    public static final String BROKER_NAME = "search-engine-broker";

    /** Default broker connector URL. */
    public static final String DEFAULT_BROKER_URL = "tcp://localhost:61616";

    /** Maximum redeliveries before forwarding to DLQ. */
    public static final int MAX_REDELIVERIES = 5;

    private static final Logger LOG = LoggerFactory.getLogger(BrokerApp.class);

    private BrokerApp() {
    }

    public static void main(String[] args) {
        String brokerUrl = Config.get("BROKER_URL", DEFAULT_BROKER_URL);
        Path storageDir = Config.dataDir().resolve("broker");

        try {
            BrokerService broker = start(brokerUrl, storageDir);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Shutting down ActiveMQ broker...");
                try {
                    broker.stop();
                    broker.waitUntilStopped();
                } catch (Exception e) {
                    LOG.error("Error stopping broker during shutdown", e);
                }
            }));
            broker.waitUntilStopped();
        } catch (Exception e) {
            LOG.error("Fatal error starting ActiveMQ broker", e);
            System.exit(1);
        }
    }

    /**
     * Creates and configures a {@link BrokerService} instance with KahaDB persistence,
     * redelivery policy, and DLQ handling.
     *
     * @param brokerUrl  URL to bind connectors (e.g. {@code tcp://localhost:61616})
     * @param storageDir directory where KahaDB store data will be persisted
     * @return a configured {@link BrokerService} ready to be started
     * @throws Exception if creating or configuring the broker fails
     */
    public static BrokerService createBroker(String brokerUrl, Path storageDir) throws Exception {
        Files.createDirectories(storageDir);

        BrokerService broker = new BrokerService();
        broker.setBrokerName(BROKER_NAME);
        broker.setPersistent(true);
        broker.setUseJmx(false);
        broker.setSchedulerSupport(true);
        broker.setDataDirectoryFile(storageDir.toFile());

        // KahaDB persistence adapter
        Path kahadbDir = storageDir.resolve("kahadb");
        Files.createDirectories(kahadbDir);
        KahaDBPersistenceAdapter kahadb = new KahaDBPersistenceAdapter();
        kahadb.setDirectory(kahadbDir.toFile());
        broker.setPersistenceAdapter(kahadb);

        // Destination policy with ActiveMQ.DLQ
        PolicyMap destinationPolicy = new PolicyMap();
        PolicyEntry defaultEntry = new PolicyEntry();
        SharedDeadLetterStrategy dlqStrategy = new SharedDeadLetterStrategy();
        dlqStrategy.setDeadLetterQueue(new ActiveMQQueue("ActiveMQ.DLQ"));
        dlqStrategy.setProcessExpired(true);
        dlqStrategy.setProcessNonPersistent(false);
        defaultEntry.setDeadLetterStrategy(dlqStrategy);
        destinationPolicy.setDefaultEntry(defaultEntry);
        broker.setDestinationPolicy(destinationPolicy);

        // Server-side Redelivery plugin (5 attempts before sending to DLQ)
        RedeliveryPlugin redeliveryPlugin = new RedeliveryPlugin();
        RedeliveryPolicyMap redeliveryPolicyMap = new RedeliveryPolicyMap();
        RedeliveryPolicy defaultPolicy = new RedeliveryPolicy();
        defaultPolicy.setMaximumRedeliveries(MAX_REDELIVERIES);
        defaultPolicy.setInitialRedeliveryDelay(2000L);
        defaultPolicy.setRedeliveryDelay(2000L);
        defaultPolicy.setUseExponentialBackOff(false);
        redeliveryPolicyMap.setDefaultEntry(defaultPolicy);
        redeliveryPlugin.setRedeliveryPolicyMap(redeliveryPolicyMap);
        broker.setPlugins(new BrokerPlugin[] { redeliveryPlugin });

        broker.addConnector(brokerUrl);
        return broker;
    }

    /**
     * Creates and starts a {@link BrokerService}.
     *
     * @param brokerUrl  URL to bind connectors
     * @param storageDir directory where KahaDB store data will be persisted
     * @return the running {@link BrokerService}
     * @throws Exception if starting the broker fails
     */
    public static BrokerService start(String brokerUrl, Path storageDir) throws Exception {
        LOG.info("Starting ActiveMQ broker {} on {} with storage in {}", BROKER_NAME, brokerUrl, storageDir);
        BrokerService broker = createBroker(brokerUrl, storageDir);
        broker.start();
        broker.waitUntilStarted();
        LOG.info("ActiveMQ broker {} started successfully", BROKER_NAME);
        return broker;
    }
}
