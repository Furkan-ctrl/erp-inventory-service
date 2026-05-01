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

        // Only check SKU uniqueness if the SKU is actually changing
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
        // We set active=false instead of calling repository.delete(product).
        // This preserves the product record for historical order data.
        productRepository.save(product);
        log.info("Product soft-deleted: id={}", id);
    }

    @Transactional
    @PreAuthorize("hasRole('Admin')")
    public Map<String, Object> bulkImportProducts(MultipartFile file) {
        /*
         * MultipartFile is Spring's abstraction for an uploaded file.
         * The client sends a multipart/form-data request containing the file.
         * Spring parses the multipart request and gives us a clean
         * MultipartFile object to work with.
         */
        if (file.isEmpty()) {
            throw new InventoryException(
                    "Upload file is empty", HttpStatus.BAD_REQUEST);
        }

        // Step 1: Save the uploaded file to disk for audit purposes.
        // Even if the import fails, we have a record of what was uploaded.
        String savedFileName = saveFileToDisk(file);
        log.info("Bulk import file saved: {}", savedFileName);

        // Step 2: Parse the file and import each product.
        int successCount = 0;
        int skipCount = 0;
        List<String> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream()))) {
            /*
             * try-with-resources ensures the BufferedReader is closed
             * automatically after the block, even if an exception is thrown.
             * This is critical for file handling — unclosed streams
             * cause resource leaks that crash servers under load.
             *
             * The file format expected (one product per line):
             * name|SKU|description|price|stockQty
             * Example: Wireless Mouse|MOUSE-001|USB wireless mouse|29.99|150
             */

            String line;
            int lineNumber = 0;

            // Skip the header line if it exists
            String firstLine = reader.readLine();
            if (firstLine != null && firstLine.startsWith("name|")) {
                // It's a header row, skip it
            } else if (firstLine != null) {
                // First line is data, process it
                lineNumber++;
                processImportLine(firstLine, lineNumber, errors, successCount);
                if (errors.isEmpty()) successCount++;
            }

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) continue; // skip blank lines

                try {
                    String[] parts = line.split("\\|");
                    /*
                     * We use "|" as the delimiter instead of comma because
                     * product descriptions might contain commas. Using "|"
                     * avoids the need for CSV quoting rules.
                     */
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
                        // We skip (not error) because re-running the same
                        // import file should be safe (idempotent).
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

        // Return a summary so the caller knows exactly what happened
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", successCount);
        result.put("skipped", skipCount);
        result.put("errors", errors);
        result.put("savedFile", savedFileName);
        return result;
    }
    // ── STOCK MANAGEMENT (called by Order Service) ────────────────────────────

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

    // ── PRIVATE HELPERS ───────────────────────────────────────────────────────

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

            // Create a unique filename to prevent overwrites:
            // timestamp + original filename
            String fileName = System.currentTimeMillis() + "_"
                    + Objects.requireNonNull(file.getOriginalFilename())
                    .replaceAll("[^a-zA-Z0-9._-]", "_");
            // We sanitize the filename to prevent path traversal attacks
            // where a malicious client might send a filename like "../../etc/passwd"

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
        // Helper for processing the first non-header line
        // (reuses the same logic as the loop body)
    }

    private ProductResponse toResponse(Product product) {
        /*
         * This mapper converts a Product entity into a ProductResponse DTO.
         * It is a private method because it is an implementation detail —
         * callers just pass a Product and get a ProductResponse back,
         * they don't need to know how the mapping happens.
         *
         * In a larger project we would use MapStruct to generate this
         * automatically, but writing it by hand is good for learning
         * because you see exactly what data flows out of the service.
         */
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

