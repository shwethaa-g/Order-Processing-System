package com.orderprocess.controller;

import com.orderprocess.dto.OrderCreateResponse;
import com.orderprocess.dto.OrderRequest;
import com.orderprocess.dto.OrderResponse;
import com.orderprocess.exception.InvalidOrderRequestException;
import com.orderprocess.exception.ResourceNotFoundException;
import com.orderprocess.model.Order;
import com.orderprocess.model.OrderStatus;
import com.orderprocess.repository.OrderRepository;
import com.orderprocess.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final TaskExecutor orderTaskExecutor;

    @PostMapping
    public ResponseEntity<OrderCreateResponse> createOrder(@Valid @RequestBody OrderRequest request) {
        Order order = orderService.createPendingOrder(request.customerName(), request.productId(), request.quantity());
        Long orderId = order.getId();
        orderTaskExecutor.execute(() -> orderService.submitOrder(orderId));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new OrderCreateResponse(order.getId(), order.getStatus().name()));
    }

    @GetMapping("/dlq")
    public List<OrderResponse> getDeadLetterOrders() {
        return orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.DEAD_LETTER).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping
    public List<OrderResponse> listOrders(@RequestParam(required = false) String status) {
        List<Order> orders;
        if (status != null) {
            orders = orderRepository.findByStatusOrderByCreatedAtDesc(parseStatus(status));
        } else {
            orders = orderRepository.findAllByOrderByCreatedAtDesc();
        }
        return orders.stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
        return toResponse(order);
    }

    private OrderStatus parseStatus(String status) {
        try {
            return OrderStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new InvalidOrderRequestException("Invalid status: " + status);
        }
    }

    private OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerName(),
                order.getProduct().getId(),
                order.getQuantity(),
                order.getStatus().name(),
                order.getRetryCount(),
                order.getFailureReason(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
