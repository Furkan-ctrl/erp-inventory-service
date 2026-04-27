package com.erp.inventory_service.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class CustomerRequest {

    @NotBlank(message = "Customer name is required")
    @Size(max = 100)
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Please provide a valid email address")
    private String email;

    @Size(max = 20)
    private String phone;

    @Size(max = 255)
    private String address;
}