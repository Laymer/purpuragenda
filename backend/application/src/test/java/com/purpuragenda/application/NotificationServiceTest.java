package com.purpuragenda.application;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.usecase.NotificationService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationServiceTest {
    static class Queue implements NotificationQueue {
        ArrayDeque<Job> jobs=new ArrayDeque<>();List<UUID> sent=new ArrayList<>(),failed=new ArrayList<>();boolean suppress,leaseValid=true;int expanded;
        public void expand(){expanded++;}public Optional<Job> claim(){return Optional.ofNullable(jobs.poll());}public boolean renew(Job job){return leaseValid;}public Optional<Message> prepare(Job job){return suppress?Optional.empty():Optional.of(new Message("synthetic@example.test","Test","No private details",job.id().toString(),null));}public void delivered(Job job){sent.add(job.id());}public void failed(Job job){failed.add(job.id());}
    }
    @Test void deliveryFailureRemainsRetryableAndDoesNotHideLaterWork(){var queue=new Queue();var first=new NotificationQueue.Job(UUID.randomUUID(),UUID.randomUUID());var second=new NotificationQueue.Job(UUID.randomUUID(),UUID.randomUUID());queue.jobs.add(first);queue.jobs.add(second);var worker=new NotificationService(queue,message->{if(message.messageId().equals(first.id().toString()))throw new IllegalStateException("synthetic provider failure");});worker.tick();assertEquals(List.of(first.id()),queue.failed);assertEquals(List.of(second.id()),queue.sent);assertEquals(1,queue.expanded);}
    @Test void supersededMessagesAndLostLeasesAreNeverDispatched(){var queue=new Queue();queue.jobs.add(new NotificationQueue.Job(UUID.randomUUID(),UUID.randomUUID()));queue.suppress=true;new NotificationService(queue,message->fail("suppressed work must not send")).tick();assertTrue(queue.sent.isEmpty());queue.suppress=false;queue.leaseValid=false;queue.jobs.add(new NotificationQueue.Job(UUID.randomUUID(),UUID.randomUUID()));new NotificationService(queue,message->fail("a lost lease must not send")).tick();assertTrue(queue.sent.isEmpty());}
    @Test void oneTickHasBoundedWork(){var queue=new Queue();for(int i=0;i<11;i++)queue.jobs.add(new NotificationQueue.Job(UUID.randomUUID(),UUID.randomUUID()));new NotificationService(queue,message->{}).tick();assertEquals(10,queue.sent.size());assertEquals(1,queue.jobs.size());}
}
