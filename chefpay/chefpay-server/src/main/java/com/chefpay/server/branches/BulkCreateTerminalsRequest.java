package com.chefpay.server.branches;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Item 7's "how many terminals do you want?" prompt - creates {@code count} terminals in one call,
 * sequentially numbered continuing from this branch's current highest {@code sequence_no} (never
 * restarting at 1 if some already exist - see {@code BranchController#bulkCreateTerminals}).
 * {@code namePrefix} is optional display-name cosmetics only ("Counter" -> "Counter 001",
 * "Counter 002"...); the number itself always comes from {@code Device#sequenceNo}, never parsed
 * back out of the name. Capped at 50 in one call - a restaurant genuinely needing more can call
 * this again, and it keeps one fat-fingered request from creating hundreds of stray rows. */
public record BulkCreateTerminalsRequest(
        @Min(1) @Max(50) int count,
        String namePrefix
) {
}
