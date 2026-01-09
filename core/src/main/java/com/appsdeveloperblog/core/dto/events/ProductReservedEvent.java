package com.appsdeveloperblog.core.dto.events;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class ProductReservedEvent {

    private UUID orderId;
    private UUID productId;
    private BigDecimal productPrice;
    private Integer productQuantity;
}
