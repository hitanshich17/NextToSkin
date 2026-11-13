package com.nexttoskin.core.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Topics the catalog service owns; created at startup if missing. */
@Configuration
@EnableScheduling
public class KafkaTopics {

    /** One partition per recommender instance (3 planned), so a consumer group can share the work. */
    static final int PARTITIONS = 3;

    @Bean
    NewTopic productUpdated() {
        return TopicBuilder.name(CatalogEvents.TOPIC).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    NewTopic productUpdatedDeadLetters() {
        return TopicBuilder.name(CatalogEvents.TOPIC + ".dlt").partitions(PARTITIONS).replicas(1).build();
    }
}
