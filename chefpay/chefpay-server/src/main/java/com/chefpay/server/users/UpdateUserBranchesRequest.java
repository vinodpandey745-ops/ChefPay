package com.chefpay.server.users;

import java.util.List;
import java.util.UUID;

/** {@code branchIds} empty or null = unrestricted (see {@code AppUser.branches}'s javadoc).
 * {@code defaultBranchId} null = always ask when there's more than one to pick from. */
public record UpdateUserBranchesRequest(List<UUID> branchIds, UUID defaultBranchId, long version) {
}
