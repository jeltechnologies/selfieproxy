package online.selfieproxy.identityprovider.domain;

/** A non-admin login: can authenticate against any exposed app protected with single sign on, never the portal -- see UserStore. */
public record User(String username, String passwordHash) {
}
