package online.selfieproxy.portal.web;

/** A row on the Homelabs list: name, live connection status (see AgentController.isOnline), and exposed app count. */
public record AgentListItem(String name, boolean online, int appCount) {
}
