package com.appsdeveloperblog.core.dto.events;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {

    private UUID orderId;
    private UUID customerId;
    private UUID productId;
    private Integer quantity;

}
