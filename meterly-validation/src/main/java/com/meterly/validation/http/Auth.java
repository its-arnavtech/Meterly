package com.meterly.validation.http;

/** How a request authenticates, mirroring docs/api-contract.md §0 ("Auth (users)" / "Auth (machines)"). */
public sealed interface Auth {

    record None() implements Auth {
    }

    record Bearer(String jwt) implements Auth {
    }

    record ApiKey(String key) implements Auth {
    }

    None NONE = new None();

    static Auth bearer(String jwt) {
        return new Bearer(jwt);
    }

    static Auth apiKey(String key) {
        return new ApiKey(key);
    }
}
