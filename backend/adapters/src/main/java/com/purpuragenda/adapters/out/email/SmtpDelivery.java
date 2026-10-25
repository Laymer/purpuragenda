package com.purpuragenda.adapters.out.email;
import com.purpuragenda.application.port.out.*;
import org.springframework.mail.javamail.*;
import org.springframework.core.io.ByteArrayResource;

public final class SmtpDelivery implements MailDelivery {
    private final JavaMailSender sender;private final String from;
    public SmtpDelivery(JavaMailSender sender,String from){this.sender=sender;this.from=from;}
    public void send(NotificationQueue.Message message){try{var mail=sender.createMimeMessage();var helper=new MimeMessageHelper(mail,message.calendar()!=null,"UTF-8");helper.setFrom(from);helper.setTo(message.recipient());helper.setSubject(message.subject());helper.setText(message.text(),false);if(message.calendar()!=null)helper.addAttachment("appointment.ics",new ByteArrayResource(message.calendar()),"text/calendar; charset=UTF-8");mail.setHeader("Message-ID",message.messageId());sender.send(mail);}catch(jakarta.mail.MessagingException e){throw new IllegalStateException("MAIL_FAILED");}}
}
