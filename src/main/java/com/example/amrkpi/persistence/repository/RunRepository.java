package com.example.amrkpi.persistence.repository;

import com.example.amrkpi.persistence.entity.Run;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RunRepository extends JpaRepository<Run, String> {
    List<Run> findTop50ByOrderByStartedAtDesc();
}
