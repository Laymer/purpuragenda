package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.port.in.Actor;
import com.purpuragenda.application.port.out.IdentityGateway;
import com.purpuragenda.domain.DomainFailure;
import static com.purpuragenda.domain.DomainFailure.require;
import com.purpuragenda.domain.customer.Input;
import com.purpuragenda.adapters.codec.Json;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresIdentity implements IdentityGateway {
    private final JdbcTemplate jdbc;private final TransactionTemplate transaction;private final Secrets secrets;private final String baseUrl;
    private final Argon2PasswordEncoder passwords=new Argon2PasswordEncoder(16,32,1,65536,3);
    private final String dummy=passwords.encode(Secrets.token());
    public PostgresIdentity(JdbcTemplate jdbc,PlatformTransactionManager manager,Secrets secrets,String baseUrl){this.jdbc=jdbc;transaction=new TransactionTemplate(manager);this.secrets=secrets;this.baseUrl=baseUrl.replaceAll("/+$","");}
    public void challenge(String email,String purpose,String reference,String nonce,String ip){
        // Technical throttles commit even when the send is denied; they never enter the scheduling lock.
        throttle("email-ip/"+ip,30,Duration.ofHours(1));throttle("email-address/"+email,5,Duration.ofHours(1));throttle("email-cooldown/"+email,1,Duration.ofSeconds(60));
        transaction.executeWithoutResult(status->{
            Instant now=now();UUID booking=null;
            if("MANAGE".equals(purpose)){
                var matching=jdbc.queryForList("SELECT id FROM booking WHERE reference=? AND delivery_email=?",UUID.class,Objects.requireNonNullElse(reference,""),email);
                if(matching.isEmpty())return;booking=matching.getFirst();
            }
            String token=Secrets.token();UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO email_challenge(id,token_hash,email_key,purpose,booking_id,browser_hash,expires_at) VALUES(?,?,?,?,?,?,?)",id,Secrets.hash(token),email,purpose,booking,"MANAGE".equals(purpose)?null:Secrets.hash(nonce),Timestamp.from(now.plusSeconds(900)));
            var envelope=Map.of("email",email,"purpose",purpose,"url",baseUrl+"/auth#token="+token);
            jdbc.update("INSERT INTO notification_job(id,audience,template,due_at,encrypted_envelope,challenge_id) VALUES(?,'CUSTOMER','AUTH',?,?,?)",UUID.randomUUID(),Timestamp.from(now),secrets.encrypt(Json.write(envelope)),id);
        });
    }
    public SessionIdentity redeem(String token,String nonce){
        SessionIdentity result=transaction.execute(status->{
            var rows=jdbc.queryForList("SELECT * FROM email_challenge WHERE token_hash=? FOR UPDATE",Secrets.hash(token));
            if(rows.isEmpty())return null;var row=rows.getFirst();Instant now=now();
            if(row.get("consumed_at")!=null||!now.isBefore(((Timestamp)row.get("expires_at")).toInstant()))return null;
            if(row.get("browser_hash")!=null&&!row.get("browser_hash").equals(Secrets.hash(nonce)))return null;
            String email=(String)row.get("email_key"),purpose=(String)row.get("purpose");UUID booking=(UUID)row.get("booking_id");
            UUID customer="MANAGE".equals(purpose)?jdbc.queryForObject("SELECT customer_id FROM booking WHERE id=?",UUID.class,booking):identity(email,now);
            Actor.Kind kind=switch(purpose){case "CREATE"->Actor.Kind.CREATION_GRANT;case "ACCOUNT"->Actor.Kind.ACCOUNT;case "MANAGE"->Actor.Kind.MANAGEMENT_GRANT;default->throw new DomainFailure("INVALID_TOKEN");};
            if(kind==Actor.Kind.ACCOUNT)jdbc.update("INSERT INTO customer_account(id,customer_id,enrolled_at) VALUES(?,?,?) ON CONFLICT(customer_id) DO NOTHING",UUID.randomUUID(),customer,Timestamp.from(now));
            jdbc.update("UPDATE email_challenge SET consumed_at=? WHERE id=?",Timestamp.from(now),row.get("id"));
            jdbc.update("UPDATE notification_job SET encrypted_envelope=NULL WHERE challenge_id=?",row.get("id"));
            UUID grant=UUID.randomUUID();Instant expiry=now.plus(kind==Actor.Kind.ACCOUNT?Duration.ofHours(12):kind==Actor.Kind.CREATION_GRANT?Duration.ofMinutes(30):Duration.ofHours(2));
            jdbc.update("INSERT INTO access_grant(id,customer_id,kind,booking_id,expires_at) VALUES(?,?,?,?,?)",grant,customer,kind.name(),booking,Timestamp.from(expiry));
            return new SessionIdentity(grant,kind,customer,booking,now,expiry);
        });
        require(result!=null,"INVALID_TOKEN");return result;
    }
    public UUID password(String email,String password,String ip){
        throttle("admin-ip/"+ip,5,Duration.ofMinutes(15));throttle("admin-account/"+email,5,Duration.ofMinutes(15));
        var rows=jdbc.queryForList("SELECT id,password_hash FROM admin_user WHERE email_key=? AND enabled AND enrolled",email);
        String hash=rows.isEmpty()?dummy:(String)rows.getFirst().get("password_hash");boolean valid=password!=null&&password.length()<=128&&passwords.matches(password,hash);
        require(valid&&!rows.isEmpty(),"AUTHENTICATION_FAILED");return (UUID)rows.getFirst().get("id");
    }
    public SessionIdentity totp(UUID admin,String code){
        throttle("totp/"+admin,5,Duration.ofMinutes(15));
        return transaction.execute(status->{
            var rows=jdbc.queryForList("SELECT * FROM admin_user WHERE id=? AND enabled AND enrolled FOR UPDATE",admin);require(!rows.isEmpty(),"AUTHENTICATION_FAILED");
            var row=rows.getFirst();Instant now=now();long step=validStep((String)row.get("totp_secret"),code,now,((Number)row.get("last_totp_step")).longValue());require(step>=0,"AUTHENTICATION_FAILED");
            jdbc.update("UPDATE admin_user SET last_totp_step=? WHERE id=?",step,admin);
            return new SessionIdentity(admin,Actor.Kind.ADMINISTRATOR,null,null,now,now.plusSeconds(28800));
        });
    }
    public SessionIdentity recovery(UUID admin,String code){
        throttle("recovery/"+admin,5,Duration.ofMinutes(15));
        return transaction.execute(status->{
            Instant now=now();int changed=jdbc.update("UPDATE admin_recovery_code SET consumed_at=? WHERE admin_id=? AND code_hash=? AND consumed_at IS NULL",Timestamp.from(now),admin,Secrets.hash(Objects.requireNonNullElse(code,"")));
            require(changed==1,"AUTHENTICATION_FAILED");
            jdbc.update("UPDATE admin_user SET enrolled=false,totp_secret=?,last_totp_step=-1 WHERE id=?",newSecret(),admin);
            // Return no administrator session; caller must complete fresh TOTP enrollment privately.
            return new SessionIdentity(admin,Actor.Kind.ADMINISTRATOR,null,null,now,now);
        });
    }
    public Optional<SessionIdentity> validate(SessionIdentity identity){
        if(identity==null)return Optional.empty();
        if(identity.kind()==Actor.Kind.ADMINISTRATOR){if(!Instant.now().isBefore(identity.absoluteExpiry()))return Optional.empty();Integer count=jdbc.queryForObject("SELECT count(*) FROM admin_user WHERE id=? AND enabled AND enrolled",Integer.class,identity.id());return count==1?Optional.of(identity):Optional.empty();}
        var rows=jdbc.queryForList("SELECT * FROM access_grant WHERE id=? AND revoked_at IS NULL AND expires_at>clock_timestamp()",identity.id());
        if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();
        return Optional.of(new SessionIdentity(identity.id(),Actor.Kind.valueOf((String)row.get("kind")),(UUID)row.get("customer_id"),(UUID)row.get("booking_id"),identity.authenticatedAt(),((Timestamp)row.get("expires_at")).toInstant()));
    }
    public void revoke(SessionIdentity identity){if(identity!=null&&identity.kind()!=Actor.Kind.ADMINISTRATOR)jdbc.update("UPDATE access_grant SET revoked_at=clock_timestamp() WHERE id=?",identity.id());}
    public UUID bootstrap(String email,char[] password){
        String normalized=Input.email(email);require(password!=null&&password.length>=15&&password.length<=128,"INVALID_PASSWORD");
        String hash;try{hash=passwords.encode(new String(password));}finally{Arrays.fill(password,'\0');}
        return transaction.execute(status->{
            jdbc.queryForObject("SELECT id FROM scheduling_resource WHERE id=1 FOR UPDATE",Integer.class);
            require(jdbc.queryForObject("SELECT count(*) FROM admin_user WHERE enabled",Integer.class)==0,"ADMIN_ALREADY_EXISTS");
            UUID id=UUID.randomUUID();jdbc.update("INSERT INTO admin_user(id,email_key,password_hash,totp_secret) VALUES(?,?,?,?)",id,normalized,hash,newSecret());return id;
        });
    }
    public Map<String,String> enrollment(UUID admin){
        var rows=jdbc.queryForList("SELECT email_key,totp_secret FROM admin_user WHERE id=? AND enabled AND NOT enrolled",admin);require(!rows.isEmpty(),"ENROLLMENT_UNAVAILABLE");
        byte[] bytes=Base64.getDecoder().decode(secrets.decrypt((String)rows.getFirst().get("totp_secret")));String secret=base32(bytes);
        String url="otpauth://totp/Purpuragenda:"+java.net.URLEncoder.encode((String)rows.getFirst().get("email_key"),StandardCharsets.UTF_8)+"?secret="+secret+"&issuer=Purpuragenda&algorithm=SHA1&digits=6&period=30";
        return Map.of("secret",secret,"uri",url);
    }
    public void completeEnrollment(UUID admin,String code){
        transaction.executeWithoutResult(status->{var rows=jdbc.queryForList("SELECT totp_secret FROM admin_user WHERE id=? AND enabled AND NOT enrolled FOR UPDATE",admin);require(!rows.isEmpty(),"ENROLLMENT_UNAVAILABLE");long step=validStep((String)rows.getFirst().get("totp_secret"),code,now(),-1);require(step>=0,"AUTHENTICATION_FAILED");jdbc.update("UPDATE admin_user SET enrolled=true,last_totp_step=? WHERE id=?",step,admin);});
    }
    public List<String> recoveryCodes(UUID admin){return transaction.execute(status->{jdbc.update("DELETE FROM admin_recovery_code WHERE admin_id=?",admin);List<String> codes=new ArrayList<>();for(int i=0;i<10;i++){String code=Secrets.token();codes.add(code);jdbc.update("INSERT INTO admin_recovery_code(id,admin_id,code_hash) VALUES(?,?,?)",UUID.randomUUID(),admin,Secrets.hash(code));}return List.copyOf(codes);});}
    public UUID ensureCustomer(Actor actor,String email){actor.requireAdmin();return transaction.execute(status->identity(Input.email(email),now()));}
    public void contactChallenge(UUID booking,String email,String nonce,String ip){
        throttle("email-ip/"+ip,30,Duration.ofHours(1));throttle("email-address/"+email,5,Duration.ofHours(1));throttle("email-cooldown/"+email,1,Duration.ofSeconds(60));
        transaction.executeWithoutResult(status->{Instant now=now();String token=Secrets.token();UUID id=UUID.randomUUID();jdbc.update("INSERT INTO email_challenge(id,token_hash,email_key,purpose,booking_id,browser_hash,expires_at) VALUES(?,?,?,'CONTACT',?,?,?)",id,Secrets.hash(token),email,booking,Secrets.hash(nonce),Timestamp.from(now.plusSeconds(900)));var envelope=Map.of("email",email,"purpose","CONTACT","url",baseUrl+"/booking/"+booking+"#contactToken="+token);jdbc.update("INSERT INTO notification_job(id,audience,template,due_at,encrypted_envelope,challenge_id) VALUES(?,'CUSTOMER','AUTH',?,?,?)",UUID.randomUUID(),Timestamp.from(now),secrets.encrypt(Json.write(envelope)),id);});
    }
    private UUID identity(String email,Instant now){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO customer_identity(id,email_key,verified_at) VALUES(?,?,?) ON CONFLICT(email_key) DO NOTHING",id,email,Timestamp.from(now));return jdbc.queryForObject("SELECT id FROM customer_identity WHERE email_key=?",UUID.class,email);}
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",(r,n)->r.getTimestamp(1).toInstant());}
    private void throttle(String key,int limit,Duration window){
        boolean allowed=Boolean.TRUE.equals(transaction.execute(status->{
            var count=jdbc.queryForObject("INSERT INTO rate_limit_bucket(key,count,reset_at) VALUES(?,1,clock_timestamp()+?*interval '1 second') ON CONFLICT(key) DO UPDATE SET count=CASE WHEN rate_limit_bucket.reset_at<=clock_timestamp() THEN 1 ELSE rate_limit_bucket.count+1 END,reset_at=CASE WHEN rate_limit_bucket.reset_at<=clock_timestamp() THEN excluded.reset_at ELSE rate_limit_bucket.reset_at END RETURNING count",Integer.class,Secrets.hash(key),window.toSeconds());return count<=limit;
        }));require(allowed,"RATE_LIMITED");
    }
    private String newSecret(){try{var generator=KeyGenerator.getInstance("HmacSHA1");generator.init(160);return secrets.encrypt(Base64.getEncoder().encodeToString(generator.generateKey().getEncoded()));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private long validStep(String encrypted,String code,Instant now,long last){
        if(code==null||!code.matches("[0-9]{6}"))return -1;
        try{var generator=new TimeBasedOneTimePasswordGenerator();var key=new SecretKeySpec(Base64.getDecoder().decode(secrets.decrypt(encrypted)),"HmacSHA1");long current=now.getEpochSecond()/30;for(long step=current-1;step<=current+1;step++)if(step>last&&java.security.MessageDigest.isEqual(code.getBytes(StandardCharsets.US_ASCII),String.format(Locale.ROOT,"%06d",generator.generateOneTimePassword(key,Instant.ofEpochSecond(step*30))).getBytes(StandardCharsets.US_ASCII)))return step;return -1;}catch(java.security.InvalidKeyException e){throw new IllegalStateException("TOTP_KEY_INVALID");}
    }
    private static String base32(byte[] bytes){String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";StringBuilder out=new StringBuilder();int buffer=0,bits=0;for(byte b:bytes){buffer=(buffer<<8)|(b&255);bits+=8;while(bits>=5){out.append(alphabet.charAt((buffer>>(bits-5))&31));bits-=5;}}if(bits>0)out.append(alphabet.charAt((buffer<<(5-bits))&31));return out.toString();}
}
