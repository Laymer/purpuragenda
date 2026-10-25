package com.purpuragenda.adapters.out.email;

import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import javax.net.ssl.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Ephemeral keys/certificates only; never writes a keystore or private key to disk. */
record TestTls(SSLContext server, X509Certificate certificate) {
    static TestTls create(boolean correctName) throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
        var name=new X500Name("CN=Synthetic SMTP"); Instant now=Instant.now();
        var builder=new JcaX509v3CertificateBuilder(name,new BigInteger(128,new SecureRandom()).add(BigInteger.ONE),
                Date.from(now.minusSeconds(60)),Date.from(now.plusSeconds(3600)),name,pair.getPublic());
        var alternative=new GeneralName(correctName?GeneralName.iPAddress:GeneralName.dNSName,
                correctName?"127.0.0.1":"different.example.test");
        builder.addExtension(Extension.subjectAlternativeName,false,new GeneralNames(alternative));
        var certificate=new JcaX509CertificateConverter().getCertificate(builder.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
        var keys=KeyStore.getInstance("PKCS12"); keys.load(null,null);
        char[] password=UUID.randomUUID().toString().toCharArray();
        try {
            keys.setKeyEntry("synthetic",pair.getPrivate(),password,new java.security.cert.Certificate[]{certificate});
            var managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(keys,password);
            var context=SSLContext.getInstance("TLS"); context.init(managers.getKeyManagers(),null,new SecureRandom());
            return new TestTls(context,certificate);
        } finally { Arrays.fill(password,'\0'); }
    }
    static SSLContext trusting(X509Certificate certificate) throws Exception {
        var trust=KeyStore.getInstance("PKCS12"); trust.load(null,null); trust.setCertificateEntry("synthetic",certificate);
        var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        var context=SSLContext.getInstance("TLS"); context.init(null,managers.getTrustManagers(),new SecureRandom());
        return context;
    }
    @Override public String toString() { return "EphemeralTls[REDACTED]"; }
}
