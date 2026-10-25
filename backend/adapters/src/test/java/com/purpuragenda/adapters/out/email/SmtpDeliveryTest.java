package com.purpuragenda.adapters.out.email;

import com.purpuragenda.application.port.out.MailDelivery.Message;
import com.purpuragenda.application.port.out.MailDelivery.Result;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import org.eclipse.angus.mail.smtp.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("smtp-default-ssl-context")
@Timeout(30)
class SmtpDeliveryTest {
    static final String FROM="sender@example.test",USER="synthetic-user";
    Message message() { return new Message(UUID.randomUUID(),"synthetic@example.test","Synthetic private subject","Synthetic private body é\n.leading line\n"); }
    SmtpDelivery operational(TestSmtpServer server,SmtpDelivery.Security security,char[] password) {
        return SmtpDelivery.operational("127.0.0.1",server.port(),FROM,security,USER,()->password);
    }
    MimeMessage received(TestSmtpServer server) throws Exception {
        byte[] bytes=server.messages.poll(5,TimeUnit.SECONDS); assertNotNull(bytes);
        return new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(bytes));
    }

    @Test void loopbackCapturePreservesUtf8DotLinesAndStableMessageIdAcrossAttempts() throws Exception {
        try(var server=new TestSmtpServer(null,false,250,false)) {
            var sender=SmtpDelivery.capture("127.0.0.1",server.port(),FROM); var message=message();
            for(int i=0;i<2;i++) {
                assertEquals(Result.SENT,sender.send(message)); var captured=received(server);
                assertTrue(captured.getSubject().equals(message.subject()));
                assertTrue(captured.getContent().toString().replace("\r\n","\n").equals(message.text()));
                assertTrue(captured.getHeader("Message-ID",null).equals("<"+message.jobId()+"@purpuragenda>"));
                assertTrue(captured.getAllRecipients()[0].toString().equals(message.recipient()));
            }
            assertEquals(0,server.auth.get()); assertEquals(2,server.mail.get());
            assertFalse(sender.toString().contains(FROM)); assertTrue(sender.toString().contains("REDACTED"));
        }
    }

    @Test void requiredStartTlsAndImplicitTlsAuthenticateAndDeliverWithTrustedCertificates() throws Exception {
        SSLContext original=SSLContext.getDefault(); var tls=TestTls.create(true);
        try {
            SSLContext.setDefault(TestTls.trusting(tls.certificate()));
            for(var security:SmtpDelivery.Security.values()) {
                try(var server=new TestSmtpServer(tls.server(),security==SmtpDelivery.Security.IMPLICIT_TLS,250,false)) {
                    char[] password=UUID.randomUUID().toString().toCharArray();
                    assertEquals(Result.SENT,operational(server,security,password).send(message()));
                    assertNotNull(received(server)); assertEquals(1,server.secure.get()); assertEquals(1,server.auth.get());
                    assertEquals(0,server.unsafeAuth.get()); assertEquals(1,server.mail.get());
                    assertTrue(Arrays.equals(password,new char[password.length]));
                }
            }
        } finally { SSLContext.setDefault(original); }
    }

    @Test void untrustedAndHostnameMismatchedCertificatesFailBeforeAuthentication() throws Exception {
        SSLContext original=SSLContext.getDefault(); var trusted=TestTls.create(true); var untrusted=TestTls.create(true); var wrongName=TestTls.create(false);
        try {
            for(var serverTls:new TestTls[]{untrusted,wrongName}) {
                SSLContext.setDefault(TestTls.trusting(serverTls==wrongName?wrongName.certificate():trusted.certificate()));
                for(var security:SmtpDelivery.Security.values()) {
                    try(var server=new TestSmtpServer(serverTls.server(),security==SmtpDelivery.Security.IMPLICIT_TLS,250,false)) {
                        char[] password=UUID.randomUUID().toString().toCharArray();
                        assertEquals(Result.FAILED,operational(server,security,password).send(message()));
                        assertEquals(0,server.auth.get()); assertEquals(0,server.mail.get()); assertTrue(server.messages.isEmpty());
                        assertTrue(Arrays.equals(password,new char[password.length]));
                    }
                }
            }
        } finally { SSLContext.setDefault(original); }
    }

    @Test void missingStartTlsIsRefusedAndRestoreDoesNotConnectOrReadCredentials() throws Exception {
        try(var server=new TestSmtpServer(null,false,250,false)) {
            char[] password=UUID.randomUUID().toString().toCharArray();
            assertEquals(Result.FAILED,operational(server,SmtpDelivery.Security.STARTTLS_REQUIRED,password).send(message()));
            assertEquals(0,server.mail.get()); assertEquals(0,server.auth.get());
            assertTrue(Arrays.equals(password,new char[password.length]));
            int connections=server.accepted.get();
            assertEquals(Result.SUPPRESSED,SmtpDelivery.restore().send(message()));
            assertEquals(Result.SUPPRESSED,SmtpDelivery.restore().send(null));
            assertEquals(connections,server.accepted.get());
        }
    }

    @Test void smtpRejectionsAndLostCloseAcknowledgementHaveSafeResults() throws Exception {
        for(int code:new int[]{451,550}) try(var server=new TestSmtpServer(null,false,code,false)) {
            assertEquals(code==451?Result.RETRY_LATER:Result.FAILED,SmtpDelivery.capture("127.0.0.1",server.port(),FROM).send(message()));
            assertTrue(server.messages.isEmpty());
        }
        try(var server=new TestSmtpServer(null,false,250,true)) {
            assertEquals(Result.SENT,SmtpDelivery.capture("127.0.0.1",server.port(),FROM).send(message())); assertNotNull(received(server));
        }
        int unused;
        try(var socket=new ServerSocket(0,1,InetAddress.getByAddress(new byte[]{127,0,0,1}))) { unused=socket.getLocalPort(); }
        assertEquals(Result.RETRY_LATER,SmtpDelivery.capture("127.0.0.1",unused,FROM).send(message()));
    }

    @Test void captureDnsPolicyPinsOnlyLoopbackOrLiteralPrivateMailpit() throws Exception {
        var loopback=InetAddress.getByAddress(new byte[]{127,0,0,1});
        var privateIp=InetAddress.getByAddress(new byte[]{10,2,3,4});
        var publicIp=InetAddress.getByAddress(new byte[]{(byte)203,0,113,1});
        var metadata=InetAddress.getByAddress(new byte[]{(byte)169,(byte)254,(byte)169,(byte)254});
        byte[] ula=new byte[16]; ula[0]=(byte)0xfd; ula[15]=1;
        assertTrue(SmtpDelivery.captureAddress("localhost",new InetAddress[]{loopback}).equals("127.0.0.1"));
        assertTrue(SmtpDelivery.captureAddress("mailpit",new InetAddress[]{privateIp}).equals("10.2.3.4"));
        assertNotNull(SmtpDelivery.captureAddress("mailpit",new InetAddress[]{InetAddress.getByAddress(ula)}));
        assertNotNull(SmtpDelivery.captureAddress("::1",new InetAddress[]{InetAddress.getByName("::1")}));
        for(var addresses:new InetAddress[][]{{},{publicIp},{privateIp,publicIp},{metadata}})
            assertThrows(IllegalArgumentException.class,()->SmtpDelivery.captureAddress("mailpit",addresses));
        assertThrows(IllegalArgumentException.class,()->SmtpDelivery.captureAddress("localhost",new InetAddress[]{privateIp}));
        assertThrows(IllegalArgumentException.class,()->SmtpDelivery.captureAddress("other",new InetAddress[]{loopback}));
        for(String host:new String[]{null,"smtp.external.test","mailpit.external.test"})
            assertThrows(IllegalArgumentException.class,()->SmtpDelivery.capture(host,1025,FROM));
    }

    @Test void operationalPolicyRequiresTlsIdentityVerificationBoundedTimeoutsAndNoDebugOrCredentialProperties() {
        for(var security:SmtpDelivery.Security.values()) {
            var sender=SmtpDelivery.operational("smtp.example.test",465,FROM,security,USER,()->new char[]{'x'});
            var properties=sender.properties(); String prefix=security==SmtpDelivery.Security.IMPLICIT_TLS?"mail.smtps.":"mail.smtp.";
            assertEquals("true",properties.getProperty(prefix+"ssl.checkserveridentity"));
            assertEquals("TLSv1.3 TLSv1.2",properties.getProperty(prefix+"ssl.protocols"));
            for(String key:new String[]{"connectiontimeout","timeout","writetimeout"}) assertTrue(Integer.parseInt(properties.getProperty(prefix+key))>0);
            assertEquals("false",properties.getProperty("mail.debug")); assertEquals("false",properties.getProperty("mail.debug.auth"));
            assertEquals("true",properties.getProperty(prefix+"auth"));
            assertEquals(Boolean.toString(security==SmtpDelivery.Security.STARTTLS_REQUIRED),properties.getProperty(prefix+"starttls.required"));
            assertFalse(properties.containsKey(prefix+"ssl.trust"));
            assertTrue(properties.keySet().stream().noneMatch(key->key.toString().contains("password")||key.toString().contains("user")));
        }
        for(int port:new int[]{0,65536}) assertThrows(IllegalArgumentException.class,()->SmtpDelivery.capture("127.0.0.1",port,FROM));
        assertThrows(IllegalArgumentException.class,()->SmtpDelivery.capture("127.0.0.1",1025,"invalid"));
        for(String host:new String[]{null,"bad host","a..b"}) assertThrows(IllegalArgumentException.class,()->SmtpDelivery.operational(host,465,FROM,SmtpDelivery.Security.IMPLICIT_TLS,USER,()->new char[]{'x'}));
        assertThrows(IllegalArgumentException.class,()->SmtpDelivery.operational("host",465,FROM,null,USER,()->new char[]{'x'}));
        for(String user:new String[]{null,"","bad\nuser"}) assertThrows(IllegalArgumentException.class,()->SmtpDelivery.operational("host",465,FROM,SmtpDelivery.Security.IMPLICIT_TLS,user,()->new char[]{'x'}));
        assertThrows(IllegalArgumentException.class,()->SmtpDelivery.operational("host",465,FROM,SmtpDelivery.Security.IMPLICIT_TLS,USER,null));
    }

    @Test void credentialFailuresAreRedactedAndTransientBuffersAreConsumed() throws Exception {
        try(var server=new TestSmtpServer(null,false,250,false)) {
            for(char[] password:new char[][]{null,new char[0],new char[4097]}) {
                if(password!=null) Arrays.fill(password,'x');
                assertEquals(Result.FAILED,operational(server,SmtpDelivery.Security.STARTTLS_REQUIRED,password).send(message()));
                if(password!=null) assertTrue(Arrays.equals(password,new char[password.length]));
            }
            assertEquals(Result.FAILED,SmtpDelivery.operational("127.0.0.1",server.port(),FROM,SmtpDelivery.Security.STARTTLS_REQUIRED,USER,
                    ()->{ throw new IllegalStateException("synthetic private provider context"); }).send(message()));
            assertEquals(0,server.accepted.get());
        }
        Exception cycle=new Exception("synthetic context"),other=new Exception("synthetic context"); cycle.initCause(other); other.initCause(cycle);
        assertEquals(Result.FAILED,SmtpDelivery.classify(null,cycle));
        assertEquals(Result.FAILED,SmtpDelivery.classify(null,new AuthenticationFailedException("synthetic context")));
        assertEquals(Result.RETRY_LATER,SmtpDelivery.classify(null,new IOException("synthetic context")));
        assertEquals(Result.FAILED,SmtpDelivery.classify(null,new SSLHandshakeException("synthetic context")));
    }
}
