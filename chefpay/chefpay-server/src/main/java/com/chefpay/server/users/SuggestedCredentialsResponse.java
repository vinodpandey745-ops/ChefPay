package com.chefpay.server.users;

/** Item 9's "Auto Generate" buttons for both the user code and the PIN - purely a suggestion the
 * admin sees and can overwrite before saving (see {@code UserAccountService#generateUserCode}/
 * {@code #generatePin}'s javadocs); nothing is persisted by requesting a suggestion. */
public record SuggestedCredentialsResponse(String userCode, String pin) {
}
