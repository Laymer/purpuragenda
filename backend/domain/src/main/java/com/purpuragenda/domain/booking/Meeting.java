package com.purpuragenda.domain.booking;

import static com.purpuragenda.domain.DomainFailure.require;
import com.purpuragenda.domain.DomainFailure;
import java.net.*;
import java.util.*;

public record Meeting(String participantUrl, String meetingId) {
    public Meeting { require(participantUrl != null && meetingId != null, "INVALID_MEETING"); }
    public static Meeting parse(String value, Set<String> allowedHosts) {
        require(value != null && value.length() <= 2048 && !value.chars().anyMatch(Character::isWhitespace), "INVALID_MEETING");
        try {
            URI uri = new URI(value); String host = uri.getHost();
            require("https".equalsIgnoreCase(uri.getScheme()) && host != null && uri.getRawUserInfo() == null
                    && uri.getPort() == -1 && uri.getRawFragment() == null, "INVALID_MEETING");
            String normalized = host.toLowerCase(Locale.ROOT);
            require(allowedHosts.stream().anyMatch(h -> normalized.equals(h) || normalized.endsWith("."+h)), "INVALID_MEETING");
            require(uri.getRawPath().matches("/j/[0-9]{9,12}"), "INVALID_MEETING");
            if (uri.getRawQuery() != null) require(uri.getRawQuery().matches("pwd=[A-Za-z0-9_.~-]{1,256}"), "INVALID_MEETING");
            return new Meeting(uri.toASCIIString(), uri.getRawPath().substring(3));
        } catch (URISyntaxException e) { throw new DomainFailure("INVALID_MEETING"); }
    }
}
