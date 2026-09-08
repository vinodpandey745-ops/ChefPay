package com.chefpay.core.repository;

import com.chefpay.core.domain.FraudRuleCode;
import com.chefpay.core.domain.RuleConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuleConfigRepository extends JpaRepository<RuleConfig, UUID> {

    Optional<RuleConfig> findByRuleCode(FraudRuleCode ruleCode);

    List<RuleConfig> findAllByOrderByRuleCodeAsc();
}
