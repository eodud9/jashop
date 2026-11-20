package com.jashop.backend.catalog.infrastructure;

import java.util.Optional;
import com.jashop.backend.catalog.domain.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select p from Product p where (:includeInactive = true or p.active = true)
            and (:pattern is null or lower(p.name) like :pattern escape '!'
                or exists (select v.id from ProductVariant v where v.productId = p.id
                    and (:includeInactive = true or v.active = true)
                    and (v.sku like :skuPattern escape '!' or lower(v.size) like :pattern escape '!'
                        or lower(v.color) like :pattern escape '!')))
            """)
    Page<Product> search(@Param("pattern") String pattern, @Param("skuPattern") String skuPattern,
            @Param("includeInactive") boolean includeInactive, Pageable page);
}
