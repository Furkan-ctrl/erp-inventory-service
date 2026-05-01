package com.erp.inventory_service.service;

import com.erp.inventory_service.dto.ProductRequest;
import com.erp.inventory_service.dto.ProductResponse;
import com.erp.inventory_service.exception.InventoryException;
import com.erp.inventory_service.model.Product;
import com.erp.inventory_service.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {
    private final ProductRepository productRepository;

    @Value("${file.upload-dir}")
    private String uploadDir;

    public ProductResponse createProduct(ProductRequest request){
        if(productRepository.existsBySku(request.getSku())){
            throw new InventoryException("A product with SKU " + request.getSku()
                    + "already exists", HttpStatus.CONFLICT);
        }
        Product product = Product.builder()
                .name(request.getName())
                .sku((request.getSku()))
                .description((request.getDescription()))
                .price(request.getPrice())
                .stockQty(request.getStockQty())
                .build();

        Product saved = productRepository.save(product);
        log.info("Product created: id= {}, sku = {}", saved.getId(),saved.getSku());
        return toResponse(saved);
    }

    public ProductResponse getProductById(Long id){
        return toResponse(findActiveProduct(id));
    }

    public Page<ProductResponse> getAllProducts(int page, int size, String sortBy){
        Pageable pageable = PageRequest.of(page,size, Sort.by(sortBy).ascending());

         return productRepository.findByActiveTrue(pageable).map(this::toResponse);
    }

    public Page<ProductResponse> searchProducts(String name, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        return productRepository
                .findByNameContainingIgnoreCaseAndActiveTrue(name, pageable)
                .map(this::toResponse);

    }

    @Transactional
    public ProductResponse updateProduct(Long id, ProductRequest request) {
        Product product = findActiveProduct(id);

        if (!product.getSku().equals(request.getSku())
                && productRepository.existsBySku(request.getSku())) {
            throw new InventoryException(
                    "A product with SKU '" + request.getSku() + "' already exists",
                    HttpStatus.CONFLICT
            );
        }

        product.setName(request.getName());
        product.setSku(request.getSku());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setStockQty(request.getStockQty());

        return toResponse(productRepository.save(product));



    }
    @Transactional
    public void deleteProduct(Long id) {
        Product product = findActiveProduct(id);
        product.setActive(false);
        productRepository.save(product);
        log.info("Product soft-deleted: id={}", id);
    }

    @Transactional
    @PreAuthorize("hasRole('Admin')")
    public Map<String, Object> bulkImportProducts(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InventoryException(
                    "Upload file is empty", HttpStatus.BAD_REQUEST);
        }

        String savedFileName = saveFileToDisk(file);
        log.info("Bulk import file saved: {}", savedFileName);

        int successCount = 0;
        int skipCount = 0;
        List<String> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream()))) {

            String line;
            int lineNumber = 0;


            String firstLine = reader.readLine();
            if (firstLine != null && firstLine.startsWith("name|")) {

            } else if (firstLine != null) {

                lineNumber++;
                processImportLine(firstLine, lineNumber, errors, successCount);
                if (errors.isEmpty()) successCount++;
            }

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) continue; // skip blank lines

                try {
                    String[] parts = line.split("\\|");

                    if (parts.length < 5) {
                        errors.add("Line " + lineNumber + ": expected 5 fields, got " + parts.length);
                        skipCount++;
                        continue;
                    }

                    String sku = parts[1].trim();
                    if (productRepository.existsBySku(sku)) {
                        log.debug("Skipping existing SKU on line {}: {}", lineNumber, sku);
                        skipCount++;
                        continue;

                    }

                    Product product = Product.builder()
                            .name(parts[0].trim())
                            .sku(sku)
                            .description(parts[2].trim())
                            .price(new BigDecimal(parts[3].trim()))
                            .stockQty(Integer.parseInt(parts[4].trim()))
                            .build();

                    productRepository.save(product);
                    successCount++;

                } catch (NumberFormatException e) {
                    errors.add("Line " + lineNumber + ": invalid number format — " + e.getMessage());
                    skipCount++;
                }
            }
        } catch (IOException e) {
            throw new InventoryException(
                    "Failed to read import file: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }


        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", successCount);
        result.put("skipped", skipCount);
        result.put("errors", errors);
        result.put("savedFile", savedFileName);
        return result;
    }

    @Transactional
    public ProductResponse decrementStock(Long id, int quantity) {
        Product product = findActiveProduct(id);

        if (product.getStockQty() < quantity) {
            throw new InventoryException(
                    "Insufficient stock for product '" + product.getName() +
                            "'. Available: " + product.getStockQty() + ", requested: " + quantity,
                    HttpStatus.CONFLICT
            );
        }

        product.setStockQty(product.getStockQty() - quantity);
        return toResponse(productRepository.save(product));
    }


    private Product findActiveProduct(Long id) {
        return productRepository.findById(id)
                .filter(Product::isActive)
                .orElseThrow(() -> new InventoryException(
                        "Product not found with id: " + id,
                        HttpStatus.NOT_FOUND
                ));
    }

    private String saveFileToDisk(MultipartFile file) {
        try {
            // Create the uploads directory if it doesn't exist
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }


            String fileName = System.currentTimeMillis() + "_"
                    + Objects.requireNonNull(file.getOriginalFilename())
                    .replaceAll("[^a-zA-Z0-9._-]", "_");

            Path filePath = uploadPath.resolve(fileName);
            Files.copy(file.getInputStream(), filePath,
                    StandardCopyOption.REPLACE_EXISTING);

            return fileName;
        } catch (IOException e) {
            log.error("Failed to save file to disk: {}", e.getMessage());
            throw new InventoryException(
                    "Could not save uploaded file", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void processImportLine(String line, int lineNumber,
                                   List<String> errors, int successCount) {
    }

    private ProductResponse toResponse(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .sku(product.getSku())
                .description(product.getDescription())
                .price(product.getPrice())
                .stockQty(product.getStockQty())
                .active(product.isActive())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }
}

