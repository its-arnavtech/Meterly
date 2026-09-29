package com.meterly.notification.email;

/** Log-safe rendering of email addresses. */
public final class EmailAddresses {

    private EmailAddresses() {
    }

    /**
     * Keeps the first character of the local part and the domain: {@code admin@acme.test} becomes
     * {@code a***@acme.test}. Anything that does not look like an address is fully masked.
     */
    public static String redact(String address) {
        if (address == null) {
            return "***";
        }
        int at = address.lastIndexOf('@');
        if (at <= 0 || at == address.length() - 1) {
            return "***";
        }
        return address.charAt(0) + "***" + address.substring(at);
    }
}
