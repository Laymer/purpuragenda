package com.purpuragenda.bootstrap;

import com.purpuragenda.adapters.out.email.SmtpDelivery;
import com.purpuragenda.adapters.out.security.RateKey;
import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.application.port.out.MailDelivery;
import java.net.URI;
import java.nio.file.*;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.core.env.Environment;

/** Explicit runtime profiles. Local defaults never authorize operational use. */
final class RuntimeSettings {
    private final URI origin;
    private final boolean suppressed,local;
    private final Secrets totp,delivery;
    private final RateKey rate;
    private final MailDelivery mail;
    private final Set<String> trustedProxyPeers;
    private RuntimeSettings(URI origin,boolean suppressed,boolean local,Secrets totp,Secrets delivery,RateKey rate,MailDelivery mail,Set<String> trustedProxyPeers) {
        this.trustedProxyPeers=trustedProxyPeers;this.origin=origin;this.suppressed=suppressed;this.local=local;this.totp=totp;this.delivery=delivery;this.rate=rate;this.mail=mail;
    }
    static RuntimeSettings read(Environment environment) {
        try {
            String mode=environment.getRequiredProperty("app.mode");
            if(!Set.of("local","test","restore","operational").contains(mode)) throw new IllegalArgumentException();
            boolean local=mode.equals("local") || mode.equals("test"), suppressed=mode.equals("restore");
            URI origin=URI.create(environment.getRequiredProperty("app.public-origin"));
            if(origin.getHost()==null || origin.getUserInfo()!=null || origin.getQuery()!=null || origin.getFragment()!=null
                    || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))) throw new IllegalArgumentException();
            String hostName=origin.getHost().toLowerCase(java.util.Locale.ROOT);
            if(hostName.endsWith(".")) hostName=hostName.substring(0,hostName.length()-1);
            if(origin.getPort()==0 || origin.getPort()>65535) throw new IllegalArgumentException();
            origin=new URI(origin.getScheme(),null,hostName,origin.getPort(),origin.getPath(),null,null);
            if(local || suppressed) {
                if(!"http".equals(origin.getScheme()) || !Set.of("localhost","127.0.0.1","[::1]").contains(hostName)) throw new IllegalArgumentException();
            } else if(!"https".equals(origin.getScheme()) || Set.of("localhost","127.0.0.1","[::1]").contains(hostName)
                    || hostName.endsWith(".invalid") || hostName.endsWith(".test")
                    || !environment.getRequiredProperty("app.notice-version").matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
                    || environment.getRequiredProperty("app.notice-version").toLowerCase(java.util.Locale.ROOT).startsWith("local-")) throw new IllegalArgumentException();
            Path totpFile=Path.of(environment.getRequiredProperty("app.totp-key-file"));
            Path deliveryFile=Path.of(environment.getRequiredProperty("app.delivery-key-file"));
            String totpId=environment.getRequiredProperty("app.totp-key-id"),deliveryId=environment.getRequiredProperty("app.delivery-key-id");
            if(Files.isSymbolicLink(totpFile) || Files.isSymbolicLink(deliveryFile) || totpId.equals(deliveryId) || Files.mismatch(totpFile,deliveryFile)==-1) throw new IllegalArgumentException();
            Path rateFile=Path.of(environment.getRequiredProperty("app.security.rate-key-file"));
            String rateId=environment.getRequiredProperty("app.security.rate-key-id");
            // The throttle HMAC key is a third, independent secret; local development identifiers never reach non-local modes.
            if(Files.isSymbolicLink(rateFile) || rateId.equals(totpId) || rateId.equals(deliveryId)
                    || Files.mismatch(rateFile,totpFile)==-1 || Files.mismatch(rateFile,deliveryFile)==-1
                    || !local && Stream.of(totpId,deliveryId,rateId).anyMatch(id->id.toLowerCase(java.util.Locale.ROOT).startsWith("local-"))) throw new IllegalArgumentException();
            var totp=new Secrets(totpFile,totpId);var delivery=new Secrets(deliveryFile,deliveryId);var rate=new RateKey(rateFile,rateId);
            MailDelivery mail;
            if(suppressed) mail=SmtpDelivery.restore();
            else {
                String host=environment.getRequiredProperty("app.smtp-host");
                int port=Integer.parseInt(environment.getRequiredProperty("app.smtp-port"));
                String from=environment.getRequiredProperty("app.mail-from");
                if(local) mail=SmtpDelivery.capture(host,port,from);
                else {
                    if(Set.of("localhost","127.0.0.1","::1","mailpit").contains(host.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException();
                    var security=SmtpDelivery.Security.valueOf(environment.getRequiredProperty("app.smtp-security"));
                    String passwordFile=environment.getRequiredProperty("app.smtp-password-file");
                    DatabaseConfiguration.secret(passwordFile);
                    mail=SmtpDelivery.operational(host,port,from,security,environment.getRequiredProperty("app.smtp-user"),
                            ()->DatabaseConfiguration.secret(passwordFile).toCharArray());
                }
            }
            return new RuntimeSettings(origin,suppressed,local,totp,delivery,rate,mail,peers(environment.getProperty("app.security.trusted-proxy-peers","")));
        } catch(Exception invalid) { throw new IllegalArgumentException("INVALID_RUNTIME_CONFIGURATION"); }
    }
    /** Exact literal IPs only; the default is empty so no forwarded client address is trusted. */
    private static Set<String> peers(String configured) {
        if(configured.isEmpty()) return Set.of();
        String[] entries=configured.split(",",-1);
        if(entries.length>16) throw new IllegalArgumentException();
        var result=new java.util.HashSet<String>();
        for(String entry:entries) result.add(com.purpuragenda.adapters.in.rest.ClientAddress.literal(entry).orElseThrow(IllegalArgumentException::new));
        return Set.copyOf(result);
    }
    URI origin() { return origin; }
    /** Browser Origin serialization: lowercase scheme and host, default port omitted. */
    String canonicalOrigin() {
        int port=origin.getPort();boolean standard=port==-1 || port==80 && origin.getScheme().equals("http") || port==443 && origin.getScheme().equals("https");
        return origin.getScheme()+"://"+origin.getHost()+(standard?"":":"+port);
    }
    boolean localCookies() { return local || suppressed; }
    Set<String> trustedProxyPeers() { return trustedProxyPeers; }
    boolean suppressed() { return suppressed; }
    Secrets totp() { return totp; }
    Secrets delivery() { return delivery; }
    RateKey rate() { return rate; }
    MailDelivery mail() { return mail; }
    Set<String> meetingHosts() { return local?Set.of("zoom.us","meetings.example.invalid"):Set.of("zoom.us"); }
    @Override public String toString() { return "RuntimeSettings[configuration=REDACTED]"; }
}
