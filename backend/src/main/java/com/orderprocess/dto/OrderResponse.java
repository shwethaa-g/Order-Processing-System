package com.orderprocess.dto;

import java.time.LocalDateTime;

public record OrderResponse(
        Long id,
        String customerName,
        Long productId,
        Integer quantity,
        String status,
        Integer retryCount,
        String failureReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
