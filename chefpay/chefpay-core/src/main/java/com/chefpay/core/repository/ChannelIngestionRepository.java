package com.chefpay.core.repository;

import com.chefpay.core.domain.ChannelIngestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChannelIngestionRepository extends JpaRepository<ChannelIngestion, UUID> {

    List<ChannelIngestion> findByEodSessionIdOrderByChannelTypeAsc(UUID eodSessionId);
}
