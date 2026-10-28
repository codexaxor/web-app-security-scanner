package com.company.secscan.source;

import java.util.Locale;

/**
 * Derives a connection's identity host from its base URL, matching the {@code gitlab_connection.host} generated
 * column in migration V22 exactly: the URL authority ({@code host[:port]}) with the scheme's default port
 * (443 for https, 80 for http) dropped and any explicit non-default port kept. A scheme without an authority
 * (e.g. a test-only {@code file://} URL) yields the empty string. Used wherever Java needs the same host the DB
 * stores (connection-dedup and repository-identity lookups), so code and the generated column never disagree.
 */
public final class SourceHosts {

    private SourceHosts() {
    }

    public static String authorityOf(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        int schemeEnd = baseUrl.indexOf("://");
        if (schemeEnd < 0) {
            return "";
        }
        String rest = baseUrl.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String lower = baseUrl.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://") && authority.endsWith(":443")) {
            return authority.substring(0, authority.length() - 4);
        }
        if (lower.startsWith("http://") && authority.endsWith(":80")) {
            return authority.substring(0, authority.length() - 3);
        }
        return authority;
    }
}
