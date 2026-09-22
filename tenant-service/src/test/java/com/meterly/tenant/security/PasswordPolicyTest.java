package com.meterly.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

    private record Account(@ValidPassword String password) {
    }

    @ParameterizedTest
    @ValueSource(strings = {"0123456789", "Meterly!2026"})
    void acceptsPasswordsInsideThePolicy(String password) {
        assertThat(PasswordPolicy.isAcceptable(password)).isTrue();
    }

    @Test
    void rejectsPasswordsOutsideTenToSeventyTwoBytes() {
        assertThat(PasswordPolicy.isAcceptable(null)).isFalse();
        assertThat(PasswordPolicy.isAcceptable("")).isFalse();
        assertThat(PasswordPolicy.isAcceptable("012345678")).isFalse();
        assertThat(PasswordPolicy.isAcceptable("a".repeat(72))).isTrue();
        assertThat(PasswordPolicy.isAcceptable("a".repeat(73))).isFalse();
    }

    @Test
    void measuresUtf8BytesRatherThanCharacters() {
        // Each emoji is 2 chars and 4 UTF-8 bytes, so 19 of them exceed BCrypt's 72-byte input.
        String nineEmoji = "🔐".repeat(9);
        String nineteenEmoji = "🔐".repeat(19);

        assertThat(nineEmoji).hasSize(18);
        assertThat(PasswordPolicy.utf8Length(nineEmoji)).isEqualTo(36);
        assertThat(PasswordPolicy.isAcceptable(nineEmoji)).isTrue();
        assertThat(PasswordPolicy.utf8Length(nineteenEmoji)).isEqualTo(76);
        assertThat(PasswordPolicy.isAcceptable(nineteenEmoji)).isFalse();
    }

    @Test
    void theBeanValidationConstraintUsesTheSameRule() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new Account("Meterly!2026"))).isEmpty();
            Set<ConstraintViolation<Account>> violations = validator.validate(new Account("short"));
            assertThat(violations).singleElement().satisfies(violation -> {
                assertThat(violation.getPropertyPath()).hasToString("password");
                assertThat(violation.getMessage()).isEqualTo(PasswordPolicy.MESSAGE);
            });
        }
    }
}
