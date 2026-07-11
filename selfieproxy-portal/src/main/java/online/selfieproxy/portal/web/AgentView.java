package online.selfieproxy.portal.web;

/** An agent's name and its current secret (an agent-scoped boringproxy access token); secret is null if none has been minted yet. */
public record AgentView(String name, String secret) {
}
