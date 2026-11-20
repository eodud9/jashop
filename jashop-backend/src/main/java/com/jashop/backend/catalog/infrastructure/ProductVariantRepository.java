package com.jashop.backend.catalog.infrastructure;

import java.util.Optional;
import com.jashop.backend.catalog.domain.ProductVariant;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {
    boolean existsBySku(String sku);
    @Query("select v.productId from ProductVariant v where v.id = :id")
    Optional<Long> findProductId(@Param("id") Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProductVariant v where v.id = :id")
    Optional<ProductVariant> findByIdForUpdate(@Param("id") Long id);
    @Query("select v from ProductVariant v where v.productId = :productId and (:includeInactive = true or v.active = true)")
    Page<ProductVariant> findPage(@Param("productId") Long productId, @Param("includeInactive") boolean includeInactive, Pageable page);
}
