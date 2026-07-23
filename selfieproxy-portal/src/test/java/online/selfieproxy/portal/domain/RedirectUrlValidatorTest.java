package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RedirectUrlValidatorTest {

	@Test
	void acceptsBareHttpsUrl() {
		assertTrue(RedirectUrlValidator.isValid("https://www.example.com"));
	}

	@Test
	void acceptsBareHttpUrl() {
		assertTrue(RedirectUrlValidator.isValid("http://www.example.com"));
	}

	@Test
	void acceptsRootPathUrl() {
		assertTrue(RedirectUrlValidator.isValid("https://www.example.com/"));
	}

	@Test
	void rejectsNullOrBlank() {
		assertFalse(RedirectUrlValidator.isValid(null));
		assertFalse(RedirectUrlValidator.isValid("  "));
	}

	@Test
	void rejectsMissingScheme() {
		assertFalse(RedirectUrlValidator.isValid("www.example.com"));
	}

	@Test
	void rejectsNonHttpScheme() {
		assertFalse(RedirectUrlValidator.isValid("ftp://www.example.com"));
	}

	@Test
	void rejectsUrlWithPath() {
		assertFalse(RedirectUrlValidator.isValid("https://www.example.com/landing"));
	}

	@Test
	void rejectsUrlWithQuery() {
		assertFalse(RedirectUrlValidator.isValid("https://www.example.com?ref=x"));
	}

	@Test
	void rejectsMalformedUrl() {
		assertFalse(RedirectUrlValidator.isValid("https://"));
	}
}
