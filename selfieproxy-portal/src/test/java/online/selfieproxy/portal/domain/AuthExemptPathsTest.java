package online.selfieproxy.portal.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A wrong rule here either blocks a legitimate exception or lets through one that quietly turns a
 * protected Server's gate off, so both directions are worth pinning down.
 */
class AuthExemptPathsTest {

	/** The always-present trailing blank row submits an empty entry on every single save. */
	@Test
	void parseDropsEmptyRowsAndDuplicatesButKeepsOrder() {
		assertEquals(List.of("/login*.*", "/static/**"),
				AuthExemptPaths.parse(java.util.Arrays.asList("/login*.*", "", "  /static/**  ", "/login*.*", null)));
	}

	@Test
	void parseTreatsNothingSubmittedAsNoExceptions() {
		assertEquals(List.of(), AuthExemptPaths.parse(null));
		assertEquals(List.of(), AuthExemptPaths.parse(List.of()));
		assertEquals(List.of(), AuthExemptPaths.parse(List.of("   ", "")));
	}

	@Test
	void ordinaryPatternsAreAccepted() {
		assertEquals(List.of(), AuthExemptPaths.validate(
				List.of("/login", "/login*.*", "/static/**", "/api/v1/*/health")));
	}

	@Test
	void aPatternMustBeRooted() {
		List<String> errors = AuthExemptPaths.validate(List.of("login*"));
		assertEquals(1, errors.size());
		assertTrue(errors.get(0).contains("must start with a slash"), errors.get(0));
	}

	/** The one case that would silently make the selected gate a no-op for every request. */
	@Test
	void aPatternMatchingEveryPathIsRejected() {
		for (String pattern : List.of("/", "/*", "/**")) {
			List<String> errors = AuthExemptPaths.validate(List.of(pattern));
			assertEquals(1, errors.size(), pattern);
			assertTrue(errors.get(0).contains("would leave the whole server open"), pattern);
		}
	}

	@Test
	void spacesAndControlCharactersAreRejected() {
		assertTrue(AuthExemptPaths.validate(List.of("/my page")).get(0).contains("cannot contain spaces"));
		assertTrue(AuthExemptPaths.validate(List.of("/page\tx")).get(0).contains("cannot contain spaces"));
	}

	@Test
	void overlongAndOvernumerousListsAreRejected() {
		String tooLong = "/" + "a".repeat(AuthExemptPaths.MAX_LENGTH);
		assertTrue(AuthExemptPaths.validate(List.of(tooLong)).get(0).contains("cannot be longer than"));

		List<String> tooMany = java.util.stream.IntStream.rangeClosed(0, AuthExemptPaths.MAX_ENTRIES)
				.mapToObj(i -> "/p" + i)
				.toList();
		assertTrue(AuthExemptPaths.validate(tooMany).get(0).contains("Up to " + AuthExemptPaths.MAX_ENTRIES));
	}
}
