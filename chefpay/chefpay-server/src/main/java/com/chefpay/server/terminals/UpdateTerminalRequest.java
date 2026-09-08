package com.chefpay.server.terminals;

import java.util.UUID;

/** Null leaves that field unchanged, same convention as every other update-request record in this
 * codebase (see {@code UpdateRestaurantRequest}'s javadoc). {@code branchId} reassigns which
 * branch this terminal belongs to; there is no "clear the branch" value here deliberately -
 * unassigning a terminal from every branch isn't a real workflow this screen needs to support. */
public record UpdateTerminalRequest(
        String name,
        UUID branchId,
        Boolean active,
        long version
) {
}
