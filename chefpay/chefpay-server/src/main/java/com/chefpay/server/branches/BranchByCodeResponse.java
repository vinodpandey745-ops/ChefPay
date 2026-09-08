package com.chefpay.server.branches;

import java.util.UUID;

/** Public (unauthenticated) response for the POS client's first-run "enter your branch code"
 * screen - deliberately carries nothing beyond what's needed to proceed to Terminal Select, per
 * the design doc's "returns only {branchId, branchName, active}, nothing sensitive" note. */
public record BranchByCodeResponse(UUID branchId, String branchName, boolean active) {
}
