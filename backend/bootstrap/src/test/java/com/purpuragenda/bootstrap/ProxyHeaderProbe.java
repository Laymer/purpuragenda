package com.purpuragenda.bootstrap;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Isolated Caddy contract observer only; never packaged in the application artifact. */
public final class ProxyHeaderProbe {
    public static void main(String[] arguments) throws Exception {
        var server=HttpServer.create(new InetSocketAddress(8081),0);
        server.createContext("/",exchange->{
            String body="{\"status\":\"UP\"}";
            if(exchange.getRequestURI().getPath().startsWith("/api/"))
                body=String.join("|",List.of("X-Purpuragenda-Client-IP","Forwarded","X-Forwarded-For","X-Forwarded-Host","X-Forwarded-Proto")
                        .stream().map(name->{String value=exchange.getRequestHeaders().getFirst(name);return value==null?"-":value;}).toList());
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);
            try(var response=exchange.getResponseBody()){response.write(bytes);}
        });
        server.start();
    }
}
