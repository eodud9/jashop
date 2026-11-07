package com.storeflow.backend.store.infrastructure;

import java.util.Optional;

import com.storeflow.backend.store.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, Long> {

    Optional<Store> findByCode(String code);

    boolean existsByCode(String code);
}
