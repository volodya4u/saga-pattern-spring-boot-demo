package com.appsdeveloperblog.core.dto.commands;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class ReserveProductCommand {

    private UUID productId;
    private Integer productQuantity;
    private UUID orderId;

}
