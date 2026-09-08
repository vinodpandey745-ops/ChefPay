package com.chefpay.core.service;

import com.chefpay.core.domain.NumberSequence;
import com.chefpay.core.repository.NumberSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Concurrency-safe number generation (requirement §57), e.g. {@code ORD-20260814-000123}.
 *
 * <p>The actual increment lives in {@link SequenceIncrementer}, a separate bean, called through
 * Spring's proxy rather than as a same-class self-invocation (which would silently skip the
 * transaction advice entirely - a classic Spring AOP gotcha). It runs with the DEFAULT
 * (REQUIRED) propagation, joining whatever transaction the caller is already in, rather than
 * REQUIRES_NEW.
 *
 * <p>An earlier version used REQUIRES_NEW so the pessimistic lock on the sequence row would be
 * held only for the instant of the increment rather than the whole order-creation transaction.
 * That's a real optimization on Postgres/MySQL, but it forces a second, independent physical
 * connection to write and commit WHILE the caller's own transaction/connection is still open
 * from an earlier read (e.g. OrderService.openOrCreateOrder checks for an existing order before
 * calling here) - on SQLite specifically, that's a structural deadlock: the second connection
 * can't get the exclusive lock it needs to commit while the first connection's read lock is
 * still held, and the first connection can't release that lock until the (synchronous) call to
 * this service returns. REQUIRED avoids the second connection entirely, so there's nothing to
 * deadlock against - at the cost of holding the row lock a little longer on Postgres/MySQL,
 * which is an acceptable trade for correctness here.
 *
 * <p>On SQLite, {@code SELECT ... FOR UPDATE} isn't supported and the dialect no-ops it; SQLite's
 * own single-writer transaction serialization still makes this correct there, just via a coarser
 * mechanism than the row lock Postgres/MySQL get.
 */
@Service
@RequiredArgsConstructor
public class NumberGeneratorService {

    private final SequenceIncrementer sequenceIncrementer;

    public String next(String prefix) {
        // The very first call for a brand-new series (e.g. the first order of the day) can race
        // two concurrent callers into both trying to INSERT the same seed row - the pessimistic
        // lock only protects rows that already exist. A single retry covers that one-time race
        // without complicating the common case where the row already exists.
        //
        // Note: now that increment() joins the caller's ambient transaction (REQUIRED) instead of
        // running in its own REQUIRES_NEW transaction, a DataIntegrityViolationException here can
        // mark that ambient transaction rollback-only under strict JPA semantics, which would make
        // the retry below (and the rest of the caller's work) fail too. This race only occurs on
        // the very first order of a brand-new day/series from two near-simultaneous callers - rare
        // enough, and low-stakes enough (the caller's request fails cleanly and can just be
        // retried by the client), that it's an accepted gap rather than something worth
        // re-introducing the cross-connection deadlock to fully close.
        try {
            return sequenceIncrementer.increment(prefix);
        } catch (DataIntegrityViolationException raceOnFirstInsert) {
            return sequenceIncrementer.increment(prefix);
        }
    }

    /**
     * Same day-scoped series/retry semantics as {@link #next(String)}, but for callers that need a
     * plain numeric ticket/line number (e.g. KOT numbers) rather than a formatted document number
     * like {@code ORD-20260814-000123}.
     */
    public long nextNumeric(String prefix) {
        try {
            return sequenceIncrementer.incrementNumeric(prefix);
        } catch (DataIntegrityViolationException raceOnFirstInsert) {
            return sequenceIncrementer.incrementNumeric(prefix);
        }
    }

    /** Separate bean purely to go through Spring's transactional proxy (see class javadoc). */
    @Service
    @RequiredArgsConstructor
    public static class SequenceIncrementer {

        private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

        private final NumberSequenceRepository sequenceRepository;

        @Transactional
        public String increment(String prefix) {
            String datePart = LocalDate.now().format(DATE_FORMAT);
            String seriesKey = prefix + "-" + datePart;

            NumberSequence sequence = sequenceRepository.findForUpdate(seriesKey)
                    .orElseGet(() -> sequenceRepository.save(new NumberSequence(seriesKey, 0L)));
            long next = sequence.getCurrentValue() + 1;
            sequence.setCurrentValue(next);
            sequenceRepository.save(sequence);

            return "%s-%s-%06d".formatted(prefix, datePart, next);
        }

        /** Numeric counterpart to {@link #increment(String)} - same series/locking, no document formatting. */
        @Transactional
        public long incrementNumeric(String prefix) {
            String datePart = LocalDate.now().format(DATE_FORMAT);
            String seriesKey = prefix + "-" + datePart;

            NumberSequence sequence = sequenceRepository.findForUpdate(seriesKey)
                    .orElseGet(() -> sequenceRepository.save(new NumberSequence(seriesKey, 0L)));
            long next = sequence.getCurrentValue() + 1;
            sequence.setCurrentValue(next);
            sequenceRepository.save(sequence);
            return next;
        }
    }
}
