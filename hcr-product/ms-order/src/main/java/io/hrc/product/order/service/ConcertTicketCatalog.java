package io.hrc.product.order.service;

import io.hrc.product.order.domain.ConcertTicket;
import io.hrc.product.order.repository.ConcertTicketRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache cho ConcertTicket — read-only catalog warm tại startup.
 *
 * <p>Catalog chỉ thay đổi khi seed lại DB (admin operation). Hot path POST /orders không
 * cần hit DB → loại bỏ 1 SELECT/request → giảm Postgres queue dưới spike.
 *
 * <p>Profile Step 1 cho thấy {@code findById} avg 1 270ms (max 4.7s) dưới 1000 RPS, là
 * bottleneck chính của path 202. Sau khi cache, lookup là HashMap O(1) sub-microsecond.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConcertTicketCatalog {

    private final ConcertTicketRepository repository;
    private final Map<String, ConcertTicket> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void warmUp() {
        repository.findAll().forEach(t -> cache.put(t.getResourceId(), t));
        log.info("[catalog-cache] Warmed {} concert tickets into memory: {}",
                cache.size(), cache.keySet());
    }

    public Optional<ConcertTicket> findById(String resourceId) {
        return Optional.ofNullable(cache.get(resourceId));
    }
}
