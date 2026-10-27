package com.offershield.support;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * Keeps the Kafka listener containers stopped in integration tests unless a test starts them itself. Two settings are
 * needed: {@code spring.kafka.listener.auto-startup=false} (in {@link IntegrationTest}) for the first refresh, and
 * this, because Spring Framework 7 pauses cached test contexts and calls {@code start()} again when one is reused,
 * and the registry would then start every container after a refresh, auto-startup or not.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestKafkaListeners {

    @Bean
    static BeanPostProcessor listenerContainersStayStopped() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof KafkaListenerEndpointRegistry registry) {
                    registry.setAlwaysStartAfterRefresh(false);
                }
                return bean;
            }
        };
    }
}
