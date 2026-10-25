package com.purpuragenda.acceptance.api;
import com.purpuragenda.acceptance.runtime.AcceptanceRuntime;
import com.purpuragenda.adapters.codec.Json;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class PerformanceJourney {
    static void run(AcceptanceRuntime stack)throws Exception{
        List<Double> lcps=new ArrayList<>(),shifts=new ArrayList<>(),feedback=new ArrayList<>();
        try(Playwright p=Playwright.create();Browser browser=p.chromium().launch()){
            for(int i=0;i<9;i++)try(BrowserContext context=browser.newContext(new Browser.NewContextOptions().setViewportSize(390,844))){
                context.addInitScript("window.__lab={lcp:0,cls:0};new PerformanceObserver(l=>{for(const e of l.getEntries())window.__lab.lcp=e.startTime}).observe({type:'largest-contentful-paint',buffered:true});new PerformanceObserver(l=>{for(const e of l.getEntries())if(!e.hadRecentInput)window.__lab.cls+=e.value}).observe({type:'layout-shift',buffered:true});");
                Page page=context.newPage();CDPSession cdp=context.newCDPSession(page);cdp.send("Network.enable");send(cdp,"Network.setCacheDisabled",Map.of("cacheDisabled",true));send(cdp,"Network.emulateNetworkConditions",Map.of("offline",false,"latency",150,"downloadThroughput",1_600_000/8,"uploadThroughput",750_000/8));send(cdp,"Emulation.setCPUThrottlingRate",Map.of("rate",4));
                page.navigate(stack.base);page.getByText("Karmic reading",new Page.GetByTextOptions().setExact(true)).waitFor();page.waitForTimeout(300);Map<?,?> metrics=(Map<?,?>)page.evaluate("window.__lab");lcps.add(((Number)metrics.get("lcp")).doubleValue());shifts.add(((Number)metrics.get("cls")).doubleValue());
                page.navigate(stack.base+"/book");page.locator(".slot").first().waitFor();page.waitForFunction("!document.querySelector('mat-progress-bar')");Number milliseconds=(Number)page.evaluate("()=>new Promise((resolve,reject)=>{const start=performance.now();const observer=new MutationObserver(()=>{if(document.querySelector('mat-progress-bar')){observer.disconnect();resolve(performance.now()-start)}});observer.observe(document.body,{childList:true,subtree:true});const field=document.querySelector('#service');field.value='10000000-0000-0000-0000-000000000002';field.dispatchEvent(new Event('change',{bubbles:true}));setTimeout(()=>{observer.disconnect();reject(new Error('No immediate loading feedback'))},3000)})");feedback.add(milliseconds.doubleValue());
            }
        }
        double lcp=percentile(lcps,.75),cls=percentile(shifts,.75),interaction=percentile(feedback,.95);Files.writeString(Path.of("target/performance-summary.json"),Json.write(Map.of("scope","controlled local browser lab; not field metrics","runs",9,"viewport","390x844","cpuSlowdown",4,"downBitsPerSecond",1600000,"upBitsPerSecond",750000,"roundTripLatencyMs",150,"lcpP75Ms",lcp,"clsP75",cls,"localFeedbackP95Ms",interaction)));
        assertTrue(lcps.stream().allMatch(v->v>0),"All cold runs must report LCP");assertTrue(lcp<=2500,"p75 LCP<=2.5s");assertTrue(cls<=.1,"p75 CLS<=0.1");assertTrue(interaction<=100,"Local loading feedback p95<=100ms");
    }
    private static void send(CDPSession session,String method,Map<String,Object> values){session.send(method,com.google.gson.JsonParser.parseString(Json.write(values)).getAsJsonObject());}
    private static double percentile(List<Double> values,double percentile){var ordered=new ArrayList<>(values);Collections.sort(ordered);return ordered.get((int)Math.ceil(ordered.size()*percentile)-1);}
}
