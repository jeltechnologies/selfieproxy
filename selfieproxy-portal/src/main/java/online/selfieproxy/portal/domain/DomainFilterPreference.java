package online.selfieproxy.portal.domain;

/** Any field may be null, meaning "no filter selected" -- the Servers and Local Websites list pages remember their own filters independently. */
public record DomainFilterPreference(String serversDomain, String localWebsitesDomain, String serversHomelab) {
}
