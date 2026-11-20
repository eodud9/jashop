package com.jashop.backend.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import com.jashop.backend.catalog.api.dto.*;
import com.jashop.backend.catalog.domain.*;
import com.jashop.backend.catalog.infrastructure.*;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.user.application.UserAuthorizationService;
import jakarta.validation.Validator;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogService {
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final UserAuthorizationService authorization;
    private final Validator validator;
    private final Clock clock;

    public CatalogService(ProductRepository products, ProductVariantRepository variants,
            UserAuthorizationService authorization, Validator validator, Clock clock) {
        this.products = products;
        this.variants = variants;
        this.authorization = authorization;
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional
    public ProductCreatedResponse create(Long actor, CreateProductRequest request) {
        authorization.requireManager(actor);
        validate(request);
        var first = request.firstVariant();
        requireUnusedSku(first.sku());
        Instant now = Instant.now(clock);
        var product = products.saveAndFlush(Product.create(request.name(), request.category(), request.description(), now));
        var variant = saveVariant(ProductVariant.create(product.getId(), first.sku(), first.size(), first.color(), now));
        return new ProductCreatedResponse(ProductResponse.from(product), ProductVariantResponse.from(variant, product));
    }

    @Transactional
    public ProductResponse update(Long actor, Long id, UpdateProductRequest request) {
        authorization.requireManager(actor);
        validate(request);
        var product = lockProduct(id);
        product.change(request.name(), request.category(), request.description(), request.active(), Instant.now(clock));
        return ProductResponse.from(products.saveAndFlush(product));
    }

    @Transactional
    public ProductVariantResponse addVariant(Long actor, Long productId, CreateVariantRequest request) {
        authorization.requireManager(actor);
        validate(request);
        var product = lockProduct(productId);
        product.requireActive();
        requireUnusedSku(request.sku());
        return ProductVariantResponse.from(saveVariant(ProductVariant.create(productId, request.sku(), request.size(), request.color(), Instant.now(clock))), product);
    }

    @Transactional
    public ProductVariantResponse updateVariant(Long actor, Long id, UpdateVariantRequest request) {
        authorization.requireManager(actor);
        validate(request);
        Long parent = variants.findProductId(validId(id)).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_VARIANT_NOT_FOUND));
        // 항상 Product → ProductVariant. 사전 조회는 불변 부모 ID만 읽는다.
        var product = lockProduct(parent);
        var variant = variants.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_VARIANT_NOT_FOUND));
        variant.change(request.size(), request.color(), request.active(), Instant.now(clock));
        return ProductVariantResponse.from(saveVariant(variant), product);
    }

    public ProductPageResponse list(Long actor, String keyword, boolean includeInactive, int page, int size) {
        requireReader(actor, includeInactive);
        String term = CatalogText.optional(keyword);
        CatalogText.bounded(term, 200);
        return ProductPageResponse.from(products.search(pattern(term == null ? null : term.toLowerCase(Locale.ROOT)),
                pattern(CatalogText.normalizeSku(term)), includeInactive, page(page, size)));
    }

    public ProductDetailResponse get(Long actor, Long id, boolean includeInactive, int variantPage, int variantSize) {
        requireReader(actor, includeInactive);
        var product = readProduct(id, includeInactive);
        return new ProductDetailResponse(ProductResponse.from(product),
                VariantPageResponse.from(variants.findPage(id, includeInactive, page(variantPage, variantSize)), product));
    }

    public VariantPageResponse listVariants(Long actor, Long productId, boolean includeInactive, int page, int size) {
        requireReader(actor, includeInactive);
        var product = readProduct(productId, includeInactive);
        return VariantPageResponse.from(variants.findPage(productId, includeInactive, page(page, size)), product);
    }

    public ProductVariantResponse getVariant(Long actor, Long id, boolean includeInactive) {
        requireReader(actor, includeInactive);
        var variant = variants.findById(validId(id)).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_VARIANT_NOT_FOUND));
        var product = products.findById(variant.getProductId()).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (!includeInactive && !variant.effectiveActive(product)) throw new BusinessException(ErrorCode.PRODUCT_VARIANT_NOT_FOUND);
        return ProductVariantResponse.from(variant, product);
    }

    private void requireReader(Long actor, boolean includeInactive) {
        if (includeInactive) authorization.requireManager(actor);
        else authorization.requireActive(actor);
    }
    private Product readProduct(Long id, boolean includeInactive) {
        var product = products.findById(validId(id)).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (!includeInactive && !product.isActive()) throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        return product;
    }
    private Product lockProduct(Long id) {
        return products.findByIdForUpdate(validId(id)).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }
    private void requireUnusedSku(String sku) {
        if (variants.existsBySku(CatalogText.normalizeSku(sku))) throw new BusinessException(ErrorCode.DUPLICATE_SKU);
    }
    private ProductVariant saveVariant(ProductVariant variant) {
        try { return variants.saveAndFlush(variant); }
        catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null
                        && (violation.getConstraintName().equals("uk_product_variants_sku")
                        || violation.getConstraintName().endsWith(".uk_product_variants_sku"))) {
                    throw new BusinessException(ErrorCode.DUPLICATE_SKU);
                }
            }
            throw exception;
        }
    }
    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
    private Long validId(Long id) {
        if (id == null || id <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return id;
    }
    private PageRequest page(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return PageRequest.of(page, size, Sort.by("id"));
    }
    private String pattern(String text) {
        return text == null ? null : "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
