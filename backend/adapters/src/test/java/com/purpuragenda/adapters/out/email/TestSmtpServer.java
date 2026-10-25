package com.purpuragenda.adapters.out.email;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;

/** Bounded real loopback SMTP/TLS peer. Captured messages remain private in memory. */
public final class TestSmtpServer implements AutoCloseable {
    final AtomicInteger accepted=new AtomicInteger(),auth=new AtomicInteger(),unsafeAuth=new AtomicInteger(),mail=new AtomicInteger(),secure=new AtomicInteger();
    final BlockingQueue<byte[]> messages=new LinkedBlockingQueue<>();
    final ServerSocket listener;
    final SSLContext tls;
    final boolean implicit,closeAfterData;
    final int recipientCode;
    final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    final List<Socket> sockets=new CopyOnWriteArrayList<>();
    public TestSmtpServer(SSLContext tls, boolean implicit, int recipientCode, boolean closeAfterData) throws IOException {
        this.tls=tls; this.implicit=implicit; this.recipientCode=recipientCode; this.closeAfterData=closeAfterData;
        InetAddress loopback=InetAddress.getByAddress(new byte[]{127,0,0,1});
        listener=implicit?tls.getServerSocketFactory().createServerSocket(0,20,loopback):new ServerSocket(0,20,loopback);
        workers.submit(()->{
            while(!listener.isClosed()) try {
                Socket connection=listener.accept(); accepted.incrementAndGet(); sockets.add(connection);
                workers.submit(()->conversation(connection));
            } catch(IOException stopped) { break; }
        });
    }
    public int port() { return listener.getLocalPort(); }
    public byte[] receive() throws InterruptedException { return messages.poll(5,TimeUnit.SECONDS); }
    public int acceptedCount() { return accepted.get(); }
    static void reply(BufferedWriter output,String text) throws IOException { output.write(text+"\r\n"); output.flush(); }
    void conversation(Socket initial) {
        Socket socket=initial; boolean encrypted=implicit,authenticated=false;
        try {
            socket.setSoTimeout(3000);
            if(implicit) { ((SSLSocket)socket).startHandshake(); secure.incrementAndGet(); }
            var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            var output=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));
            reply(output,"220 Synthetic SMTP");
            for(String line;(line=input.readLine())!=null;) {
                if(line.startsWith("EHLO") || line.startsWith("HELO")) {
                    reply(output,"250-synthetic.local");
                    if(tls!=null && !encrypted) reply(output,"250-STARTTLS");
                    if(tls!=null) reply(output,"250-AUTH PLAIN");
                    reply(output,"250 OK");
                } else if(line.equals("STARTTLS") && tls!=null && !encrypted) {
                    reply(output,"220 Begin TLS");
                    var wrapped=(SSLSocket)tls.getSocketFactory().createSocket(socket,"127.0.0.1",port(),true);
                    wrapped.setUseClientMode(false);
                    var parameters=wrapped.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    wrapped.setSSLParameters(parameters); wrapped.setSoTimeout(3000); wrapped.startHandshake();
                    socket=wrapped; encrypted=true; secure.incrementAndGet();
                    input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                    output=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));
                } else if(line.startsWith("AUTH")) {
                    auth.incrementAndGet(); if(!encrypted) unsafeAuth.incrementAndGet();
                    authenticated=encrypted; reply(output,encrypted?"235 Accepted":"530 TLS required");
                } else if(line.startsWith("MAIL FROM")) {
                    mail.incrementAndGet(); reply(output,tls!=null&&!authenticated?"530 Authentication required":"250 Accepted");
                } else if(line.startsWith("RCPT TO")) reply(output,recipientCode+" Synthetic response");
                else if(line.equals("DATA")) {
                    reply(output,"354 Continue"); var data=new ByteArrayOutputStream();
                    while((line=input.readLine())!=null && !line.equals(".")) {
                        if(line.startsWith("..")) line=line.substring(1);
                        data.write((line+"\r\n").getBytes(StandardCharsets.UTF_8));
                        if(data.size()>262144) throw new IOException("SMTP_FIXTURE_LIMIT");
                    }
                    messages.add(data.toByteArray()); reply(output,"250 Accepted");
                    if(closeAfterData) return;
                } else if(line.equals("RSET")) reply(output,"250 Reset");
                else if(line.equals("QUIT")) { reply(output,"221 Goodbye"); return; }
                else reply(output,"500 Unsupported");
            }
        } catch(IOException | RuntimeException expectedClientAbort) { /* Never expose private protocol data. */ }
        finally { try { socket.close(); } catch(IOException ignored) { } }
    }
    @Override public void close() throws IOException {
        listener.close();
        for(var socket:sockets) try { socket.close(); } catch(IOException ignored) { }
        workers.close(); messages.clear();
    }
}
