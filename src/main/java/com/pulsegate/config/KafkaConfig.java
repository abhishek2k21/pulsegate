package com.pulsegate.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

@Configuration
public class KafkaConfig {

    public static final String REQUEST_EVENTS_TOPIC = "pulsegate.request.events";
    public static final String CB_TRANSITIONS_TOPIC = "pulsegate.cb.transitions";
    public static final String ANALYTICS_TOPIC      = "pulsegate.analytics";

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,      bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.ACKS_CONFIG,    "1",
            ProducerConfig.RETRIES_CONFIG, 3,
            ProducerConfig.LINGER_MS_CONFIG, 5
        ));
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean public NewTopic requestEventsTopic() { return TopicBuilder.name(REQUEST_EVENTS_TOPIC).partitions(3).replicas(1).build(); }
    @Bean public NewTopic cbTransitionsTopic() { return TopicBuilder.name(CB_TRANSITIONS_TOPIC).partitions(1).replicas(1).build(); }
    @Bean public NewTopic analyticsTopic()     { return TopicBuilder.name(ANALYTICS_TOPIC).partitions(3).replicas(1).build(); }
}
