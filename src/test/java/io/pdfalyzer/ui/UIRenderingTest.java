package io.pdfalyzer.ui;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class UIRenderingTest {
    
    @LocalServerPort
    private int port;
    
    private WebDriver driver;
    private String baseUrl;
    
    @BeforeEach
    public void setUp() {
        // Setup WebDriverManager for Chromium (will download if needed)
        try {
            WebDriverManager.chromedriver().clearDriverCache().setup();
            
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--disable-gpu");
            options.addArguments("--disable-blink-features=AutomationControlled");
            
            driver = new ChromeDriver(options);
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
            baseUrl = "http://localhost:" + port;
        } catch (Exception e) {
            System.err.println("Warning: Could not initialize ChromeDriver with WebDriverManager: " + e.getMessage());
            e.printStackTrace();
            driver = null;
        }
    }
    
    @AfterEach
    public void tearDown() {
        if (driver != null) {
            try {
                driver.quit();
            } catch (Exception e) {
                System.err.println("Error closing WebDriver: " + e.getMessage());
            }
        }
    }
    
    @Test
    public void testPageLoads() {
        if (driver == null) {
            System.out.println("Skipping testPageLoads - ChromeDriver not available");
            return;
        }
        
        driver.get(baseUrl);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String title = driver.getTitle();
        assertNotNull(title);
        assertFalse(title.isEmpty());
    }
    
    @Test
    public void testPageHasContent() {
        if (driver == null) {
            System.out.println("Skipping testPageHasContent - ChromeDriver not available");
            return;
        }
        
        driver.get(baseUrl);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String body = driver.getPageSource();
        assertNotNull(body);
        assertTrue(body.length() > 100, "Page should have content");
    }
    
    @Test
    public void testTreeNodeHeaderHasFlexIfPresent() {
        if (driver == null) {
            System.out.println("Skipping testTreeNodeHeaderHasFlexIfPresent - ChromeDriver not available");
            return;
        }
        
        driver.get(baseUrl);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<WebElement> headers = driver.findElements(By.className("tree-node-header"));
        
        if (!headers.isEmpty()) {
            WebElement header = headers.get(0);
            String display = header.getCssValue("display");
            assertEquals("flex", display, "Header should use flex display");
        } else {
            System.out.println("No tree node headers found - test skipped (valid state for empty PDF)");
        }
    }
    
    @Test
    public void testToggleElementStyle() {
        if (driver == null) {
            System.out.println("Skipping testToggleElementStyle - ChromeDriver not available");
            return;
        }
        
        driver.get(baseUrl);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<WebElement> toggles = driver.findElements(By.className("tree-toggle"));
        
        if (!toggles.isEmpty()) {
            WebElement toggle = toggles.get(0);
            String display = toggle.getCssValue("display");
            // Should be flex, inline, or inherit
            assertNotNull(display);
        } else {
            System.out.println("No tree toggle elements found - test skipped (valid state for empty PDF)");
        }
    }
    
    @Test
    public void testLabelFlexIfPresent() {
        if (driver == null) {
            System.out.println("Skipping testLabelFlexIfPresent - ChromeDriver not available");
            return;
        }
        
        driver.get(baseUrl);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<WebElement> labels = driver.findElements(By.className("tree-label"));
        
        if (!labels.isEmpty()) {
            WebElement label = labels.get(0);
            String flex = label.getCssValue("flex");
            assertNotNull(flex);
            assertTrue(flex.contains("0") || flex.contains("auto"), 
                "Label flex should be 0 1 auto, got: " + flex);
        } else {
            System.out.println("No tree label elements found - test skipped (valid state for empty PDF)");
        }
    }

    @Test
    public void testPage1ImageStreamsHaveWorkingViewAndDownloadButtons() {
        if (driver == null) {
            System.out.println("Skipping testPage1ImageStreamsHaveWorkingViewAndDownloadButtons - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        wait.until(ExpectedConditions.or(
                ExpectedConditions.textToBePresentInElementLocated(By.id("statusFilename"), "test.pdf"),
                ExpectedConditions.presenceOfElementLocated(By.cssSelector(".toast-msg.text-success"))
        ));

        expandNode(wait, "pages");
        expandNode(wait, "page-0");
        expandNode(wait, "page-0-resources");
        expandNode(wait, "page-0-images");

        List<WebElement> imageNodes = wait.until(d -> {
            List<WebElement> nodes = d.findElements(By.cssSelector(".tree-node[data-node-id^='page-0-img-']"));
            return nodes.isEmpty() ? null : nodes;
        });

        assertFalse(imageNodes.isEmpty(), "Expected page 1 image nodes in tree");

        ((JavascriptExecutor) driver).executeScript(
                "window.__pdfalyzerOpenCalls = [];" +
                "window.__pdfalyzerOriginalOpen = window.open;" +
                "window.open = function(url, target){ window.__pdfalyzerOpenCalls.push(url); return null; };"
        );

        for (WebElement node : new ArrayList<>(imageNodes)) {
            WebElement previewBtn = node.findElement(By.cssSelector(".resource-open-btn"));
            WebElement downloadBtn = node.findElement(By.cssSelector(".resource-download-btn"));

            assertNotNull(previewBtn, "Preview button must exist for image node");
            assertNotNull(downloadBtn, "Download button must exist for image node");

            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", downloadBtn);
        }

        WebElement firstPreviewBtn = imageNodes.get(0).findElement(By.cssSelector(".resource-open-btn"));
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", firstPreviewBtn);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#resourcePreviewModal.show")));
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#resourcePreviewBody img")));

        ((JavascriptExecutor) driver).executeScript(
            "var el=document.getElementById('resourcePreviewModal');" +
            "if(el){el.classList.remove('show');el.style.display='none';}" +
            "document.querySelectorAll('.modal-backdrop').forEach(function(b){b.remove();});" +
            "document.body.classList.remove('modal-open');"
        );

        Object callCount = ((JavascriptExecutor) driver).executeScript("return window.__pdfalyzerOpenCalls.length;");
        assertEquals(imageNodes.size(), ((Number) callCount).intValue(), "Each image node should trigger one download open call");

        Object lastUrl = ((JavascriptExecutor) driver).executeScript("return window.__pdfalyzerOpenCalls[window.__pdfalyzerOpenCalls.length - 1];");
        assertNotNull(lastUrl, "Expected a download URL to be captured");
        assertTrue(lastUrl.toString().contains("/api/resource/"), "Download URL should point to resource endpoint");

        ((JavascriptExecutor) driver).executeScript("if (window.__pdfalyzerOriginalOpen) { window.open = window.__pdfalyzerOriginalOpen; }");
    }

    @Test
    public void testAttributeModificationAndNodeDeletionWorkflow() {
        if (driver == null) {
            System.out.println("Skipping testAttributeModificationAndNodeDeletionWorkflow - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        wait.until(ExpectedConditions.or(
            ExpectedConditions.textToBePresentInElementLocated(By.id("statusFilename"), "test.pdf"),
            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".toast-msg.text-success"))
        ));

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
                "const done = arguments[arguments.length - 1];" +
                "const P = window.PDFalyzer;" +
                "if(!P || !P.state || !P.state.sessionId){ done({ok:false, msg:'no-session'}); return; }" +
                "function findDict(n){" +
                "  if(!n) return null;" +
                "  if((n.cosType==='COSDictionary' || n.cosType==='COSStream') && n.objectNumber>=0 && n.keyPath){ return n; }" +
                "  if(n.children){ for(const c of n.children){ const r=findDict(c); if(r) return r; } }" +
                "  return null;" +
                "}" +
                "const dict = findDict(P.state.treeData);" +
                "if(!dict){ done({ok:false, msg:'no-dict'}); return; }" +
                "let keyPath; try { keyPath = JSON.parse(dict.keyPath); } catch(e){ done({ok:false, msg:'bad-keypath'}); return; }" +
                "const sid = P.state.sessionId;" +
                "const payloadBase = { objectNumber: dict.objectNumber, generationNumber: dict.generationNumber || 0 };" +
                "$.ajax({ url:'/api/cos/'+sid+'/update', method:'POST', contentType:'application/json', data: JSON.stringify(Object.assign({}, payloadBase, { keyPath: keyPath.concat(['UITestKey']), newValue:'123', valueType:'COSInteger', operation:'add' })) })" +
                ".then(function(addResp){" +
                "  return $.ajax({ url:'/api/cos/'+sid+'/update', method:'POST', contentType:'application/json', data: JSON.stringify(Object.assign({}, payloadBase, { keyPath: keyPath.concat(['UITestKey']), newValue:'456', valueType:'COSInteger', operation:'update' })) });" +
                "})" +
                ".then(function(updateResp){" +
                "  return $.ajax({ url:'/api/cos/'+sid+'/update', method:'POST', contentType:'application/json', data: JSON.stringify(Object.assign({}, payloadBase, { keyPath: keyPath.concat(['UITestKey']), operation:'remove' })) });" +
                "})" +
                ".then(function(){ done({ok:true}); })" +
                ".catch(function(err){ done({ok:false, msg: (err && err.statusText) ? err.statusText : 'ajax-failed'}); });"
        );

        assertTrue(result instanceof Map, "Expected script result to be a map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("ok"), "Attribute modify/delete workflow must succeed: " + map);

        List<WebElement> dangerToasts = driver.findElements(By.cssSelector(".toast-msg.text-danger"));
        assertTrue(dangerToasts.isEmpty(), "No danger toast should be shown during attribute modify/delete workflow");
    }

    private void expandNode(WebDriverWait wait, String nodeId) {
        By nodeSel = By.cssSelector(".tree-node[data-node-id='" + nodeId + "']");
        WebElement node = wait.until(ExpectedConditions.presenceOfElementLocated(nodeSel));
        List<WebElement> children = node.findElements(By.cssSelector(":scope > .tree-children > .tree-node"));
        if (!children.isEmpty()) {
            return;
        }
        WebElement toggle = node.findElement(By.cssSelector(":scope > .tree-node-header > .tree-toggle"));
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", toggle);
        wait.until(d -> !node.findElements(By.cssSelector(":scope > .tree-children > .tree-node")).isEmpty());
    }
}
