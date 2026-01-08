package com.appsdeveloperblog.products.service.handler;

import com.appsdeveloperblog.core.dto.Product;
import com.appsdeveloperblog.core.dto.commands.ReserveProductCommand;
import com.appsdeveloperblog.products.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@KafkaListener(topics = {"${products.commands.topic.name}"})
public class ProductCommandsHandler {

    private final ProductService productService;

    public ProductCommandsHandler(ProductService productService) {
        this.productService = productService;
    }

    @KafkaHandler
    public void handleCommand(@Payload ReserveProductCommand command) {

        try {
            Product desiredProduct = new Product(command.getProductId(), command.getProductQuantity());
            Product reserveProduct = productService.reserve(desiredProduct, command.getOrderId());
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
        }

    }
}
