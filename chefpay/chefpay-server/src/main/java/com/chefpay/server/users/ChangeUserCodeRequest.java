package com.chefpay.server.users;

import jakarta.validation.constraints.NotBlank;

public record ChangeUserCodeRequest(@NotBlank String newUserCode, long version) {
}
