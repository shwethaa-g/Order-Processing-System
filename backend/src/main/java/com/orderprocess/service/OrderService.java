package com.orderprocess.service;

import com.orderprocess.exception.InvalidOrderRequestException;
import com.orderprocess.exception.ResourceNotFoundException;
import com.orderprocess.model.Order;
import com.orderprocess.model.OrderStatus;
import com.orderprocess.model.Product;
import com.orderprocess.repository.OrderRepository;
import com.orderprocess.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private static final String INSUFFICIENT_STOCK_REASON = "insufficient stock";

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    @Value("${order.retry.max-retries}")
    private int maxRetries;

    // Split from submitOrder so an order id exists for the 202 response before async processing runs.
    @Transactional
    public Order createPendingOrder(String customerName, Long productId, Integer quantity) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new InvalidOrderRequestException("productId does not exist: " + productId));

        Order order = Order.builder()
                .customerName(customerName)
                .product(product)
                .quantity(quantity)
                .status(OrderStatus.PENDING)
                .retryCount(0)
                .build();

        return orderRepository.save(order);
    }

    // Shared by the first attempt and every retry attempt; pessimistic lock is held for the whole method.
    @Transactional
    public void submitOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        order.setStatus(OrderStatus.PROCESSING);
        orderRepository.save(order);

        Long productId = order.getProduct().getId();
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        if (product.getStockQuantity() >= order.getQuantity()) {
            product.setStockQuantity(product.getStockQuantity() - order.getQuantity());
            productRepository.save(product);

            order.setStatus(OrderStatus.COMPLETED);
            order.setFailureReason(null);
            log.info("Order {} COMPLETED (product={}, qty={}, remainingStock={})",
                    order.getId(), productId, order.getQuantity(), product.getStockQuantity());
        } else {
            int nextRetryCount = order.getRetryCount() + 1;
            order.setRetryCount(nextRetryCount);
            order.setFailureReason(INSUFFICIENT_STOCK_REASON);

            if (nextRetryCount >= maxRetries) {
                order.setStatus(OrderStatus.DEAD_LETTER);
                log.info("Order {} DEAD_LETTER after {} attempts (product={}, requestedQty={}, availableStock={})",
                        order.getId(), nextRetryCount, productId, order.getQuantity(), product.getStockQuantity());
            } else {
                order.setStatus(OrderStatus.FAILED);
                log.info("Order {} FAILED, attempt {}/{} (product={}, requestedQty={}, availableStock={})",
                        order.getId(), nextRetryCount, maxRetries, productId, order.getQuantity(), product.getStockQuantity());
            }
        }

        orderRepository.save(order);
    }
}
