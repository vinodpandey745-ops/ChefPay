package com.chefpay.core.service;

import com.chefpay.core.domain.AuditLog;
import com.chefpay.core.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Walks {@link AuditLog}'s hash chain and reports PASS/FAIL (AI Backbone Addendum F1.3
 * acceptance criterion: "a background integrity job can walk the hash chain for a given date
 * range and report PASS/FAIL; a broken chain raises a Level-3 alert").
 *
 * <p>Always verifies the WHOLE chain from its first hashed row (a break anywhere invalidates
 * every entry after it, so a "just this date range" check could otherwise report PASS on rows
 * that only look fine in isolation) but only lists broken entries that additionally fall inside
 * the caller's requested range, so the report stays focused on what the caller asked about.
 */
@Service
@RequiredArgsConstructor
public class AuditIntegrityService {

    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public IntegrityReport verifyChain(LocalDateTime from, LocalDateTime to) {
        List<AuditLog> chain = auditLogRepository.findByChainSeqIsNotNullOrderByChainSeqAsc();
        List<BrokenLink> broken = new ArrayList<>();
        String expectedPrevHash = "0".repeat(64);
        long expectedSeq = 1;

        for (AuditLog entry : chain) {
            boolean seqOk = entry.getChainSeq() != null && entry.getChainSeq() == expectedSeq;
            boolean prevOk = expectedPrevHash.equals(entry.getPrevHash());
            String recomputedHash = recomputeHash(entry, expectedPrevHash);
            boolean hashOk = recomputedHash.equals(entry.getEntryHash());

            if (!seqOk || !prevOk || !hashOk) {
                boolean inRange = !entry.getTimestamp().isBefore(from) && !entry.getTimestamp().isAfter(to);
                broken.add(new BrokenLink(entry.getId(), entry.getChainSeq(), entry.getTimestamp(),
                        inRange, describeFailure(seqOk, prevOk, hashOk)));
                // Once a link is broken, every subsequent entry's "expected previous" is unknowable
                // from this entry alone - keep walking (to report every independently-broken link,
                // not just the first), but re-anchor on the store's own recorded values rather than
                // cascading one failure into a false failure for the entire rest of the chain.
            }
            expectedPrevHash = entry.getEntryHash() == null ? expectedPrevHash : entry.getEntryHash();
            expectedSeq = (entry.getChainSeq() == null ? expectedSeq : entry.getChainSeq()) + 1;
        }

        List<BrokenLink> inRangeBroken = broken.stream().filter(BrokenLink::inRequestedRange).toList();
        boolean pass = broken.isEmpty();
        return new IntegrityReport(pass, chain.size(), broken.size(), inRangeBroken, from, to);
    }

    private String describeFailure(boolean seqOk, boolean prevOk, boolean hashOk) {
        List<String> problems = new ArrayList<>();
        if (!seqOk) problems.add("sequence gap");
        if (!prevOk) problems.add("previous-hash mismatch");
        if (!hashOk) problems.add("entry hash does not match its recorded content");
        return String.join("; ", problems);
    }

    private String recomputeHash(AuditLog entry, String prevHash) {
        String sep = "";
        String payload = String.join(sep,
                str(entry.getUserId()), str(entry.getDeviceId()), str(entry.getEntityType()), str(entry.getEntityId()),
                str(entry.getAction()), str(entry.getOldValue()), str(entry.getNewValue()), str(entry.getReason()),
                str(entry.getCorrelationId()), str(entry.getTimestamp()));
        return sha256Hex(prevHash + payload);
    }

    private String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record BrokenLink(UUID auditLogId, Long chainSeq, LocalDateTime timestamp, boolean inRequestedRange,
                              String problem) {
    }

    public record IntegrityReport(boolean pass, int totalHashedEntries, int totalBrokenLinks,
                                   List<BrokenLink> brokenLinksInRange, LocalDateTime from, LocalDateTime to) {
    }
}
