package com.offershield.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} jobs (the QUEUED-submission sweeper and the daily 30-day retention job). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
