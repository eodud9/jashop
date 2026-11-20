package com.jashop.backend.catalog.api;

import java.net.URI;
import com.jashop.backend.catalog.api.dto.*;
import com.jashop.backend.catalog.application.CatalogService;
import com.jashop.backend.global.security.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/products", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProductController {
    private final CatalogService catalog;
    public ProductController(CatalogService catalog) { this.catalog = catalog; }
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProductCreatedResponse> create(Authentication auth, @Valid @RequestBody CreateProductRequest request) {
        var response = catalog.create(UserPrincipal.requireUserId(auth), request);
        return ResponseEntity.created(URI.create("/api/v1/products/" + response.product().id())).body(response);
    }
    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProductResponse update(Authentication auth, @PathVariable @Positive Long id, @Valid @RequestBody UpdateProductRequest request) {
        return catalog.update(UserPrincipal.requireUserId(auth), id, request);
    }
    @GetMapping
    public ProductPageResponse list(Authentication auth, @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return catalog.list(UserPrincipal.requireUserId(auth), keyword, includeInactive, page, size);
    }
    @GetMapping("/{id}")
    public ProductDetailResponse get(Authentication auth, @PathVariable @Positive Long id,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int variantPage, @RequestParam(defaultValue = "20") int variantSize) {
        return catalog.get(UserPrincipal.requireUserId(auth), id, includeInactive, variantPage, variantSize);
    }
    @PostMapping(value = "/{id}/variants", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProductVariantResponse> addVariant(Authentication auth, @PathVariable @Positive Long id,
            @Valid @RequestBody CreateVariantRequest request) {
        var response = catalog.addVariant(UserPrincipal.requireUserId(auth), id, request);
        return ResponseEntity.created(URI.create("/api/v1/product-variants/" + response.id())).body(response);
    }
    @GetMapping("/{id}/variants")
    public VariantPageResponse variants(Authentication auth, @PathVariable @Positive Long id,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return catalog.listVariants(UserPrincipal.requireUserId(auth), id, includeInactive, page, size);
    }
}
