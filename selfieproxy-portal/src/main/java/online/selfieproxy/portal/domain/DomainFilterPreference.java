package online.selfieproxy.portal.domain;

/** Either field may be null, meaning "All domains" -- the Applications and Local Websites list pages remember their own filter independently. */
public record DomainFilterPreference(String serversDomain, String localWebsitesDomain) {
}
