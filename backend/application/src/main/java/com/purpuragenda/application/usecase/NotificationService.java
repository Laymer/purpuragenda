package com.purpuragenda.application.usecase;
import com.purpuragenda.application.port.out.*;
public final class NotificationService {
    private final NotificationQueue queue;private final MailDelivery delivery;
    public NotificationService(NotificationQueue queue,MailDelivery delivery){this.queue=queue;this.delivery=delivery;}
    public void tick(){queue.expand();try(var heartbeat=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())){for(int i=0;i<10;i++){var job=queue.claim();if(job.isEmpty())return;if(!queue.renew(job.get()))continue;var lease=heartbeat.scheduleAtFixedRate(()->{try{queue.renew(job.get());}catch(RuntimeException ignored){}},15,15,java.util.concurrent.TimeUnit.SECONDS);try{var message=queue.prepare(job.get());if(message.isEmpty())continue;delivery.send(message.get());queue.delivered(job.get());}catch(RuntimeException e){queue.failed(job.get());}finally{lease.cancel(false);}}}}
}
