package com.orderprocess.service;

import com.orderprocess.model.Order;
import com.orderprocess.model.OrderStatus;
import com.orderprocess.model.Product;
import com.orderprocess.repository.OrderRepository;
import com.orderprocess.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderRetrySchedulerTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRetryScheduler retryScheduler;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void failedOrderRetriedAndCompletesOnceStockAvailable() {
        Product product = productRepository.save(Product.builder()
                .name("Retry Test Widget")
                .sku("TEST-RETRY-" + System.nanoTime())
                .price(new BigDecimal("1.00"))
                .stockQuantity(0)
                .build());

        Order order = orderService.createPendingOrder("retryTester", product.getId(), 1);
        orderService.submitOrder(order.getId());

        Order afterFirstAttempt = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(afterFirstAttempt.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(afterFirstAttempt.getRetryCount()).isEqualTo(1);
        assertThat(afterFirstAttempt.getFailureReason()).isEqualTo("insufficient stock");

        product.setStockQuantity(5);
        productRepository.save(product);

        retryScheduler.retryFailedOrders();

        Order afterRetry = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(afterRetry.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(afterRetry.getFailureReason()).isNull();
    }

    @Test
    void orderMovesToDeadLetterAfterMaxRetries() {
        Product product = productRepository.save(Product.builder()
                .name("DLQ Test Widget")
                .sku("TEST-DLQ-" + System.nanoTime())
                .price(new BigDecimal("1.00"))
                .stockQuantity(0)
                .build());

        Order order = orderService.createPendingOrder("dlqTester", product.getId(), 1);
        orderService.submitOrder(order.getId()); // attempt 1 -> FAILED, retryCount=1
        retryScheduler.retryFailedOrders();       // attempt 2 -> FAILED, retryCount=2
        retryScheduler.retryFailedOrders();       // attempt 3 -> DEAD_LETTER, retryCount=3

        Order finalOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.DEAD_LETTER);
        assertThat(finalOrder.getRetryCount()).isEqualTo(3);
        assertThat(finalOrder.getFailureReason()).isEqualTo("insufficient stock");

        retryScheduler.retryFailedOrders(); // must not pick up a DEAD_LETTER order
        Order stillDeadLetter = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(stillDeadLetter.getStatus()).isEqualTo(OrderStatus.DEAD_LETTER);
        assertThat(stillDeadLetter.getRetryCount()).isEqualTo(3);
    }
}
