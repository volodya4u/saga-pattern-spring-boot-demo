package com.appsdeveloperblog.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

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

import com.appsdeveloperblog.core.dto.Order;
import com.appsdeveloperblog.core.dto.commands.CancelProductReservationCommand;
import com.appsdeveloperblog.core.dto.commands.ProcessPaymentCommand;
import com.appsdeveloperblog.core.dto.commands.ReserveProductCommand;
import com.appsdeveloperblog.core.dto.events.PaymentFailedEvent;
import com.appsdeveloperblog.core.dto.events.PaymentProcessedEvent;
import com.appsdeveloperblog.core.dto.events.ProductReservationCancelledEvent;
import com.appsdeveloperblog.core.dto.events.ProductReservationFailedEvent;
import com.appsdeveloperblog.core.dto.events.ProductReservedEvent;
import com.appsdeveloperblog.core.types.OrderStatus;
import com.appsdeveloperblog.orders.dao.jpa.entity.OrderHistoryEntity;
import com.appsdeveloperblog.orders.dao.jpa.repository.OrderHistoryRepository;
import com.appsdeveloperblog.orders.dao.jpa.repository.OrderRepository;
import com.appsdeveloperblog.orders.service.OrderService;

/**
 * Runs the order saga against an embedded broker. The test stands in for the
 * products and payments services: it reads the commands the saga sends them
 * and answers with their events.
 */
@EmbeddedKafka(partitions = 3, topics = {
        "orders-events", "orders-commands",
        "products-commands", "products-events",
        "payments-commands", "payments-events"})
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "app.kafka.topic.replicas=1"})
class OrderSagaIntegrationTest {

    private static final BigDecimal PRICE = new BigDecimal("10.50");

    @Autowired
    OrderService orderService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    OrderHistoryRepository orderHistoryRepository;

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    KafkaListenerEndpointRegistry listenerRegistry;

    private Consumer<String, Object> commands;
    private final List<Object> receivedCommands = new ArrayList<>();

    @BeforeEach
    void setUp() {
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            int topics = container.getContainerProperties().getTopics().length;
            ContainerTestUtils.waitForAssignment(container, topics * embeddedKafka.getPartitionsPerTopic());
        }
        Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, "test-" + UUID.randomUUID(), false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class);
        props.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.appsdeveloperblog.core.*");
        commands = new KafkaConsumer<>(props);
        List<TopicPartition> partitions = new ArrayList<>();
        for (String topic : List.of("products-commands", "payments-commands")) {
            for (int partition = 0; partition < embeddedKafka.getPartitionsPerTopic(); partition++) {
                partitions.add(new TopicPartition(topic, partition));
            }
        }
        commands.assign(partitions);
        commands.seekToEnd(partitions);
        partitions.forEach(commands::position);
    }

    @AfterEach
    void closeConsumer() {
        commands.close();
    }

    @Test
    void orderIsApprovedOnceProductIsReservedAndPaymentProcessed() {
        UUID productId = UUID.randomUUID();
        UUID orderId = placeOrder(productId, 2);

        ReserveProductCommand reserve = awaitCommand(ReserveProductCommand.class, orderId);
        assertEquals(productId, reserve.getProductId());
        assertEquals(2, reserve.getProductQuantity());
        kafkaTemplate.send("products-events", new ProductReservedEvent(orderId, productId, PRICE, 2));

        ProcessPaymentCommand payment = awaitCommand(ProcessPaymentCommand.class, orderId);
        assertEquals(productId, payment.getProductId());
        assertEquals(0, PRICE.compareTo(payment.getProductPrice()));
        assertEquals(2, payment.getProductQuantity());
        kafkaTemplate.send("payments-events", new PaymentProcessedEvent(orderId, UUID.randomUUID()));

        awaitOrder(orderId, OrderStatus.APPROVED, List.of(OrderStatus.CREATED, OrderStatus.APPROVED));
    }

    @Test
    void orderIsRejectedWhenProductCannotBeReserved() {
        UUID productId = UUID.randomUUID();
        UUID orderId = placeOrder(productId, 10);

        awaitCommand(ReserveProductCommand.class, orderId);
        kafkaTemplate.send("products-events", new ProductReservationFailedEvent(productId, orderId, 10));

        awaitOrder(orderId, OrderStatus.REJECTED, List.of(OrderStatus.CREATED, OrderStatus.REJECTED));
    }

    @Test
    void reservationIsCancelledAndOrderRejectedWhenPaymentFails() {
        UUID productId = UUID.randomUUID();
        UUID orderId = placeOrder(productId, 1);

        awaitCommand(ReserveProductCommand.class, orderId);
        kafkaTemplate.send("products-events", new ProductReservedEvent(orderId, productId, PRICE, 1));
        awaitCommand(ProcessPaymentCommand.class, orderId);
        kafkaTemplate.send("payments-events", new PaymentFailedEvent(orderId, productId, 1));

        CancelProductReservationCommand cancel = awaitCommand(CancelProductReservationCommand.class, orderId);
        assertEquals(productId, cancel.getProductId());
        assertEquals(1, cancel.getProductQuantity());
        kafkaTemplate.send("products-events", new ProductReservationCancelledEvent(productId, orderId));

        awaitOrder(orderId, OrderStatus.REJECTED, List.of(OrderStatus.CREATED, OrderStatus.REJECTED));
    }

    private UUID placeOrder(UUID productId, int quantity) {
        Order order = orderService.placeOrder(new Order(UUID.randomUUID(), productId, quantity, null));
        assertEquals(OrderStatus.CREATED, order.getStatus());
        return order.getOrderId();
    }

    /** Waits for the saga to send a command of the given type for the order. */
    private <T> T awaitCommand(Class<T> type, UUID orderId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, Object> record : commands.poll(Duration.ofMillis(200))) {
                receivedCommands.add(record.value());
            }
            for (Object command : receivedCommands) {
                if (type.isInstance(command) && orderId.equals(orderIdOf(command))) {
                    receivedCommands.remove(command);
                    return type.cast(command);
                }
            }
        }
        return fail("No " + type.getSimpleName() + " for order " + orderId + "; received " + receivedCommands);
    }

    private static UUID orderIdOf(Object command) {
        if (command instanceof ReserveProductCommand reserve) {
            return reserve.getOrderId();
        }
        if (command instanceof ProcessPaymentCommand payment) {
            return payment.getOrderId();
        }
        if (command instanceof CancelProductReservationCommand cancel) {
            return cancel.getOrderId();
        }
        return null;
    }

    /** Waits for the order to reach a status and for its history to list the given statuses. */
    private void awaitOrder(UUID orderId, OrderStatus status, List<OrderStatus> history) {
        Supplier<OrderStatus> currentStatus = () -> orderRepository.findById(orderId).orElseThrow().getStatus();
        Supplier<List<OrderStatus>> currentHistory = () -> orderHistoryRepository.findByOrderId(orderId).stream()
                .sorted(Comparator.comparing(OrderHistoryEntity::getCreatedAt))
                .map(OrderHistoryEntity::getStatus)
                .toList();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (currentStatus.get() == status && currentHistory.get().equals(history)) {
                return;
            }
            sleep();
        }
        assertEquals(status, currentStatus.get());
        assertEquals(history, currentHistory.get());
    }

    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
