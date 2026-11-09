package com.jashop.backend.store.infrastructure;

import java.util.Optional;

import com.jashop.backend.store.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, Long> {

    Optional<Store> findByCode(String code);

    boolean existsByCode(String code);
}
