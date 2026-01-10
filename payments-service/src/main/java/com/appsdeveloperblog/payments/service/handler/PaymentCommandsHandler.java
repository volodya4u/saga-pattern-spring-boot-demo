package com.appsdeveloperblog.payments.service.handler;

import com.appsdeveloperblog.core.dto.Payment;
import com.appsdeveloperblog.core.dto.commands.ProcessPaymentCommand;
import com.appsdeveloperblog.core.exceptions.CreditCardProcessorUnavailableException;
import com.appsdeveloperblog.payments.service.PaymentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@KafkaListener(topics = "${payments.commands.topic.name}")
public class PaymentCommandsHandler {

    private final PaymentService paymentService;

    public PaymentCommandsHandler(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @KafkaHandler
    public void handleCommand(@Payload ProcessPaymentCommand command) {

        try {
            Payment payment = new Payment(
                    command.getOrderId(),
                    command.getProductId(),
                    command.getProductPrice(),
                    command.getProductQuantity()
            );
            Payment processedPayment = paymentService.process(payment);
        } catch (CreditCardProcessorUnavailableException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }


}
