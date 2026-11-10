package com.validexia.support;

import com.nimbusds.jose.jwk.RSAKey;
import com.validexia.platform.security.ClerkJwksProvider;
import java.net.URI;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Shared test Clerk wiring: one RSA keypair for the whole suite; the JWKS "endpoint" is a stub HTTP
 * client serving that key's public JWK. Flow tests mint tokens with {@link #key()}.
 */
@TestConfiguration
public class TestClerkConfig {

  private static final RSAKey KEY = TestTokens.generateKey(TestTokens.KID);

  public static RSAKey key() {
    return KEY;
  }

  @Bean
  @Primary
  public ClerkJwksProvider testJwksProvider() {
    String jwks = TestTokens.jwksJson(KEY.toPublicJWK());
    StubHttpClient http = new StubHttpClient(request -> new StubHttpClient.StubResponse(200, jwks));
    return new ClerkJwksProvider(
        URI.create("https://clerk.test.example/.well-known/jwks.json"), 0, http);
  }
}
