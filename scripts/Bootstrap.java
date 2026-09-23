import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Generates the local-only secrets the Docker Compose stack needs: {@code .env} (database and
 * internal-token secrets) and {@code secrets/jwt-private.pem} + {@code secrets/jwt-public.pem}
 * (the RS256 key pair tenant-service signs access tokens with). All three are gitignored.
 *
 * <p>Runs with nothing but a JDK (single-file source launch, Java 11+):
 * <pre>{@code java scripts/Bootstrap.java}</pre>
 * Idempotent: an existing {@code .env} is never overwritten, and it refuses to mint new keys next to
 * an existing configuration so a running stack's tokens are never silently invalidated.
 */
public final class Bootstrap {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path env = root.resolve(".env");
        Path secrets = root.resolve("secrets");
        Path privateKey = secrets.resolve("jwt-private.pem");
        Path publicKey = secrets.resolve("jwt-public.pem");

        if (Files.exists(env)) {
            System.out.println("Existing .env preserved. Start with: docker compose up -d --build");
            return;
        }
        if (Files.exists(privateKey) || Files.exists(publicKey)) {
            throw new IllegalStateException("Key files exist without .env: restore the configuration or move "
                    + "secrets/ aside before bootstrapping.");
        }

        Files.createDirectories(secrets);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072, RANDOM);
        KeyPair pair = generator.generateKeyPair();
        Files.writeString(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
        Files.writeString(publicKey, pem("PUBLIC KEY", pair.getPublic().getEncoded()), StandardCharsets.US_ASCII);

        List<String> lines = List.of(
                "COMPOSE_PROJECT_NAME=meterly",
                "POSTGRES_PASSWORD=" + secret(),
                "DB_OWNER_PASSWORD=" + secret(),
                "DB_APP_PASSWORD=" + secret(),
                "INTERNAL_TOKEN=" + secret(),
                "WEB_PORT=8080",
                "POSTGRES_PORT=55432",
                "REDIS_PORT=16379",
                "KAFKA_PORT=19092");
        Files.write(env, lines, StandardCharsets.UTF_8);
        System.out.println("Created .env and secrets/jwt-*.pem (gitignored). Start with: docker compose up -d --build");
    }

    /** 256 bits of entropy, lower-case hex: long enough for the production configuration guard. */
    private static String secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** PKCS#8 / SubjectPublicKeyInfo DER wrapped as PEM with 64-character lines. */
    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
