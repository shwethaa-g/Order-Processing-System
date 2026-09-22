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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderServiceConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void concurrentOrdersNeverOversellStock() throws InterruptedException {
        int startingStock = 5;
        int numRequests = 10; // more requests than stock, to force contention

        Product product = productRepository.save(Product.builder()
                .name("Concurrency Test Widget")
                .sku("TEST-CONC-" + System.nanoTime())
                .price(new BigDecimal("9.99"))
                .stockQuantity(startingStock)
                .build());

        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < numRequests; i++) {
            orders.add(orderService.createPendingOrder("tester" + i, product.getId(), 1));
        }

        ExecutorService executor = Executors.newFixedThreadPool(numRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numRequests);

        for (Order order : orders) {
            Long orderId = order.getId();
            executor.submit(() -> {
                try {
                    startLatch.await();
                    orderService.submitOrder(orderId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(finished).as("all submissions finished within timeout").isTrue();

        Product refreshedProduct = productRepository.findById(product.getId()).orElseThrow();
        List<Order> refreshedOrders = orderRepository.findAllById(orders.stream().map(Order::getId).toList());

        long completedQty = refreshedOrders.stream()
                .filter(o -> o.getStatus() == OrderStatus.COMPLETED)
                .mapToLong(Order::getQuantity)
                .sum();
        long nonCompletedCount = refreshedOrders.stream()
                .filter(o -> o.getStatus() != OrderStatus.COMPLETED)
                .count();

        assertThat(refreshedProduct.getStockQuantity()).isGreaterThanOrEqualTo(0);
        assertThat(completedQty).isEqualTo(startingStock);
        assertThat(refreshedProduct.getStockQuantity()).isEqualTo(0);
        assertThat(completedQty + nonCompletedCount).isEqualTo(numRequests);
    }
}
