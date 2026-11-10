package iot.iot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the Node-RED state collector; disable with app.collector.enabled=false (COLLECTOR_ENABLED). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.collector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
