package com.appsdeveloperblog.products;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
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

import com.appsdeveloperblog.core.dto.commands.CancelProductReservationCommand;
import com.appsdeveloperblog.core.dto.commands.ReserveProductCommand;
import com.appsdeveloperblog.core.dto.events.ProductReservationCancelledEvent;
import com.appsdeveloperblog.core.dto.events.ProductReservationFailedEvent;
import com.appsdeveloperblog.core.dto.events.ProductReservedEvent;
import com.appsdeveloperblog.products.dao.jpa.entity.ProductEntity;
import com.appsdeveloperblog.products.dao.jpa.repository.ProductRepository;

@EmbeddedKafka(partitions = 3, topics = {"products-commands", "products-events"})
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "app.kafka.topic.replicas=1"})
class ProductCommandsHandlerIntegrationTest {

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    ProductRepository productRepository;

    @Autowired
    EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    KafkaListenerEndpointRegistry listenerRegistry;

    private Consumer<String, Object> productEvents;

    @BeforeEach
    void setUp() {
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic());
        }
        Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, "test-" + UUID.randomUUID(), false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class);
        props.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.appsdeveloperblog.core.*");
        productEvents = new KafkaConsumer<>(props);
        List<TopicPartition> partitions = productEvents.partitionsFor("products-events").stream()
                .map(info -> new TopicPartition(info.topic(), info.partition()))
                .toList();
        productEvents.assign(partitions);
        productEvents.seekToEnd(partitions);
        partitions.forEach(productEvents::position);
    }

    @AfterEach
    void closeConsumer() {
        productEvents.close();
    }

    @Test
    void reservingAvailableStockPublishesProductReservedEvent() {
        ProductEntity product = saveProduct(5);
        UUID orderId = UUID.randomUUID();

        kafkaTemplate.send("products-commands", new ReserveProductCommand(product.getId(), 2, orderId));

        ProductReservedEvent event = assertInstanceOf(ProductReservedEvent.class, nextEvent());
        assertEquals(orderId, event.getOrderId());
        assertEquals(product.getId(), event.getProductId());
        assertEquals(0, new BigDecimal("10").compareTo(event.getProductPrice()));
        assertEquals(2, event.getProductQuantity());
        assertEquals(3, stockOf(product));
    }

    @Test
    void reservingMoreThanTheStockPublishesProductReservationFailedEvent() {
        ProductEntity product = saveProduct(5);
        UUID orderId = UUID.randomUUID();

        kafkaTemplate.send("products-commands", new ReserveProductCommand(product.getId(), 10, orderId));

        ProductReservationFailedEvent event = assertInstanceOf(ProductReservationFailedEvent.class, nextEvent());
        assertEquals(orderId, event.getOrderId());
        assertEquals(product.getId(), event.getProductId());
        assertEquals(10, event.getProductQuantity());
        assertEquals(5, stockOf(product));
    }

    @Test
    void cancellingAReservationReturnsTheStock() {
        ProductEntity product = saveProduct(3);
        UUID orderId = UUID.randomUUID();

        kafkaTemplate.send("products-commands", new CancelProductReservationCommand(product.getId(), orderId, 2));

        ProductReservationCancelledEvent event =
                assertInstanceOf(ProductReservationCancelledEvent.class, nextEvent());
        assertEquals(orderId, event.getOrderId());
        assertEquals(product.getId(), event.getProductId());
        assertEquals(5, stockOf(product));
    }

    private ProductEntity saveProduct(int quantity) {
        ProductEntity product = new ProductEntity();
        product.setName("Test product");
        product.setPrice(new BigDecimal("10"));
        product.setQuantity(quantity);
        return productRepository.save(product);
    }

    private int stockOf(ProductEntity product) {
        return productRepository.findById(product.getId()).orElseThrow().getQuantity();
    }

    private Object nextEvent() {
        ConsumerRecord<String, Object> record =
                KafkaTestUtils.getSingleRecord(productEvents, "products-events", Duration.ofSeconds(10));
        assertNotNull(record);
        return record.value();
    }
}
