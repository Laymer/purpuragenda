package com.purpuragenda.adapters.out.email;

import com.purpuragenda.application.port.out.MailDelivery;
import com.purpuragenda.domain.customer.Input;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLException;
import org.eclipse.angus.mail.smtp.*;

/** Explicit profiles only; no booking templates, database access or provider diagnostics. */
public final class SmtpDelivery implements MailDelivery {
    public enum Security { STARTTLS_REQUIRED, IMPLICIT_TLS }
    private enum Mode { CAPTURE, OPERATIONAL, RESTORE }
    private static final Set<String> CAPTURE_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "mailpit");
    private final Mode mode;
    private final String host, from, username;
    private final int port;
    private final Security security;
    private final Supplier<char[]> passwords;

    private SmtpDelivery(Mode mode, String host, int port, String from, Security security,
                         String username, Supplier<char[]> passwords) {
        this.mode=mode; this.host=host; this.port=port; this.from=from;
        this.security=security; this.username=username; this.passwords=passwords;
        // Session debug is also disabled. These namespaces must not emit private protocol traces.
        Logger.getLogger("jakarta.mail").setLevel(Level.OFF);
        Logger.getLogger("org.eclipse.angus.mail").setLevel(Level.OFF);
    }
    public static SmtpDelivery restore() { return new SmtpDelivery(Mode.RESTORE, null, 0, null, null, null, null); }
    public static SmtpDelivery capture(String host, int port, String from) {
        if (host == null || !CAPTURE_HOSTS.contains(host.toLowerCase(Locale.ROOT))) throw invalid();
        return configured(Mode.CAPTURE, host.toLowerCase(Locale.ROOT), port, from, null, null, null);
    }
    public static SmtpDelivery operational(String host, int port, String from, Security security,
                                         String username, Supplier<char[]> passwords) {
        if (host == null || !host.matches("[A-Za-z0-9][A-Za-z0-9.:-]{0,252}") || host.contains("..")
                || security == null || username == null || username.isBlank() || passwords == null) throw invalid();
        try { username = Input.text(username, 1, 254); } catch (RuntimeException ignored) { throw invalid(); }
        return configured(Mode.OPERATIONAL, host, port, from, security, username, passwords);
    }
    private static SmtpDelivery configured(Mode mode, String host, int port, String from, Security security,
                                          String username, Supplier<char[]> passwords) {
        if (port < 1 || port > 65535) throw invalid();
        try { from = Input.email(from); } catch (RuntimeException ignored) { throw invalid(); }
        return new SmtpDelivery(mode,host,port,from,security,username,passwords);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_SMTP_CONFIGURATION"); }

    @Override public Result send(Message message) {
        if (mode == Mode.RESTORE) return Result.SUPPRESSED;
        Objects.requireNonNull(message);
        Transport transport = null; char[] password = null;
        try {
            String endpoint = mode == Mode.CAPTURE ? captureAddress(host, InetAddress.getAllByName(host)) : host;
            var session = Session.getInstance(properties()); session.setDebug(false);
            transport = session.getTransport(security == Security.IMPLICIT_TLS ? "smtps" : "smtp");
            var mail = new MimeMessage(session) {
                @Override protected void updateMessageID() throws MessagingException {
                    setHeader("Message-ID", "<" + message.jobId() + "@purpuragenda>");
                }
            };
            mail.setFrom(new InternetAddress(from)); mail.setRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(message.recipient()));
            mail.setSubject(message.subject(), StandardCharsets.UTF_8.name());
            mail.setText(message.text(), StandardCharsets.UTF_8.name()); mail.setSentDate(new Date()); mail.saveChanges();
            if (mode == Mode.OPERATIONAL) {
                password = passwords.get();
                if (password == null || password.length == 0 || password.length > 4096) return Result.FAILED;
                transport.connect(endpoint, port, username, new String(password));
            } else transport.connect(endpoint, port, null, null);
            transport.sendMessage(mail, mail.getAllRecipients());
            return Result.SENT;
        } catch (MessagingException | IOException | RuntimeException failure) {
            return classify(transport, failure);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (transport != null) try { transport.close(); } catch (MessagingException | RuntimeException ignored) { }
        }
    }

    Properties properties() {
        String prefix = security == Security.IMPLICIT_TLS ? "mail.smtps." : "mail.smtp.";
        var properties = new Properties();
        properties.setProperty("mail.debug", "false"); properties.setProperty("mail.debug.auth", "false");
        properties.setProperty(prefix+"localhost", "purpuragenda.local");
        properties.setProperty(prefix+"connectiontimeout", "5000"); properties.setProperty(prefix+"timeout", "10000");
        properties.setProperty(prefix+"writetimeout", "10000"); properties.setProperty(prefix+"auth", Boolean.toString(mode==Mode.OPERATIONAL));
        properties.setProperty(prefix+"ssl.checkserveridentity", "true"); properties.setProperty(prefix+"ssl.protocols", "TLSv1.3 TLSv1.2");
        properties.setProperty(prefix+"ssl.enable", Boolean.toString(security==Security.IMPLICIT_TLS));
        properties.setProperty(prefix+"starttls.enable", Boolean.toString(security==Security.STARTTLS_REQUIRED));
        properties.setProperty(prefix+"starttls.required", Boolean.toString(security==Security.STARTTLS_REQUIRED));
        return properties;
    }
    static String captureAddress(String host, InetAddress[] addresses) {
        if (!CAPTURE_HOSTS.contains(host) || addresses.length == 0) throw invalid();
        for (var address : addresses) {
            byte[] bytes = address.getAddress();
            boolean privateAddress = address.isSiteLocalAddress() || bytes.length==16 && (bytes[0]&0xfe)==0xfc;
            if (!address.isLoopbackAddress() && (!host.equals("mailpit") || !privateAddress)) throw invalid();
        }
        return addresses[0].getHostAddress(); // Pin the checked address, avoiding a second DNS resolution.
    }
    static Result classify(Transport transport, Exception failure) {
        int status = 0; boolean io = false, permanent = false;
        Throwable current = failure;
        for (int i=0; current!=null && i<10; i++) {
            if (current instanceof SMTPAddressFailedException address) status = address.getReturnCode();
            if (current instanceof SMTPSendFailedException send) status = send.getReturnCode();
            if (current instanceof SMTPSenderFailedException sender) status = sender.getReturnCode();
            permanent |= current instanceof AuthenticationFailedException || current instanceof SSLException;
            io |= current instanceof IOException;
            current = current.getCause();
        }
        if (permanent) return Result.FAILED;
        if (status == 0 && transport instanceof SMTPTransport smtp) status = smtp.getLastReturnCode();
        if (status >= 400 && status < 500) return Result.RETRY_LATER;
        if (status >= 500) return Result.FAILED;
        return io ? Result.RETRY_LATER : Result.FAILED;
    }
    @Override public String toString() { return "SmtpDelivery[mode=" + mode + ", configuration=REDACTED]"; }
}
