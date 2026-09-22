package com.orderprocess.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OrderRequest(
        @NotBlank String customerName,
        @NotNull Long productId,
        @NotNull @Positive Integer quantity
) {
}
