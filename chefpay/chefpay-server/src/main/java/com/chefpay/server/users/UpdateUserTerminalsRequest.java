package com.chefpay.server.users;

import java.util.List;
import java.util.UUID;

/** {@code terminalIds} empty or null = unrestricted (see {@code AppUser.terminals}'s javadoc) -
 * same convention as {@code UpdateUserBranchesRequest}, one level deeper (item 14 of the request). */
public record UpdateUserTerminalsRequest(List<UUID> terminalIds, long version) {
}
