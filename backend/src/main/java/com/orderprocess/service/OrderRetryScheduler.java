package com.orderprocess.service;

import com.orderprocess.model.Order;
import com.orderprocess.model.OrderStatus;
import com.orderprocess.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderRetryScheduler {

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    @Value("${order.retry.max-retries}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${order.retry.fixed-delay-ms:5000}")
    public void retryFailedOrders() {
        List<Order> retryable = orderRepository.findByStatusAndRetryCountLessThan(OrderStatus.FAILED, maxRetries);
        for (Order order : retryable) {
            try {
                orderService.submitOrder(order.getId());
            } catch (Exception e) {
                log.error("Retry attempt threw unexpectedly for order {}", order.getId(), e);
            }
        }
    }
}
