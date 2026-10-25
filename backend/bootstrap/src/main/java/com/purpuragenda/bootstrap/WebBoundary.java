package com.purpuragenda.bootstrap;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import com.purpuragenda.adapters.out.security.Secrets;

/** Bounds API bodies and enforces canonical browser origins before JSON parsing. */
final class WebBoundary extends OncePerRequestFilter {
    private final String origin;private final boolean local;private final JdbcTemplate jdbc;
    WebBoundary(String baseUrl,String mode,JdbcTemplate jdbc){URI uri=URI.create(baseUrl);origin=uri.getScheme()+"://"+uri.getRawAuthority();local=Set.of("local","test").contains(mode);this.jdbc=jdbc;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        if(!request.getRequestURI().startsWith("/api/")){chain.doFilter(request,response);return;}
        response.setHeader("Cache-Control","no-store");
        if(request.getRequestURI().equals("/api/v1/availability")){
            Integer count=jdbc.queryForObject("INSERT INTO rate_limit_bucket(key,count,reset_at) VALUES(?,1,clock_timestamp()+interval '1 minute') ON CONFLICT(key) DO UPDATE SET count=CASE WHEN rate_limit_bucket.reset_at<=clock_timestamp() THEN 1 ELSE rate_limit_bucket.count+1 END,reset_at=CASE WHEN rate_limit_bucket.reset_at<=clock_timestamp() THEN excluded.reset_at ELSE rate_limit_bucket.reset_at END RETURNING count",Integer.class,Secrets.hash("availability/"+request.getRemoteAddr()));
            if(count>120){response.setHeader("Retry-After","60");error(response,429,"RATE_LIMITED");return;}
        }
        if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())){
            String source=request.getHeader("Origin");
            boolean devOrigin=local&&Set.of("http://localhost:4200","http://127.0.0.1:4200").contains(source==null?"":source);
            if(source!=null&&!source.equals(origin)&&!devOrigin){error(response,403,"FORBIDDEN");return;}
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT maintenance FROM operation_control WHERE id=1",Boolean.class))){response.setHeader("Retry-After","60");error(response,503,"MAINTENANCE");return;}
            byte[] data=request.getInputStream().readNBytes(65537);if(data.length>65536){error(response,413,"BODY_TOO_LARGE");return;}
            var bytes=new ByteArrayInputStream(data);
            HttpServletRequest wrapped=new HttpServletRequestWrapper(request){
                @Override public ServletInputStream getInputStream(){return new ServletInputStream(){public int read(){return bytes.read();}public int read(byte[] b,int off,int len){return bytes.read(b,off,len);}public boolean isFinished(){return bytes.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}};}
                @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
            };
            chain.doFilter(wrapped,response);return;
        }
        chain.doFilter(request,response);
    }
    private static void error(HttpServletResponse response,int status,String code)throws IOException{response.setStatus(status);response.setContentType("application/problem+json");response.getWriter().write("{\"status\":"+status+",\"code\":\""+code+"\"}");}
}
