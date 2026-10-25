package com.purpuragenda.adapters.out.calendar;

import com.purpuragenda.application.port.out.CalendarWriter;
import com.purpuragenda.domain.booking.Booking;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.*;
import net.fortuna.ical4j.data.CalendarOutputter;

public final class IcalendarWriter implements CalendarWriter {
    public byte[] write(Booking booking,String audience,Instant generatedAt){
        Calendar calendar=new Calendar();calendar.add(new ProdId("-//Purpuragenda//Private consultations//EN"));calendar.add(new Version("2.0","2.0"));calendar.add(new CalScale("GREGORIAN"));
        VEvent event=new VEvent(booking.start(),booking.sessionEnd(),"Private consultation with Medina");
        event.add(new Uid(UUID.nameUUIDFromBytes((booking.id()+"/"+audience).getBytes(StandardCharsets.UTF_8)).toString()+"@purpuragenda"));
        event.add(new Sequence(booking.calendarRevision()));event.replace(new DtStamp(generatedAt));
        event.add(new Status(booking.state()==Booking.State.CANCELLED?"CANCELLED":"CONFIRMED"));
        if(booking.meeting()!=null){event.add(new Location(booking.meeting().participantUrl()));event.add(new Description("Private Zoom consultation. "+booking.meeting().participantUrl()+"\nCalendar imports may require manual updates."));}
        calendar.add(event);
        try{var output=new java.io.ByteArrayOutputStream();new CalendarOutputter().output(calendar,output);return foldUtf8(output.toByteArray());}catch(java.io.IOException e){throw new IllegalStateException("CALENDAR_FAILED");}
    }
    // iCal4j serializes/escapes properties. Re-fold by UTF-8 octets (RFC5545 section3.1), never inside a code point.
    static byte[] foldUtf8(byte[] input){String unfolded=new String(input,StandardCharsets.UTF_8).replace("\r\n ","").replace("\r\n\t","");StringBuilder result=new StringBuilder();for(String line:unfolded.split("\\r?\\n")){int width=0;for(int at=0;at<line.length();){int cp=line.codePointAt(at);String text=new String(Character.toChars(cp));int bytes=text.getBytes(StandardCharsets.UTF_8).length;if(width+bytes>75){result.append("\r\n ");width=1;}result.append(text);width+=bytes;at+=Character.charCount(cp);}result.append("\r\n");}return result.toString().getBytes(StandardCharsets.UTF_8);}
}
