package com.appsdeveloperblog.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.appsdeveloperblog.core.dto.commands.ProcessPaymentCommand;
import com.appsdeveloperblog.core.dto.events.PaymentFailedEvent;
import com.appsdeveloperblog.core.dto.events.PaymentProcessedEvent;
import com.appsdeveloperblog.core.exceptions.CreditCardProcessorUnavailableException;
import com.appsdeveloperblog.payments.dao.jpa.entity.PaymentEntity;
import com.appsdeveloperblog.payments.dao.jpa.repository.PaymentRepository;
import com.appsdeveloperblog.payments.service.CreditCardProcessorRemoteService;

@EmbeddedKafka(partitions = 3, topics = {"payments-commands", "payments-events"})
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "app.kafka.topic.replicas=1"})
class PaymentCommandsHandlerIntegrationTest {

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    KafkaListenerEndpointRegistry listenerRegistry;

    @MockitoBean
    CreditCardProcessorRemoteService creditCardProcessor;

    private Consumer<String, Object> paymentEvents;

    @BeforeEach
    void setUp() {
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic());
        }
        Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, "test-" + UUID.randomUUID(), false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class);
        props.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.appsdeveloperblog.core.*");
        paymentEvents = new KafkaConsumer<>(props);
        List<TopicPartition> partitions = paymentEvents.partitionsFor("payments-events").stream()
                .map(info -> new TopicPartition(info.topic(), info.partition()))
                .toList();
        paymentEvents.assign(partitions);
        paymentEvents.seekToEnd(partitions);
        partitions.forEach(paymentEvents::position);
    }

    @AfterEach
    void closeConsumer() {
        paymentEvents.close();
    }

    @Test
    void chargingTheCardPublishesPaymentProcessedEvent() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        kafkaTemplate.send("payments-commands",
                new ProcessPaymentCommand(orderId, productId, new BigDecimal("10.50"), 2));

        PaymentProcessedEvent event = assertInstanceOf(PaymentProcessedEvent.class, nextEvent());
        assertEquals(orderId, event.getOrderId());
        PaymentEntity payment = paymentRepository.findById(event.getPaymentId()).orElseThrow();
        assertEquals(orderId, payment.getOrderId());
        assertEquals(productId, payment.getProductId());
        verify(creditCardProcessor).process(any(BigInteger.class), eq(new BigDecimal("21.00")));
    }

    @Test
    void unavailableCardProcessorPublishesPaymentFailedEvent() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        doThrow(new CreditCardProcessorUnavailableException(new RuntimeException("Connection refused")))
                .when(creditCardProcessor).process(any(), any());

        kafkaTemplate.send("payments-commands",
                new ProcessPaymentCommand(orderId, productId, new BigDecimal("10.50"), 2));

        PaymentFailedEvent event = assertInstanceOf(PaymentFailedEvent.class, nextEvent());
        assertEquals(orderId, event.getOrderId());
        assertEquals(productId, event.getProductId());
        assertEquals(2, event.getProductQuantity());
    }

    private Object nextEvent() {
        ConsumerRecord<String, Object> record =
                KafkaTestUtils.getSingleRecord(paymentEvents, "payments-events", Duration.ofSeconds(10));
        assertNotNull(record);
        return record.value();
    }
}
