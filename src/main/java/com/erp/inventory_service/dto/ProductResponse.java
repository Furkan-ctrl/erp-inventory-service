package com.erp.inventory_service.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {
    private Long id;
    private String name;
    private String sku;
    private String description;
    private BigDecimal price;
    private Integer stockQty;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}