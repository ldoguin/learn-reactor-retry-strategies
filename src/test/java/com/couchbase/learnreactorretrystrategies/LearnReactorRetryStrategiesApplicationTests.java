package com.couchbase.learnreactorretrystrategies;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.util.List;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LearnReactorRetryStrategiesApplicationTests {

	@TestConfiguration
	public static class MockWebServerConfiguration {

		private MockWebServer mockWebServer;
		@Bean
		public MockWebServer getMockWebServer() throws Exception{
			if (mockWebServer == null) {
				mockWebServer = new MockWebServer();
				mockWebServer.start(18888);
			}
			return this.mockWebServer;
		}
	}

	@TestConfiguration
	public static class WebClientConfiguration {

		@Autowired
		MockWebServer mockWebServer;
		@Bean
		@Primary
		public WebClient getLearnUponWebClient() {
			String uri =mockWebServer.url("/").toString();
			return WebClient.builder()
					.baseUrl(uri)
					.exchangeStrategies(
							ExchangeStrategies.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(20000000)).build())
					.build();
		}
	}

	@Autowired
	MockWebServer mockWebServer;

	@Autowired
	WebClient webClient;

	@Test
	public void testLMSRetryStrategy() throws Exception {
		String tooManyRequests = "{\"response_type\": \"ERROR\",\"message\": \"Number of requests has exceeded the 1 minute limit\"}";
		String jsonResponse = "{\"response_type\": \"Hello\",\"message\": \"world\"}";
		mockWebServer.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", 60).setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON).setBody(tooManyRequests));
		mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON).setBody(jsonResponse));
		String uri = "/";
		MultiValueMap queryParams = null;
		webClient.get()
				.uri(ub -> ub.pathSegment(uri).queryParams(queryParams).build())
				.retrieve()
				.onStatus(
						HttpStatus.TOO_MANY_REQUESTS::equals,
						response -> {
							List<String> header = response.headers().header("Retry-After");
							Integer delayInSeconds;
							if (!header.isEmpty()) {
								delayInSeconds = Integer.valueOf(header.get(0));
							} else {
								delayInSeconds = 60;
							}
							return response.bodyToMono(String.class).map(msg -> new RateLimitException(msg, delayInSeconds));
						})
				.bodyToMono(String.class)
//                .retryWhen(Retry.indefinitely().filter(throwable -> throwable instanceof RateLimitException)
//                        .doBeforeRetryAsync(s -> Mono.delay(
//                                ((RateLimitException) s.failure()).getRetryAfterDelayDuration()).then()
//                ))
				.retryWhen(Retry.withThrowable(throwableFlux -> {
					return throwableFlux.filter(t -> t instanceof RateLimitException).map(t -> {
						RateLimitException rle = (RateLimitException) t;
						return Retry.fixedDelay(1, rle.getRetryAfterDelayDuration());
					});
				}))
				.block();
	}

	@AfterAll
	public void shutdownServer() throws IOException {
		mockWebServer.close();
	}


}
