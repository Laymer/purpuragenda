package com.purpuragenda.acceptance.api;

import com.purpuragenda.acceptance.runtime.AcceptanceRuntime;
import com.purpuragenda.adapters.codec.Json;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class BrowserJourney {
    static boolean completed;
    static void run(AcceptanceRuntime stack)throws Exception{
        String selected=System.getenv().getOrDefault("BROWSER","chromium"),viewport=System.getenv().getOrDefault("VIEWPORT","phone");
        List<String> browsers=selected.equals("all")?List.of("chromium","firefox","webkit","chrome","msedge"):List.of(selected);
        List<String> viewports=viewport.equals("all")?List.of("phone","tablet","desktop"):List.of(viewport);
        List<Cookie> ownerCookies=null;List<Map<String,Object>> manifest=new ArrayList<>();int cell=0;
        try(Playwright playwright=Playwright.create()){
            for(String name:browsers){BrowserType type=switch(name){case "firefox"->playwright.firefox();case "webkit"->playwright.webkit();default->playwright.chromium();};var launch=new BrowserType.LaunchOptions();
                if(name.equals("chrome")||name.equals("msedge")){String executable=System.getenv(name.equals("chrome")?"CHROME_EXECUTABLE":"EDGE_EXECUTABLE");if(executable!=null)launch.setExecutablePath(Path.of(executable));else launch.setChannel(name);}
                try(Browser browser=type.launch(launch)){
                    for(String size:viewports){int[] dimensions=switch(size){case "phone"->new int[]{390,844};case "tablet"->new int[]{768,1024};case "desktop"->new int[]{1440,900};default->throw new IllegalArgumentException("INVALID_VIEWPORT");};
                        try(BrowserContext guest=browser.newContext(new Browser.NewContextOptions().setViewportSize(dimensions[0],dimensions[1]).setReducedMotion(ReducedMotion.REDUCE));BrowserContext owner=browser.newContext(new Browser.NewContextOptions().setViewportSize(dimensions[0],dimensions[1]).setReducedMotion(ReducedMotion.REDUCE))){
                            if(ownerCookies!=null)owner.addCookies(ownerCookies);Page page=guest.newPage();page.setDefaultTimeout(15000);page.navigate(stack.base+"/book");
                            page.getByLabel("Consultation",new Page.GetByLabelOptions().setExact(true)).selectOption(cell%2==0?AcceptanceRuntime.KARMIC:AcceptanceRuntime.TRANSIT);
                            String date=stack.start(10+cell,9).atZone(com.purpuragenda.domain.scheduling.SchedulingPolicy.BUSINESS_ZONE).toLocalDate().toString();
                            page.waitForResponse(r->r.url().contains("/availability?")&&r.url().contains("from="+date),()->{page.getByLabel("Date in your timezone").fill(date);page.getByLabel("Date in your timezone").press("Tab");});
                            page.locator(".slot").first().click();page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Continue").setExact(false)).click();String email="browser-"+cell+"@example.test";
                            page.getByLabel("Email address",new Page.GetByLabelOptions().setExact(true)).fill(email);page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Send verification email")).click();
                            String link=stack.emailLink(email);Page verification=guest.newPage();verification.navigate(link);verification.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Verify email").setExact(true)).waitFor();assertTrue(java.net.URI.create(verification.url()).getFragment()==null,"Landing removes the credential fragment before interaction");
                            verification.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Verify email").setExact(true)).click();verification.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("You’re verified.")).waitFor();verification.close();
                            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("I’ve verified my email")).click();
                            page.getByLabel("First name",new Page.GetByLabelOptions().setExact(true)).fill("Alex");page.getByLabel("Last name",new Page.GetByLabelOptions().setExact(true)).fill("Synthetic");page.getByLabel("Telephone with country code").fill("+32400123456");page.getByLabel("Birth date",new Page.GetByLabelOptions().setExact(true)).fill("1990-01-01");page.getByLabel("Birth time",new Page.GetByLabelOptions().setExact(true)).fill("10:30:15");page.getByLabel("Birthplace",new Page.GetByLabelOptions().setExact(true)).fill("PRIVATE_BIRTHPLACE");
                            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Review request")).click();page.getByRole(AriaRole.CHECKBOX).check();page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Request appointment")).click();page.waitForURL("**/booking/*");page.locator(".status").getByText("pending confirmation",new Locator.GetByTextOptions().setExact(true)).waitFor();
                            String id=page.url().substring(page.url().lastIndexOf('/')+1);Page panel=owner.newPage();panel.setDefaultTimeout(15000);panel.navigate(stack.base+"/admin");
                            if(ownerCookies==null){panel.getByLabel("Administrator email").fill(stack.adminEmail);panel.getByLabel("Password",new Page.GetByLabelOptions().setExact(true)).fill(stack.adminPassword);panel.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Continue").setExact(true)).click();panel.getByLabel("Authenticator code",new Page.GetByLabelOptions().setExact(true)).fill(stack.totp(1));panel.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Sign in securely")).click();panel.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Sign out")).waitFor();ownerCookies=owner.cookies();}
                            panel.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Pending").setExact(true)).click();panel.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Open details")).first().click();panel.getByLabel("Unique Zoom participant URL").fill("https://zoom.us/j/"+(70000000000L+cell));
                            var approval=panel.waitForResponse(r->r.url().endsWith("/approvals"),()->panel.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Approve · leave payment unchanged")).click());assertEquals(200,approval.status());
                            page.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Refresh status")).click();page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Join private Zoom meeting").setExact(false)).waitFor();assertEquals("confirmed",page.locator(".status").innerText());
                            String calendar=guest.request().get(stack.base+"/api/v1/bookings/"+id+"/calendar").text();assertFalse(calendar.contains("PRIVATE_"));assertFalse(calendar.contains("token="));
                            String google=page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Add to Google Calendar").setExact(false)).getAttribute("href");assertFalse(google.contains("zoom"));assertFalse(google.contains("token"));assertFalse(google.contains("example.test"));
                            assertNoOverflow(page);assertNoOverflow(panel);axe(page,stack.root);axe(panel,stack.root);
                            Path screenshots=Path.of("target/screenshots");Files.createDirectories(screenshots);page.navigate(stack.base);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Find your time")).waitFor();page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve(name+"-"+size+"-public.png")).setFullPage(true));
                            manifest.add(Map.of("browser",name,"actualVersion",browser.version(),"viewport",size,"width",dimensions[0],"height",dimensions[1],"coreJourney","passed","seriousCriticalAxeFindings",0));cell++;
                        }
                    }
                }
            }
        }catch(Exception|AssertionError failure){String detail=Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()).replace(stack.adminPassword,"[REDACTED]").replaceAll("(?:token|contactToken)=[A-Za-z0-9_-]+","credential=[REDACTED]").replaceAll("fill\\(\"[^\"]*\"\\)","fill(\"[REDACTED]\")");throw new AssertionError("BROWSER_JOURNEY_FAILED: "+detail);}
        Files.writeString(Path.of("target/browser-manifest.json"),Json.write(manifest));completed=true;
    }
    private static void assertNoOverflow(Page page){assertTrue((Boolean)page.evaluate("document.documentElement.scrollWidth<=innerWidth"),"No horizontal page overflow");}
    private static void axe(Page page,Path root)throws Exception{
        Path script=root.resolve("frontend/node_modules/axe-core/axe.min.js");assertTrue(Files.isRegularFile(script),"Install pinned axe-core");page.evaluate(Files.readString(script));
        Object violations=page.evaluate("async()=>{const result=await axe.run(document,{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21aa','wcag22aa']}});return result.violations.filter(v=>['serious','critical'].includes(v.impact)).map(v=>v.id)}");assertEquals(List.of(),violations,"Zero serious or critical axe findings");
    }
}
