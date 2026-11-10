package com.jashop.backend.store.api;

import java.net.URI;

import com.jashop.backend.global.security.UserPrincipal;
import com.jashop.backend.store.api.dto.CreateStoreRequest;
import com.jashop.backend.store.api.dto.StorePageResponse;
import com.jashop.backend.store.api.dto.StoreResponse;
import com.jashop.backend.store.api.dto.UpdateStoreRequest;
import com.jashop.backend.store.application.StoreService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/stores", produces = MediaType.APPLICATION_JSON_VALUE)
public class StoreController {
    private final StoreService stores;

    public StoreController(StoreService stores) {
        this.stores = stores;
    }

    @GetMapping
    public StorePageResponse list(Authentication authentication,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "true") boolean active) {
        return stores.list(UserPrincipal.requireUserId(authentication), page, size, active);
    }

    @GetMapping("/{id}")
    public StoreResponse get(Authentication authentication, @PathVariable @Positive Long id) {
        return stores.get(UserPrincipal.requireUserId(authentication), id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StoreResponse> create(Authentication authentication, @Valid @RequestBody CreateStoreRequest request) {
        StoreResponse response = stores.create(UserPrincipal.requireUserId(authentication), request);
        return ResponseEntity.created(URI.create("/api/v1/stores/" + response.id())).body(response);
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StoreResponse rename(Authentication authentication, @PathVariable @Positive Long id,
            @Valid @RequestBody UpdateStoreRequest request) {
        return stores.rename(UserPrincipal.requireUserId(authentication), id, request);
    }

    @PatchMapping("/{id}/deactivate")
    public StoreResponse deactivate(Authentication authentication, @PathVariable @Positive Long id) {
        return stores.deactivate(UserPrincipal.requireUserId(authentication), id);
    }
}
