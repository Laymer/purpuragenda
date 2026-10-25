package com.purpuragenda.bootstrap;

import com.purpuragenda.application.usecase.IdentityService;
import com.purpuragenda.adapters.out.security.SessionFilter;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

@Configuration @ConditionalOnWebApplication
public class WebSecurity {
    @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<WebBoundary> boundary(@org.springframework.beans.factory.annotation.Value("${app.public-base-url}")String url,@org.springframework.beans.factory.annotation.Value("${app.mode}")String mode,org.springframework.jdbc.core.JdbcTemplate jdbc){var bean=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new WebBoundary(url,mode,jdbc));bean.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+10);return bean;}
    @Bean org.springframework.security.core.userdetails.UserDetailsService noDefaultLogin(){return username->{throw new org.springframework.security.core.userdetails.UsernameNotFoundException("PASSWORD_LOGIN_UNAVAILABLE");};}
    @Bean SecurityFilterChain security(HttpSecurity http,IdentityService identity)throws Exception{
        var csrf=new HttpSessionCsrfTokenRepository();
        http.csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(a->a.requestMatchers("/api/v1/security/csrf","/api/v1/services","/api/v1/availability","/api/v1/me","/api/v1/auth/**","/api/v1/admin/auth/**","/actuator/health","/actuator/health/**").permitAll()
              .requestMatchers("/api/v1/admin/**").hasRole("ADMINISTRATOR").requestMatchers("/api/**").authenticated().anyRequest().denyAll())
            .addFilterBefore(new SessionFilter(identity),AnonymousAuthenticationFilter.class)
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->error(res,401,"UNAUTHENTICATED")).accessDeniedHandler((req,res,ex)->error(res,403,ex instanceof org.springframework.security.web.csrf.CsrfException?"CSRF_REQUIRED":"FORBIDDEN")))
            .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"))
                .referrerPolicy(r->r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
            .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable());
        return http.build();
    }
    private static void error(jakarta.servlet.http.HttpServletResponse res,int status,String code)throws IOException{res.setStatus(status);res.setContentType("application/problem+json");res.setHeader("Cache-Control","no-store");res.getWriter().write("{\"status\":"+status+",\"code\":\""+code+"\"}");}
}
