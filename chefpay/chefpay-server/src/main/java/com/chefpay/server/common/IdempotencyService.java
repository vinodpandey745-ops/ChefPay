package com.chefpay.server.common;

import com.chefpay.core.domain.IdempotencyRecord;
import com.chefpay.core.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Generic idempotent-operation wrapper (requirement §29/§51): the first call for a given
 * {@code (operation, key)} pair runs {@code action} and caches its result; every later call with
 * the same key replays the cached result instead of re-running {@code action}. This is what
 * makes "double-click PAY" / a client retry after a dropped response / a WebSocket-reconnect
 * resubmit safe rather than a duplicate order or duplicate payment.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public <T> T execute(String operation, String idempotencyKey, Class<T> resultType, Supplier<T> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }
        String compositeKey = operation + ":" + idempotencyKey;
        Optional<IdempotencyRecord> existing = repository.findById(compositeKey);
        if (existing.isPresent()) {
            return deserialize(existing.get().getResultJson(), resultType);
        }

        T result = action.get();
        try {
            repository.save(IdempotencyRecord.builder()
                    .compositeKey(compositeKey)
                    .operation(operation)
                    .resultJson(objectMapper.writeValueAsString(result))
                    .build());
        } catch (Exception e) {
            throw new ApiException("IDEMPOTENCY_STORE_FAILED", "Could not record idempotent result", org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return result;
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new ApiException("IDEMPOTENCY_REPLAY_FAILED", "Could not replay cached result", org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
