package com.offershield.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.mysql.MySQLContainer;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Full application context against one shared MySQL 8.4 container and one in-JVM Kafka (KRaft) broker, both started
 * once per JVM, so no test ever reaches {@code kafka:9092}. Like production, the broker never auto-creates topics:
 * the application's {@code KafkaAdmin} creates them. The letter-text AES key and the registry HMAC pepper are random
 * per JVM and the scheduled sweeper, retention job and snapshot reconciler are off (tests call them directly). The
 * Kafka listener containers do not start on their own: a test that needs the consumers starts them through {@code KafkaListenerEndpointRegistry} and stops them again, so other tests'
 * submissions stay QUEUED. RDAP and DNS lookups go to {@link LookupStubs} and Groq calls to {@link AiStubs} (a fake
 * key), never to the internet.
 */
@SpringBootTest
@Import({LookupStubs.TestLookups.class, TestKafkaListeners.class})
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    @ServiceConnection
    protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    protected static final EmbeddedKafkaKraftBroker KAFKA = new EmbeddedKafkaKraftBroker(1, 3);

    private static final String TEXT_KEY_BASE64 = randomKeyBase64();
    /** The registry HMAC pepper, random per JVM (never the .env value). */
    public static final String HMAC_PEPPER = randomKeyBase64() + randomKeyBase64();

    static {
        // Before Spring configures logging, the broker would log at DEBUG.
        ((Logger) LoggerFactory.getLogger("kafka")).setLevel(Level.WARN);
        ((Logger) LoggerFactory.getLogger("org.apache.kafka")).setLevel(Level.WARN);
        MYSQL.start();
        KAFKA.brokerProperty("auto.create.topics.enable", "false");
        KAFKA.afterPropertiesSet();
        Runtime.getRuntime().addShutdownHook(new Thread(IntegrationTest::stopKafka, "embedded-kafka-stop"));
    }

    @Autowired
    protected MockMvc mockMvc;

    @DynamicPropertySource
    static void sharedInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBrokersAsString);
        registry.add("offershield.crypto.text-key-base64", () -> TEXT_KEY_BASE64);
        registry.add("offershield.registry.hmac-pepper", () -> HMAC_PEPPER);
        registry.add("offershield.submission.sweeper.enabled", () -> "false");
        registry.add("offershield.retention.enabled", () -> "false");
        registry.add("offershield.registry.reconcile.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("offershield.lookup.rdap.bootstrap-url", () -> LookupStubs.RDAP.bootstrapUrl().toString());
        registry.add("offershield.lookup.rdap.require-https", () -> "false");
        registry.add("offershield.ai.base-url", AiStubs.GROQ::baseUrl);
        registry.add("offershield.ai.api-keys", () -> AiStubs.TEST_KEY);
    }

    private static void stopKafka() {
        try {
            KAFKA.destroy();
        } catch (RuntimeException ignored) {
            // JVM exit: best effort (Windows can keep log segments locked).
        }
    }

    private static String randomKeyBase64() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
