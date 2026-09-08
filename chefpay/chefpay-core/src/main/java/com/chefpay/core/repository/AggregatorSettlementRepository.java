package com.chefpay.core.repository;

import com.chefpay.core.domain.AggregatorSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AggregatorSettlementRepository extends JpaRepository<AggregatorSettlement, UUID> {

    List<AggregatorSettlement> findByEodSessionId(UUID eodSessionId);

    Optional<AggregatorSettlement> findByChannelIngestionId(UUID channelIngestionId);
}
