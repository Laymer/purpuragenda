package com.purpuragenda.bootstrap;

import com.purpuragenda.adapters.out.email.SmtpDelivery;
import com.purpuragenda.adapters.out.security.Secrets;
import com.purpuragenda.application.port.out.MailDelivery;
import java.net.URI;
import java.nio.file.*;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Explicit runtime profiles. Local defaults never authorize operational use. */
final class RuntimeSettings {
    private final URI origin;
    private final boolean suppressed,local;
    private final Secrets totp,delivery;
    private final MailDelivery mail;
    private RuntimeSettings(URI origin,boolean suppressed,boolean local,Secrets totp,Secrets delivery,MailDelivery mail) {
        this.origin=origin;this.suppressed=suppressed;this.local=local;this.totp=totp;this.delivery=delivery;this.mail=mail;
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
            var totp=new Secrets(totpFile,totpId);var delivery=new Secrets(deliveryFile,deliveryId);
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
            return new RuntimeSettings(origin,suppressed,local,totp,delivery,mail);
        } catch(Exception invalid) { throw new IllegalArgumentException("INVALID_RUNTIME_CONFIGURATION"); }
    }
    URI origin() { return origin; }
    boolean suppressed() { return suppressed; }
    Secrets totp() { return totp; }
    Secrets delivery() { return delivery; }
    MailDelivery mail() { return mail; }
    Set<String> meetingHosts() { return local?Set.of("zoom.us","meetings.example.invalid"):Set.of("zoom.us"); }
    @Override public String toString() { return "RuntimeSettings[configuration=REDACTED]"; }
}
