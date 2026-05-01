package com.erp.inventory_service.service;

import com.erp.inventory_service.dto.CustomerRequest;
import com.erp.inventory_service.dto.CustomerResponse;
import com.erp.inventory_service.model.Customer;
import com.erp.inventory_service.exception.InventoryException;
import com.erp.inventory_service.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerService {

    private final CustomerRepository customerRepository;

    @Transactional
    public CustomerResponse createCustomer(CustomerRequest request) {
        if (customerRepository.existsByEmail(request.getEmail())) {
            throw new InventoryException(
                    "A customer with email '" + request.getEmail() + "' already exists",
                    HttpStatus.CONFLICT
            );
        }

        Customer customer = Customer.builder()
                .name(request.getName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .address(request.getAddress())
                .build();

        Customer saved = customerRepository.save(customer);
        log.info("Customer created: id={}, email={}", saved.getId(), saved.getEmail());
        return toResponse(saved);
    }



    public CustomerResponse getCustomerById(Long id) {
        return toResponse(findActiveCustomer(id));
    }

    public CustomerResponse getCustomerByEmail(String email) {
        Customer customer = customerRepository.findByEmailAndActiveTrue(email)
                .orElseThrow(() -> new InventoryException(
                        "Customer not found with email: " + email,
                        HttpStatus.NOT_FOUND
                ));
        return toResponse(customer);
    }


    public Page<CustomerResponse> getAllCustomers(int page, int size, String sortBy) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortBy).ascending());
        return customerRepository.findByActiveTrue(pageable)
                .map(this::toResponse);
    }

    public Page<CustomerResponse> searchCustomers(String name, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        return customerRepository
                .findByNameContainingIgnoreCaseAndActiveTrue(name, pageable)
                .map(this::toResponse);
    }



    @Transactional
    public CustomerResponse updateCustomer(Long id, CustomerRequest request) {
        Customer customer = findActiveCustomer(id);

        boolean emailChanged = !customer.getEmail().equals(request.getEmail());
        if (emailChanged && customerRepository.existsByEmail(request.getEmail())) {
            throw new InventoryException(
                    "A customer with email '" + request.getEmail() + "' already exists",
                    HttpStatus.CONFLICT
            );
        }

        customer.setName(request.getName());
        customer.setEmail(request.getEmail());
        customer.setPhone(request.getPhone());
        customer.setAddress(request.getAddress());
        // @PreUpdate on the entity will automatically update the updatedAt timestamp

        Customer saved = customerRepository.save(customer);
        log.info("Customer updated: id={}", saved.getId());
        return toResponse(saved);
    }



    @Transactional
    public void deleteCustomer(Long id) {
        Customer customer = findActiveCustomer(id);
        customer.setActive(false);
        customerRepository.save(customer);
        log.info("Customer soft-deleted: id={}", id);
    }



    private Customer findActiveCustomer(Long id) {
        return customerRepository.findById(id)
                .filter(Customer::isActive)
                .orElseThrow(() -> new InventoryException(
                        "Customer not found with id: " + id,
                        HttpStatus.NOT_FOUND
                ));
    }


    private CustomerResponse toResponse(Customer customer) {
        return CustomerResponse.builder()
                .id(customer.getId())
                .name(customer.getName())
                .email(customer.getEmail())
                .phone(customer.getPhone())
                .address(customer.getAddress())
                .active(customer.isActive())
                .createdAt(customer.getCreatedAt())
                .updatedAt(customer.getUpdatedAt())
                .build();
    }
}
