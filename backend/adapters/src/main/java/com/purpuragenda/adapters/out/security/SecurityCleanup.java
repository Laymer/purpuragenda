package com.purpuragenda.adapters.out.security;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Component @ConditionalOnProperty(name="app.jobs.enabled",havingValue="true",matchIfMissing=true)
public class SecurityCleanup {
    private final JdbcTemplate jdbc;
    public SecurityCleanup(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Scheduled(cron="0 0 * * * *") public void cleanup(){
        jdbc.update("UPDATE notification_job SET encrypted_envelope=NULL WHERE challenge_id IN(SELECT id FROM email_challenge WHERE expires_at<clock_timestamp())");
        jdbc.update("UPDATE notification_job SET challenge_id=NULL WHERE challenge_id IN(SELECT id FROM email_challenge WHERE expires_at<clock_timestamp()-interval '24 hours')");
        jdbc.update("DELETE FROM email_challenge WHERE expires_at<clock_timestamp()-interval '24 hours'");
        jdbc.update("DELETE FROM privacy_export WHERE expires_at<clock_timestamp()");
        jdbc.update("DELETE FROM rate_limit_bucket WHERE reset_at<clock_timestamp()-interval '24 hours'");
        jdbc.update("DELETE FROM delivery_attempt WHERE attempted_at<clock_timestamp()-interval '90 days'");
    }
}
