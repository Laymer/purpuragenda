package com.purpuragenda.domain.booking;

import static com.purpuragenda.domain.DomainFailure.require;
import com.purpuragenda.domain.DomainFailure;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** A validated participant URL. Never fetch it or include it in logs. */
public final class Meeting {
    private final String participantUrl;
    private final String meetingId;

    private Meeting(String participantUrl, String meetingId) {
        this.participantUrl = participantUrl;
        this.meetingId = meetingId;
    }

    public String participantUrl() { return participantUrl; }
    public String meetingId() { return meetingId; }

    public static Meeting parse(String value, Set<String> allowedHosts) {
        require(value != null && value.length() <= 2048
                && value.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)), "INVALID_MEETING");
        require(allowedHosts != null && !allowedHosts.isEmpty(), "INVALID_MEETING");
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            require("https".equalsIgnoreCase(uri.getScheme()) && host != null && uri.getRawUserInfo() == null
                    && uri.getPort() == -1 && uri.getRawFragment() == null, "INVALID_MEETING");
            String normalized = host.toLowerCase(Locale.ROOT);
            require(!normalized.matches("[0-9.]+") && !normalized.contains(":"), "INVALID_MEETING");
            require(allowedHosts.stream().filter(Objects::nonNull).map(h -> h.toLowerCase(Locale.ROOT))
                    .anyMatch(h -> normalized.equals(h) || normalized.endsWith("." + h)), "INVALID_MEETING");
            require(uri.getRawPath().matches("/j/[0-9]{9,12}"), "INVALID_MEETING");
            if (uri.getRawQuery() != null)
                require(uri.getRawQuery().matches("pwd=[A-Za-z0-9_.~-]{1,256}"), "INVALID_MEETING");
            return new Meeting(uri.toASCIIString(), uri.getRawPath().substring(3));
        } catch (URISyntaxException error) {
            throw new DomainFailure("INVALID_MEETING");
        }
    }

    @Override public boolean equals(Object other) {
        return other instanceof Meeting meeting && participantUrl.equals(meeting.participantUrl);
    }
    @Override public int hashCode() { return participantUrl.hashCode(); }
    @Override public String toString() { return "Meeting[redacted]"; }
}
