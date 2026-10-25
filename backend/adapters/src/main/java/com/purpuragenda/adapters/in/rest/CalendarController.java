package com.purpuragenda.adapters.in.rest;
import com.purpuragenda.application.usecase.CalendarService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class CalendarController {
    private final CalendarService calendars;
    public CalendarController(CalendarService calendars){this.calendars=calendars;}
    @GetMapping({"/bookings/{id}/calendar","/admin/bookings/{id}/calendar"}) public ResponseEntity<byte[]> export(@PathVariable UUID id,HttpServletRequest request){return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8")).header("Content-Disposition","attachment; filename=appointment.ics").cacheControl(CacheControl.noStore()).body(calendars.export(SessionAccess.actor(request),id));}
}
