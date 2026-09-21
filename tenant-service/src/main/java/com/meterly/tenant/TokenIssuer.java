package com.meterly.tenant;

import com.meterly.common.autoconfigure.MeterlyProperties;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class TokenIssuer {
    private final RSAKey key;
    private final Clock clock;
    private final MeterlyProperties properties;
    public TokenIssuer(Environment env, Clock clock, MeterlyProperties properties) {
        this.clock=clock;
        this.properties=properties;
        String privatePath=env.getProperty("meterly.auth.private-key-path", "");
        String publicPath=env.getProperty("meterly.auth.public-key-path", "");
        boolean prod=env.matchesProfiles("prod");
        if (prod && (privatePath.isBlank() || publicPath.isBlank()))
            throw new IllegalStateException("Production requires RSA private/public key files");
        if (prod && !env.getProperty("meterly.auth.refresh-cookie-secure",Boolean.class,false))
            throw new IllegalStateException("Production requires secure refresh cookies");
        if (prod && (properties.security().internalToken()==null
                || properties.security().internalToken().length()<32
                || properties.security().internalToken().startsWith("dev-")))
            throw new IllegalStateException("Production requires a strong internal authentication token");
        try {
            RSAPublicKey pub;
            RSAPrivateKey priv;
            if (privatePath.isBlank() && publicPath.isBlank()) {
                KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");
                generator.initialize(3072);
                KeyPair pair=generator.generateKeyPair();
                pub=(RSAPublicKey)pair.getPublic(); priv=(RSAPrivateKey)pair.getPrivate();
            } else {
                KeyFactory factory=KeyFactory.getInstance("RSA");
                priv=(RSAPrivateKey)factory.generatePrivate(new PKCS8EncodedKeySpec(pem(privatePath)));
                pub=(RSAPublicKey)factory.generatePublic(new X509EncodedKeySpec(pem(publicPath)));
                if (!pub.getModulus().equals(priv.getModulus()) || pub.getModulus().bitLength()<2048)
                    throw new IllegalArgumentException("RSA pair must match and be at least 2048 bits");
            }
            String kid=env.getProperty("meterly.auth.key-id", "");
            if (kid.isBlank()) kid=Secrets.hash(Base64.getEncoder().encodeToString(pub.getEncoded())).substring(0,16);
            key=new RSAKey.Builder(pub).privateKey(priv).keyID(kid).algorithm(JWSAlgorithm.RS256)
                    .keyUse(KeyUse.SIGNATURE).build();
        } catch (Exception ex) { throw new IllegalStateException("Unable to initialize RSA signing key",ex); }
    }
    private static byte[] pem(String path) throws Exception {
        return Base64.getDecoder().decode(Files.readString(Path.of(path))
                .replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
    }
    RSAPublicKey publicKey() {
        try { return key.toRSAPublicKey(); } catch (JOSEException ex) { throw new IllegalStateException(ex); }
    }
    public Map<String,Object> jwks() { return new JWKSet(key.toPublicJWK()).toJSONObject(); }
    public String issue(Map<String,Object> user) {
        Instant now=clock.instant();
        JWTClaimsSet claims=new JWTClaimsSet.Builder().issuer(properties.security().jwt().issuer())
                .audience(properties.security().jwt().audience()).subject(user.get("id").toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(900)))
                .jwtID(UUID.randomUUID().toString()).claim("tenant_id",user.get("tenant_id").toString())
                .claim("email",user.get("email")).claim("role",user.get("role")).build();
        SignedJWT jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                .type(JOSEObjectType.JWT).build(),claims);
        try { jwt.sign(new RSASSASigner(key)); return jwt.serialize(); }
        catch (JOSEException ex) { throw new IllegalStateException("Token signing failed",ex); }
    }
}
