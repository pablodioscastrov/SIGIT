package com.inventario.multisucursal.branches;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    boolean existsByCode(String code);

    @Query("SELECT b FROM Branch b WHERE (:active IS NULL OR b.active = :active)")
    Page<Branch> search(@Param("active") Boolean active, Pageable pageable);
}
