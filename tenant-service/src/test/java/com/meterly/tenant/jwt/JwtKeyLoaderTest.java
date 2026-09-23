package com.meterly.tenant.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterly.tenant.config.TenantProperties;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JwtKeyLoaderTest {

    @TempDir
    Path keyDirectory;

    private static TenantProperties.Jwt config(String privatePath, String publicPath, String keyId,
                                               List<TenantProperties.PublicKeyFile> additional) {
        return new TenantProperties.Jwt(privatePath, publicPath, keyId, additional, null, Duration.ofMinutes(15));
    }

    @Test
    void loadsAPkcs8PrivateKeyAndDerivesItsPublicHalf() throws IOException {
        KeyPair pair = TestKeys.generate(2048);
        Path privateKey = TestKeys.writePrivateKey(keyDirectory, "jwt-private.pem", pair);

        JwtKeyMaterial keys = JwtKeyLoader.load(config(privateKey.toString(), null, "local-rsa-1", List.of()), false);

        assertThat(keys.ephemeral()).isFalse();
        assertThat(keys.signingKeyId()).isEqualTo("local-rsa-1");
        assertThat(keys.signingPublicKey().getEncoded()).isEqualTo(pair.getPublic().getEncoded());
        assertThat(keys.jwksDocument()).containsKey("keys");
    }

    @Test
    void acceptsAMatchingPublicKeyFileAndRejectsAForeignOne() throws IOException {
        KeyPair pair = TestKeys.generate(2048);
        Path privateKey = TestKeys.writePrivateKey(keyDirectory, "jwt-private.pem", pair);
        Path publicKey = TestKeys.writePublicKey(keyDirectory, "jwt-public.pem", pair);
        Path foreignKey = TestKeys.writePublicKey(keyDirectory, "other-public.pem", TestKeys.generate(2048));

        assertThat(JwtKeyLoader.load(config(privateKey.toString(), publicKey.toString(), null, List.of()), false)
                .signingKeyId()).isNotBlank();

        assertThatThrownBy(() -> JwtKeyLoader.load(
                config(privateKey.toString(), foreignKey.toString(), null, List.of()), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not belong to the configured private key");
    }

    @Test
    void derivesTheKeyIdFromTheThumbprintWhenNoneIsConfigured() throws IOException {
        KeyPair pair = TestKeys.generate(2048);
        Path privateKey = TestKeys.writePrivateKey(keyDirectory, "jwt-private.pem", pair);

        JwtKeyMaterial first = JwtKeyLoader.load(config(privateKey.toString(), null, "  ", List.of()), false);
        JwtKeyMaterial second = JwtKeyLoader.load(config(privateKey.toString(), null, null, List.of()), false);

        assertThat(first.signingKeyId()).isEqualTo(second.signingKeyId()).hasSizeGreaterThan(20);
    }

    @Test
    void publishesAdditionalPublicKeysForRotation() throws IOException {
        KeyPair current = TestKeys.generate(2048);
        KeyPair retired = TestKeys.generate(2048);
        Path privateKey = TestKeys.writePrivateKey(keyDirectory, "jwt-private.pem", current);
        Path retiredPublic = TestKeys.writePublicKey(keyDirectory, "retired-public.pem", retired);

        JwtKeyMaterial keys = JwtKeyLoader.load(config(privateKey.toString(), null, "current",
                List.of(new TenantProperties.PublicKeyFile(retiredPublic.toString(), "retired"))), false);

        assertThat(keys.verificationKeys()).extracting(RSAKey::getKeyID).containsExactly("current", "retired");
        assertThat(keys.publicJwkSet().getKeys()).allSatisfy(key -> assertThat(key.isPrivate()).isFalse());
    }

    @Test
    void generatesAnEphemeralKeyOnlyWhenItIsAllowed() {
        TenantProperties.Jwt noKeys = config(null, null, null, List.of());

        assertThatThrownBy(() -> JwtKeyLoader.load(noKeys, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY_PATH");

        JwtKeyMaterial ephemeral = JwtKeyLoader.load(noKeys, true);
        assertThat(ephemeral.ephemeral()).isTrue();
        assertThat(ephemeral.signingKey().size()).isGreaterThanOrEqualTo(JwtKeyMaterial.MINIMUM_KEY_BITS);
        assertThat(ephemeral.signingKeyId()).isNotBlank();
    }

    @Test
    void rejectsAPublicKeyPathWithoutAPrivateKey() throws IOException {
        Path publicKey = TestKeys.writePublicKey(keyDirectory, "jwt-public.pem", TestKeys.generate(2048));

        assertThatThrownBy(() -> JwtKeyLoader.load(config(null, publicKey.toString(), null, List.of()), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a private key");
    }

    @Test
    void explainsHowToConvertAPkcs1KeyAndRejectsGarbage() throws IOException {
        Path pkcs1 = TestKeys.write(keyDirectory, "pkcs1.pem", "RSA PRIVATE KEY", new byte[] {1, 2, 3});
        Path garbage = keyDirectory.resolve("garbage.pem");
        Files.writeString(garbage, "not a key at all");

        assertThatThrownBy(() -> JwtKeyLoader.load(config(pkcs1.toString(), null, null, List.of()), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl pkcs8 -topk8");
        assertThatThrownBy(() -> JwtKeyLoader.load(config(garbage.toString(), null, null, List.of()), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not contain a PEM block");
    }

    @Test
    void rejectsKeysShorterThanTheMinimum() throws IOException {
        Path weakKey = TestKeys.writePrivateKey(keyDirectory, "weak.pem", TestKeys.generate(1024));

        assertThatThrownBy(() -> JwtKeyLoader.load(config(weakKey.toString(), null, "weak", List.of()), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shorter than 2048 bits");
    }
}
