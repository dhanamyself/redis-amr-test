package com.example.amrkpi.persistence.repository;

import com.example.amrkpi.persistence.entity.EventCategory;
import com.example.amrkpi.persistence.entity.RawEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface RawEventRepository extends JpaRepository<RawEvent, Long> {

    List<RawEvent> findByCategoryAndTimestampBetweenOrderByTimestampAsc(
            EventCategory category, Instant from, Instant to);

    List<RawEvent> findByCategoryAndRegionAndTimestampBetweenOrderByTimestampAsc(
            EventCategory category, String region, Instant from, Instant to);

    List<RawEvent> findByRunIdAndCategoryOrderByTimestampAsc(String runId, EventCategory category);

    List<RawEvent> findTop200ByCategoryOrderByTimestampDesc(EventCategory category);
}
