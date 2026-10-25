package com.purpuragenda.application.port.out;
import java.util.*;
public interface NotificationQueue {
    record Job(UUID id,UUID leaseToken){}
    record Message(String recipient,String subject,String text,String messageId,byte[] calendar){}
    void expand();
    Optional<Job> claim();
    boolean renew(Job job);
    Optional<Message> prepare(Job job);
    void delivered(Job job);
    void failed(Job job);
}
