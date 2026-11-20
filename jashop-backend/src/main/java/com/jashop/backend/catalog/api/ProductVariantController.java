package com.jashop.backend.catalog.api;

import com.jashop.backend.catalog.api.dto.*;
import com.jashop.backend.catalog.application.CatalogService;
import com.jashop.backend.global.security.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/product-variants", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProductVariantController {
    private final CatalogService catalog;
    public ProductVariantController(CatalogService catalog) { this.catalog = catalog; }
    @GetMapping("/{id}")
    public ProductVariantResponse get(Authentication auth, @PathVariable @Positive Long id,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return catalog.getVariant(UserPrincipal.requireUserId(auth), id, includeInactive);
    }
    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProductVariantResponse update(Authentication auth, @PathVariable @Positive Long id, @Valid @RequestBody UpdateVariantRequest request) {
        return catalog.updateVariant(UserPrincipal.requireUserId(auth), id, request);
    }
}
