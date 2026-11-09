package com.validexia.support;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * An {@link HttpClient} stub that answers from a handler function — lets security tests exercise
 * the JWKS provider and the Clerk Backend client with NO live network (and no loopback sockets).
 */
public class StubHttpClient extends HttpClient {

  private final Function<HttpRequest, StubResponse> handler;
  private final AtomicInteger callCount = new AtomicInteger();

  public StubHttpClient(Function<HttpRequest, StubResponse> handler) {
    this.handler = handler;
  }

  public int callCount() {
    return callCount.get();
  }

  public record StubResponse(int status, String body) {}

  @Override
  @SuppressWarnings("unchecked")
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
      throws IOException {
    callCount.incrementAndGet();
    StubResponse stub = handler.apply(request);
    // All production call sites use BodyHandlers.ofString()/discarding(); the stub body is the
    // string payload either way.
    T result = (T) stub.body();
    return new HttpResponse<>() {
      @Override
      public int statusCode() {
        return stub.status();
      }

      @Override
      public HttpRequest request() {
        return request;
      }

      @Override
      public Optional<HttpResponse<T>> previousResponse() {
        return Optional.empty();
      }

      @Override
      public java.net.http.HttpHeaders headers() {
        return HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
      }

      @Override
      public T body() {
        return result;
      }

      @Override
      public Optional<javax.net.ssl.SSLSession> sslSession() {
        return Optional.empty();
      }

      @Override
      public java.net.URI uri() {
        return request.uri();
      }

      @Override
      public Version version() {
        return Version.HTTP_1_1;
      }
    };
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
    return CompletableFuture.failedFuture(new UnsupportedOperationException("stub is sync-only"));
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> responseBodyHandler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    return CompletableFuture.failedFuture(new UnsupportedOperationException("stub is sync-only"));
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return Optional.empty();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return Optional.of(Duration.ofSeconds(2));
  }

  @Override
  public Redirect followRedirects() {
    return Redirect.NEVER;
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return Optional.empty();
  }

  @Override
  public SSLContext sslContext() {
    try {
      return SSLContext.getDefault();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public SSLParameters sslParameters() {
    return new SSLParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return Optional.empty();
  }

  @Override
  public Version version() {
    return Version.HTTP_1_1;
  }

  @Override
  public Optional<Executor> executor() {
    return Optional.empty();
  }

  @Override
  public java.net.http.WebSocket.Builder newWebSocketBuilder() {
    throw new UnsupportedOperationException();
  }
}
