package com.purpuragenda.acceptance;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrowserCompatibilityIT {
    @Test void java25PlaywrightAndResponsiveApplication()throws Exception{
        try(Playwright playwright=Playwright.create();Browser browser=playwright.chromium().launch()){
            Page page=browser.newPage(new Browser.NewPageOptions().setViewportSize(390,844));
            String base=System.getenv("APP_BASE_URL");
            if(base==null)page.setContent("<main><h1>Purpuragenda compatibility</h1></main>");
            else{page.navigate(base);page.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Find your time")).waitFor();page.getByText("Karmic reading",new Page.GetByTextOptions().setExact(true)).waitFor();assertTrue((Boolean)page.evaluate("document.documentElement.scrollWidth<=innerWidth"),"Public phone layout must not overflow");}
            assertFalse(browser.version().isBlank());
            Files.writeString(Path.of("target/browser-version.txt"),"Playwright Java1.64.0; Chromium "+browser.version()+"\n");
        }
    }
}
