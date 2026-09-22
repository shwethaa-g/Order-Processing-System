package com.orderprocess.repository;

import com.orderprocess.model.Order;
import com.orderprocess.model.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findAllByOrderByCreatedAtDesc();

    List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    List<Order> findByStatusAndRetryCountLessThan(OrderStatus status, Integer retryCount);
}
