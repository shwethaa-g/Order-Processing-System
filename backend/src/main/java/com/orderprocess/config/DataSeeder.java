package com.orderprocess.config;

import com.orderprocess.model.Product;
import com.orderprocess.repository.ProductRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;

@Configuration
public class DataSeeder {

    @Bean
    CommandLineRunner seedProducts(ProductRepository productRepository) {
        return args -> {
            if (productRepository.count() > 0) {
                return;
            }
            productRepository.saveAll(List.of(
                    Product.builder().name("Wireless Mouse").sku("WM-001").price(new BigDecimal("499.00")).stockQuantity(7).build(),
                    Product.builder().name("Mechanical Keyboard").sku("MK-002").price(new BigDecimal("2499.00")).stockQuantity(5).build(),
                    Product.builder().name("USB-C Hub").sku("UH-003").price(new BigDecimal("1299.00")).stockQuantity(10).build(),
                    Product.builder().name("Webcam 1080p").sku("WC-004").price(new BigDecimal("1899.00")).stockQuantity(6).build(),
                    Product.builder().name("Laptop Stand").sku("LS-005").price(new BigDecimal("899.00")).stockQuantity(8).build()
            ));
        };
    }
}
