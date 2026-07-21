package online.selfieproxy.portal.domain;

/**
 * A domain the admin registered in addition to the primary domain (see
 * DomainStore) -- never surfaced to the user as "secondary", just as another
 * domain they can expose Applications/Local Websites on.
 *
 * @param name the full domain as typed, e.g. "example2.com" -- lowercased, no trailing dot
 */
public record SecondaryDomain(String name) {
}
