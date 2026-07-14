package com.example.amrkpi.persistence.repository;

import com.example.amrkpi.persistence.entity.SourceOfTruthRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceOfTruthRepository extends JpaRepository<SourceOfTruthRecord, String> {
}
