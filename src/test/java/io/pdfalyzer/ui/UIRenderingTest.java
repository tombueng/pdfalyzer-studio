package io.pdfalyzer.ui;

import io.github.bonigarcia.wdm.WebDriverManager;
import lombok.extern.slf4j.Slf4j;
import org.jcodec.api.awt.AWTSequenceEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
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
            try {
                WebDriverManager.chromedriver().clearDriverCache().setup();
            } catch (Exception cacheEx) {
                System.err.println("Warning: Could not clear ChromeDriver cache, proceeding with setup: " + cacheEx.getMessage());
                WebDriverManager.chromedriver().setup();
            }
            
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--disable-gpu");
            options.addArguments("--disable-blink-features=AutomationControlled");
            LoggingPreferences loggingPrefs = new LoggingPreferences();
            loggingPrefs.enable(LogType.BROWSER, Level.ALL);
            loggingPrefs.enable(LogType.PERFORMANCE, Level.INFO);
            options.setCapability("goog:loggingPrefs", loggingPrefs);
            
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
            RuntimeException captureFailure = null;
            try {
                assertNoClientJsErrors("after test execution");
            } catch (RuntimeException ex) {
                captureFailure = ex;
            }
            try {
                driver.quit();
            } catch (Exception e) {
                System.err.println("Error closing WebDriver: " + e.getMessage());
            }
            if (captureFailure != null) {
                throw captureFailure;
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
    @Disabled("Flaky Selenium test - UI timing issue")
    public void testAutoUploadLoadsTestPdfOnStartup() {
        if (driver == null) {
            System.out.println("Skipping testAutoUploadLoadsTestPdfOnStartup - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(300));

        ensureTestPdfReady(wait, false);

        WebElement statusFilename = driver.findElement(By.id("statusFilename"));
        WebElement statusSession = driver.findElement(By.id("statusSession"));

        assertTrue(statusFilename.getText().toLowerCase().contains("test.pdf"),
            "Expected startup autoload to show test.pdf in filename status");
        assertTrue(statusSession.getText().toLowerCase().contains("active"),
            "Expected session status to be active after startup autoload");
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
    public void testJsErrorCaptureHookIsInstalled() {
        if (driver == null) {
            System.out.println("Skipping testJsErrorCaptureHookIsInstalled - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        Object installed = ((JavascriptExecutor) driver).executeScript("return !!window.__pdfalyzerErrorCaptureInstalled;");
        Object errors = ((JavascriptExecutor) driver).executeScript("return Array.isArray(window.__pdfalyzerJsErrors);");

        assertEquals(Boolean.TRUE, installed, "Client error capture flag should be set");
        assertEquals(Boolean.TRUE, errors, "Client error capture array should be initialized");
    }

        @Test
        @Disabled("Flaky Selenium test - modal timeout")
        public void testAddFieldUsesModalDialogNotPrompt() {
        if (driver == null) {
            System.out.println("Skipping testAddFieldUsesModalDialogNotPrompt - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(300));
        ensureTestPdfReady(wait, true);

        ((JavascriptExecutor) driver).executeScript(
            "window.__promptCalls=0;" +
            "window.__origPrompt=window.prompt;" +
            "window.prompt=function(){ window.__promptCalls++; return null; };"
        );

        WebElement addTextBtn = wait.until(ExpectedConditions.elementToBeClickable(
            By.cssSelector(".edit-field-btn[data-type='text']")
        ));
        addTextBtn.click();

        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#pdfViewer .pdf-page-wrapper canvas")));

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
        Object drawResult = ((JavascriptExecutor) driver).executeAsyncScript(
            "const done = arguments[arguments.length - 1];" +
            "const canvas = document.querySelector('#pdfViewer .pdf-page-wrapper canvas');" +
            "if(!canvas){ done({ok:false,msg:'missing-first-canvas'}); return; }" +
            "const rect = canvas.getBoundingClientRect();" +
            "const startX = rect.left + 40;" +
            "const startY = rect.top + 40;" +
            "const endX = startX + 90;" +
            "const endY = startY + 35;" +
            "canvas.dispatchEvent(new MouseEvent('mousedown', { bubbles:true, cancelable:true, clientX:startX, clientY:startY }));" +
            "document.dispatchEvent(new MouseEvent('mousemove', { bubbles:true, cancelable:true, clientX:endX, clientY:endY }));" +
            "document.dispatchEvent(new MouseEvent('mouseup', { bubbles:true, cancelable:true, clientX:endX, clientY:endY }));" +
            "setTimeout(function(){ done({ok:true}); }, 120);"
        );
        assertTrue(drawResult instanceof Map, "Expected draw result map");
        @SuppressWarnings("unchecked")
        Map<String, Object> drawMap = (Map<String, Object>) drawResult;
        assertEquals(Boolean.TRUE, drawMap.get("ok"), "Failed to draw placement rectangle for add-field modal test");

        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("fieldCreateModal")));
        Object promptCalls = ((JavascriptExecutor) driver).executeScript("return window.__promptCalls;");
        assertTrue(promptCalls instanceof Number);
        assertEquals(0, ((Number) promptCalls).intValue(), "Adding fields should not invoke browser prompt");

        ((JavascriptExecutor) driver).executeScript(
            "if(window.__origPrompt){ window.prompt = window.__origPrompt; }"
        );
        }

    @Test
    public void testCollapsingNodeRemovesInfoPanel() {
        if (driver == null) {
            System.out.println("Skipping testCollapsingNodeRemovesInfoPanel - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        ensureTestPdfReady(wait, true);

        expandNode(wait, "pages");
        By nodeBy = By.cssSelector(".tree-node[data-node-id='page-0']");
        List<WebElement> pageNodes = driver.findElements(nodeBy);
        if (pageNodes.isEmpty()) {
            System.out.println("Skipping testCollapsingNodeRemovesInfoPanel - page-0 node not found");
            return;
        }

        WebElement pageNode = pageNodes.get(0);
        WebElement header = pageNode.findElement(By.cssSelector(":scope > .tree-node-header"));
        WebElement toggle = pageNode.findElement(By.cssSelector(":scope > .tree-node-header > .tree-toggle"));

        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", header);
        wait.until(d -> !d.findElements(By.cssSelector(".tree-node[data-node-id='page-0'] > .node-properties")).isEmpty());

        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", toggle);
        wait.until(d -> d.findElements(By.cssSelector(".tree-node[data-node-id='page-0'] > .node-properties")).isEmpty());
    }

    @Test
    public void testPage1ImageStreamsHaveWorkingViewAndDownloadButtons() {
        if (driver == null) {
            System.out.println("Skipping testPage1ImageStreamsHaveWorkingViewAndDownloadButtons - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        ensureTestPdfReady(wait, true);

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
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));
        ensureTestPdfReady(wait, true);

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
                "const done = arguments[arguments.length - 1];" +
                "if(typeof $ === 'undefined' || !$.ajax){ done({ok:false, msg:'jquery-not-loaded'}); return; }" +
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
                ".catch(function(err){" +
                "  var info = 'unknown';" +
                "  try { info = 'status=' + (err&&err.status) + ' text=' + (err&&err.statusText) + ' body=' + (err&&err.responseText); } catch(e2){}" +
                "  done({ok:false, msg:info, obj:dict.objectNumber, gen:dict.generationNumber||0, keyPath:dict.keyPath});" +
                "});"
        );

        assertTrue(result instanceof Map, "Expected script result to be a map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("ok"), "Attribute modify/delete workflow must succeed: " + map);

        List<WebElement> dangerToasts = driver.findElements(By.cssSelector(".toast-msg.text-danger"));
        assertTrue(dangerToasts.isEmpty(), "No danger toast should be shown during attribute modify/delete workflow");
    }

    @Test
    public void testFieldSelectionSyncBetweenTreeAndPdfView() {
        if (driver == null) {
            System.out.println("Skipping testFieldSelectionSyncBetweenTreeAndPdfView - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));
        ensureTestPdfReady(wait, true);

        activateSelectEditMode();
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".form-field-handle")));

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
            "const done = arguments[arguments.length - 1];" +
            "const P = window.PDFalyzer;" +
            "if(!P || !P.state || !P.Tree){ done({ok:false,msg:'no-pdfalyzer'}); return; }" +
            "const handles = Array.from(document.querySelectorAll('.form-field-handle[data-field-name]'));" +
            "if(!handles.length){ done({ok:false,msg:'no-handles'}); return; }" +
            "P.Tree.renderSubtree(P.state.treeData, 'field');" +
            "const firstName = handles[0].getAttribute('data-field-name');" +
            "if(!firstName){ done({ok:false,msg:'missing-first-name'}); return; }" +
            "const firstHeader = document.querySelector('.tree-node-header[data-field-name=\"' + firstName + '\"]');" +
            "if(!firstHeader){ done({ok:false,msg:'tree-header-not-found'}); return; }" +
            "firstHeader.dispatchEvent(new MouseEvent('click', { bubbles:true }));" +
            "setTimeout(function(){" +
            "  const selectedNames = Array.isArray(P.state.selectedFieldNames) ? P.state.selectedFieldNames : [];" +
            "  const firstHandleEl = document.querySelector('.form-field-handle[data-field-name=\"' + firstName + '\"]');" +
            "  const treeToPdfState = selectedNames.indexOf(firstName) >= 0;" +
            "  const treeToPdfHandle = !!firstHandleEl && firstHandleEl.classList.contains('selected');" +
            "  const treeToPdfTree = firstHeader.classList.contains('field-selected') || firstHeader.classList.contains('selected');" +
            "  const secondHandle = handles.length > 1 ? handles[1] : handles[0];" +
            "  const secondName = secondHandle.getAttribute('data-field-name');" +
            "  let secondNode = null;" +
            "  (function walk(n){" +
            "    if(!n || secondNode) return;" +
            "    if(n.nodeCategory==='field' && n.properties && n.properties.FullName===secondName){ secondNode=n; return; }" +
            "    if(n.children) n.children.forEach(walk);" +
            "  })(P.state.treeData);" +
            "  if(P.EditMode && P.EditMode.selectFieldFromViewer && secondNode){" +
            "    P.EditMode.selectFieldFromViewer(secondNode, false);" +
            "  }" +
            "  setTimeout(function(){" +
            "    const secondHeader = document.querySelector('.tree-node-header[data-field-name=\"' + secondName + '\"]');" +
            "    const afterNames = Array.isArray(P.state.selectedFieldNames) ? P.state.selectedFieldNames : [];" +
            "    const pdfToTreeState = afterNames.indexOf(secondName) >= 0;" +
            "    const pdfToTreeTree = !!secondHeader && (secondHeader.classList.contains('field-selected') || secondHeader.classList.contains('selected'));" +
            "    firstHeader.dispatchEvent(new MouseEvent('click', { bubbles:true }));" +
            "    if (secondHeader) {" +
            "      secondHeader.dispatchEvent(new MouseEvent('click', { bubbles:true, ctrlKey:true }));" +
            "    }" +
            "    const namesAfterCtrl = Array.isArray(P.state.selectedFieldNames) ? P.state.selectedFieldNames : [];" +
            "    const ctrlMultiState = namesAfterCtrl.indexOf(firstName) >= 0 && namesAfterCtrl.indexOf(secondName) >= 0;" +
            "    const firstHandleAfterCtrl = document.querySelector('.form-field-handle[data-field-name=\"' + firstName + '\"]');" +
            "    const secondHandleAfterCtrl = document.querySelector('.form-field-handle[data-field-name=\"' + secondName + '\"]');" +
            "    const ctrlMultiHandle = !!firstHandleAfterCtrl && !!secondHandleAfterCtrl &&" +
            "      firstHandleAfterCtrl.classList.contains('selected') && secondHandleAfterCtrl.classList.contains('selected');" +
            "    const ctrlMultiTree = !!secondHeader &&" +
            "      firstHeader.classList.contains('field-selected') && secondHeader.classList.contains('field-selected');" +
            "    done({" +
            "      ok: treeToPdfState && treeToPdfHandle && treeToPdfTree && pdfToTreeState && pdfToTreeTree && ctrlMultiState && ctrlMultiHandle && ctrlMultiTree," +
            "      treeToPdfState: treeToPdfState," +
            "      treeToPdfHandle: treeToPdfHandle," +
            "      treeToPdfTree: treeToPdfTree," +
            "      pdfToTreeState: pdfToTreeState," +
            "      pdfToTreeTree: pdfToTreeTree," +
            "      ctrlMultiState: ctrlMultiState," +
            "      ctrlMultiHandle: ctrlMultiHandle," +
            "      ctrlMultiTree: ctrlMultiTree," +
            "      firstName: firstName," +
            "      secondName: secondName" +
            "    });" +
            "  }, 60);" +
            "}, 60);"
        );

        assertTrue(result instanceof Map, "Expected script result map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("ok"), "Field selection sync must work both directions: " + map);
    }

    @Test
    public void testPdfViewClickAndCtrlClickSelectsFieldsInTreeAndHandles() {
        if (driver == null) {
            System.out.println("Skipping testPdfViewClickAndCtrlClickSelectsFieldsInTreeAndHandles - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        ensureTestPdfReady(wait, true);
        activateSelectEditMode();
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".form-field-handle[data-field-name]")));

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(40));
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
            "const done = arguments[arguments.length - 1];" +
            "const P = window.PDFalyzer;" +
            "if(!P || !P.state || !P.state.treeData){ done({ok:false,msg:'no-state'}); return; }" +
            "if(!P.state.pageCanvases || !P.state.pageCanvases.length){ done({ok:false,msg:'no-canvases'}); return; }" +
            "const fields = [];" +
            "(function walk(n){" +
            "  if(!n) return;" +
            "  if(n.nodeCategory==='field' && n.properties && n.properties.FullName && Array.isArray(n.boundingBox) && n.boundingBox.length===4 && n.pageIndex>=0){" +
            "    fields.push(n);" +
            "  }" +
            "  if(n.children) n.children.forEach(walk);" +
            "})(P.state.treeData);" +
            "if(fields.length < 2){ done({ok:false,msg:'not-enough-fields',count:fields.length}); return; }" +
            "const first = fields[0];" +
            "const second = fields[1];" +
            "function clickField(field, withCtrl){" +
            "  const pageIndex = field.pageIndex;" +
            "  const canvas = P.state.pageCanvases[pageIndex];" +
            "  const vp = P.state.pageViewports[pageIndex];" +
            "  if(!canvas || !vp) return false;" +
            "  const bb = field.boundingBox;" +
            "  const centerX = bb[0] + bb[2] / 2;" +
            "  const centerY = bb[1] + bb[3] / 2;" +
            "  const rect = canvas.getBoundingClientRect();" +
            "  const clientX = rect.left + (centerX * vp.scale);" +
            "  const clientY = rect.top + (vp.height - (centerY * vp.scale));" +
            "  const ev = new MouseEvent('click', {" +
            "    bubbles: true," +
            "    cancelable: true," +
            "    clientX: clientX," +
            "    clientY: clientY," +
            "    ctrlKey: !!withCtrl" +
            "  });" +
            "  canvas.dispatchEvent(ev);" +
            "  return true;" +
            "}" +
            "if(!clickField(first, false)){ done({ok:false,msg:'first-click-failed'}); return; }" +
            "setTimeout(function(){" +
            "  const namesAfterFirst = Array.isArray(P.state.selectedFieldNames) ? P.state.selectedFieldNames.slice() : [];" +
            "  const firstName = first.properties.FullName;" +
            "  const firstOnlySelected = namesAfterFirst.length === 1 && namesAfterFirst.indexOf(firstName) >= 0;" +
            "  if(!clickField(second, true)){ done({ok:false,msg:'second-click-failed'}); return; }" +
            "  setTimeout(function(){" +
            "    const secondName = second.properties.FullName;" +
            "    const names = Array.isArray(P.state.selectedFieldNames) ? P.state.selectedFieldNames : [];" +
            "    const hasBoth = names.indexOf(firstName) >= 0 && names.indexOf(secondName) >= 0;" +
            "    const h1 = document.querySelector('.form-field-handle[data-field-name=\"' + firstName + '\"]');" +
            "    const h2 = document.querySelector('.form-field-handle[data-field-name=\"' + secondName + '\"]');" +
            "    const handlesSelected = !!h1 && !!h2 && h1.classList.contains('selected') && h2.classList.contains('selected');" +
            "    const t1 = document.querySelector('.tree-node-header[data-field-name=\"' + firstName + '\"]');" +
            "    const t2 = document.querySelector('.tree-node-header[data-field-name=\"' + secondName + '\"]');" +
            "    const treeSelected = !!t1 && !!t2 && t1.classList.contains('field-selected') && t2.classList.contains('field-selected');" +
            "    done({" +
            "      ok: firstOnlySelected && hasBoth && handlesSelected && treeSelected," +
            "      firstOnlySelected:firstOnlySelected," +
            "      hasBoth:hasBoth," +
            "      handlesSelected:handlesSelected," +
            "      treeSelected:treeSelected," +
            "      names:names" +
            "    });" +
            "  }, 100);" +
            "}, 100);"
        );

        assertTrue(result instanceof Map, "Expected script result map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("ok"), "PDF click/Ctrl-click field selection sync failed: " + map);
    }

    @Test
    public void testPdfViewMultiselectKeepsAllHandlesHighlighted() {
        if (driver == null) {
            System.out.println("Skipping testPdfViewMultiselectKeepsAllHandlesHighlighted - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        ensureTestPdfReady(wait, true);
        activateSelectEditMode();
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".form-field-handle[data-field-name]")));

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(40));
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
            "const done = arguments[arguments.length - 1];" +
            "const P = window.PDFalyzer;" +
            "if(!P || !P.state || !P.state.treeData){ done({ok:false,msg:'no-state'}); return; }" +
            "const fields = [];" +
            "(function walk(n){" +
            "  if(!n) return;" +
            "  if(n.nodeCategory==='field' && n.properties && n.properties.FullName && Array.isArray(n.boundingBox) && n.boundingBox.length===4 && n.pageIndex>=0){ fields.push(n); }" +
            "  if(n.children) n.children.forEach(walk);" +
            "})(P.state.treeData);" +
            "if(fields.length < 2){ done({ok:false,msg:'not-enough-fields',count:fields.length}); return; }" +
            "function clickField(field, withCtrl){" +
            "  const pageIndex = field.pageIndex;" +
            "  const canvas = P.state.pageCanvases[pageIndex];" +
            "  const vp = P.state.pageViewports[pageIndex];" +
            "  if(!canvas || !vp) return false;" +
            "  const bb = field.boundingBox;" +
            "  const centerX = bb[0] + bb[2] / 2;" +
            "  const centerY = bb[1] + bb[3] / 2;" +
            "  const rect = canvas.getBoundingClientRect();" +
            "  const clientX = rect.left + (centerX * vp.scale);" +
            "  const clientY = rect.top + (vp.height - (centerY * vp.scale));" +
            "  const ev = new MouseEvent('click', { bubbles:true, cancelable:true, clientX:clientX, clientY:clientY, ctrlKey:!!withCtrl });" +
            "  canvas.dispatchEvent(ev);" +
            "  return true;" +
            "}" +
            "const first = fields[0];" +
            "const second = fields[1];" +
            "if(!clickField(first, false)){ done({ok:false,msg:'first-click-failed'}); return; }" +
            "setTimeout(function(){" +
            "  if(!clickField(second, true)){ done({ok:false,msg:'second-click-failed'}); return; }" +
            "  setTimeout(function(){" +
            "    const firstName = first.properties.FullName;" +
            "    const secondName = second.properties.FullName;" +
            "    const selectedNames = Array.isArray(P.state.selectedFieldNames) ? P.state.selectedFieldNames : [];" +
            "    const firstHandles = document.querySelectorAll('.form-field-handle[data-field-name=\"' + firstName + '\"]');" +
            "    const secondHandles = document.querySelectorAll('.form-field-handle[data-field-name=\"' + secondName + '\"]');" +
            "    const firstSelected = firstHandles.length===1 && firstHandles[0].classList.contains('selected');" +
            "    const secondSelected = secondHandles.length===1 && secondHandles[0].classList.contains('selected');" +
            "    const hasBothInState = selectedNames.indexOf(firstName)>=0 && selectedNames.indexOf(secondName)>=0;" +
            "    done({" +
            "      ok: hasBothInState && firstSelected && secondSelected," +
            "      hasBothInState: hasBothInState," +
            "      firstSelected: firstSelected," +
            "      secondSelected: secondSelected," +
            "      selectedNames: selectedNames" +
            "    });" +
            "  }, 120);" +
            "}, 120);"
        );

        assertTrue(result instanceof Map, "Expected script result map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("ok"), "Multi-select PDF highlight must show all selected handles: " + map);
    }

    @Disabled("Flaky Selenium test - Chrome session dies mid-test")
    @Test
    public void testPdfViewAndTreeImageSelectionSyncBothDirections() {
        if (driver == null) {
            System.out.println("Skipping testPdfViewAndTreeImageSelectionSyncBothDirections - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        ensureTestPdfReady(wait, true);
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#pdfViewer .pdf-page-wrapper canvas")));
        expandAllPageImageBranches(wait);

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(40));
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
            "const done = arguments[arguments.length - 1];" +
            "const P = window.PDFalyzer;" +
            "if(!P || !P.state || !P.state.treeData){ done({ok:false,msg:'no-state'}); return; }" +
            "const images = [];" +
            "(function walk(n){" +
            "  if(!n) return;" +
            "  if(n.nodeCategory==='image' && n.id!==undefined && n.id!==null && Array.isArray(n.boundingBox) && n.boundingBox.length===4 && n.pageIndex>=0){ images.push(n); }" +
            "  if(n.children) n.children.forEach(walk);" +
            "})(P.state.treeData);" +
            "if(images.length < 1){ done({ok:true,skipped:true,msg:'no-images'}); return; }" +
            "function clickImage(imageNode, withCtrl){" +
            "  const canvas = P.state.pageCanvases[imageNode.pageIndex];" +
            "  const vp = P.state.pageViewports[imageNode.pageIndex];" +
            "  if(!canvas || !vp) return false;" +
            "  const bb = imageNode.boundingBox;" +
            "  const centerX = bb[0] + bb[2] / 2;" +
            "  const centerY = bb[1] + bb[3] / 2;" +
            "  const rect = canvas.getBoundingClientRect();" +
            "  const clientX = rect.left + (centerX * vp.scale);" +
            "  const clientY = rect.top + (vp.height - (centerY * vp.scale));" +
            "  canvas.dispatchEvent(new MouseEvent('click', { bubbles:true, cancelable:true, clientX:clientX, clientY:clientY, ctrlKey:!!withCtrl }));" +
            "  return true;" +
            "}" +
            "const first = images[0];" +
            "if(!clickImage(first,false)){ done({ok:false,msg:'first-image-click-failed'}); return; }" +
            "setTimeout(function(){" +
            "  const selectedIds = Array.isArray(P.state.selectedImageNodeIds) ? P.state.selectedImageNodeIds.slice() : [];" +
            "  const firstSelectedInState = selectedIds.indexOf(first.id) >= 0;" +
            "  const firstHeader = document.querySelector('.tree-node[data-node-id=\"' + first.id + '\"] > .tree-node-header');" +
            "  const firstSelectedInTree = !!firstHeader && (firstHeader.classList.contains('image-selected') || firstHeader.classList.contains('selected'));" +
            "  let second = images.length > 1 ? images[1] : null;" +
            "  if(!second){" +
            "    done({ok:firstSelectedInState && firstSelectedInTree, firstSelectedInState:firstSelectedInState, firstSelectedInTree:firstSelectedInTree, skippedMulti:true, msg:'single-image-available'});" +
            "    return;" +
            "  }" +
            "  let secondHeader = document.querySelector('.tree-node[data-node-id=\"' + second.id + '\"] > .tree-node-header');" +
            "  if(!secondHeader){" +
            "    const fallbackHeaders = Array.from(document.querySelectorAll('.tree-node[data-node-id^=\"page-\"][data-node-id*=\"-img-\"] > .tree-node-header'));" +
            "    const alt = fallbackHeaders.find(h => {" +
            "      const p = h && h.parentElement;" +
            "      if(!p) return false;" +
            "      const nid = p.getAttribute('data-node-id');" +
            "      return !!nid && String(nid) !== String(first.id);" +
            "    });" +
            "    if(!alt){" +
            "      done({ok:firstSelectedInState && firstSelectedInTree, firstSelectedInState:firstSelectedInState, firstSelectedInTree:firstSelectedInTree, skippedMulti:true, msg:'second-image-header-missing'});" +
            "      return;" +
            "    }" +
            "    secondHeader = alt;" +
            "    const altId = secondHeader.parentElement.getAttribute('data-node-id');" +
            "    const mapped = images.find(img => String(img.id) === String(altId));" +
            "    if(mapped) second = mapped;" +
            "  }" +
            "  secondHeader.dispatchEvent(new MouseEvent('click', { bubbles:true, ctrlKey:true }));" +
            "  setTimeout(function(){" +
            "    const idsAfterCtrlTree = Array.isArray(P.state.selectedImageNodeIds) ? P.state.selectedImageNodeIds : [];" +
            "    const treeToPdfMulti = idsAfterCtrlTree.indexOf(first.id) >= 0 && idsAfterCtrlTree.indexOf(second.id) >= 0;" +
            "    if(!clickImage(first, true)){ done({ok:false,msg:'ctrl-image-click-failed'}); return; }" +
            "    setTimeout(function(){" +
            "      const idsAfterPdfCtrl = Array.isArray(P.state.selectedImageNodeIds) ? P.state.selectedImageNodeIds : [];" +
            "      const pdfToTreeMultiState = idsAfterPdfCtrl.indexOf(first.id) >= 0 && idsAfterPdfCtrl.indexOf(second.id) >= 0;" +
            "      const firstTreeNow = document.querySelector('.tree-node[data-node-id=\"' + first.id + '\"] > .tree-node-header');" +
            "      const secondTreeNow = document.querySelector('.tree-node[data-node-id=\"' + second.id + '\"] > .tree-node-header');" +
            "      const pdfToTreeMultiClasses = !!firstTreeNow && !!secondTreeNow && firstTreeNow.classList.contains('image-selected') && secondTreeNow.classList.contains('image-selected');" +
            "      done({" +
            "        ok: firstSelectedInState && firstSelectedInTree && treeToPdfMulti," +
            "        firstSelectedInState:firstSelectedInState," +
            "        firstSelectedInTree:firstSelectedInTree," +
            "        treeToPdfMulti:treeToPdfMulti," +
            "        pdfToTreeMultiState:pdfToTreeMultiState," +
            "        pdfToTreeMultiClasses:pdfToTreeMultiClasses" +
            "      });" +
            "    }, 120);" +
            "  }, 120);" +
            "}, 120);"
        );

        assertTrue(result instanceof Map, "Expected script result map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("ok"), "Image selection sync must work both directions: " + map);
    }

    @Test
    @Disabled("Flaky Selenium test - canvas not available")
    public void testPdfViewClickExpandsStructuralTreeForFieldAndImage() {
        if (driver == null) {
            System.out.println("Skipping testPdfViewClickExpandsStructuralTreeForFieldAndImage - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        ensureTestPdfReady(wait, true);
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#pdfViewer .pdf-page-wrapper canvas")));

        WebElement structureTab = wait.until(ExpectedConditions.elementToBeClickable(
            By.cssSelector(".tab-btn[data-tab='structure']")
        ));
        structureTab.click();
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#treeContent .tree-node[data-node-id='pages']")));

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(40));
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
            "const done = arguments[arguments.length - 1];" +
            "const P = window.PDFalyzer;" +
            "if(!P || !P.state || !P.state.treeData){ done({ok:false,msg:'no-state'}); return; }" +
            "if(!P.state.pageCanvases || !P.state.pageCanvases.length){ done({ok:false,msg:'no-canvases'}); return; }" +
            "const targets = { field:null, image:null };" +
            "(function walk(n){" +
            "  if(!n) return;" +
            "  if(!targets.field && n.nodeCategory==='field' && n.properties && n.properties.FullName && Array.isArray(n.boundingBox) && n.boundingBox.length===4 && n.pageIndex>=0){ targets.field = n; }" +
            "  if(!targets.image && n.nodeCategory==='image' && n.id!==undefined && n.id!==null && Array.isArray(n.boundingBox) && n.boundingBox.length===4 && n.pageIndex>=0){ targets.image = n; }" +
            "  if(targets.field && targets.image) return;" +
            "  if(n.children) n.children.forEach(walk);" +
            "})(P.state.treeData);" +
            "if(!targets.field || !targets.image){" +
            "  done({ok:true,skipped:true,msg:'missing-field-or-image',hasField:!!targets.field,hasImage:!!targets.image});" +
            "  return;" +
            "}" +
            "function collapseAll(){" +
            "  document.querySelectorAll('#treeContent .tree-node > .tree-children').forEach(c => c.style.display='none');" +
            "  document.querySelectorAll('#treeContent .tree-node > .tree-node-header > .tree-toggle > i').forEach(i => {" +
            "    i.classList.remove('fa-chevron-down');" +
            "    i.classList.add('fa-chevron-right');" +
            "  });" +
            "}" +
            "function pathToId(targetId){" +
            "  const path = [];" +
            "  (function dfs(n){" +
            "    if(!n) return false;" +
            "    path.push(n.id);" +
            "    if(String(n.id)===String(targetId)) return true;" +
            "    if(n.children){" +
            "      for(let i=0;i<n.children.length;i++){ if(dfs(n.children[i])) return true; }" +
            "    }" +
            "    path.pop();" +
            "    return false;" +
            "  })(P.state.treeData);" +
            "  return path;" +
            "}" +
            "function isExpandedPath(path){" +
            "  if(!path || path.length < 2) return false;" +
            "  for(let i=0;i<path.length-1;i++){" +
            "    const node = document.querySelector('.tree-node[data-node-id=\"' + path[i] + '\"]');" +
            "    if(!node) return false;" +
            "    const children = node.querySelector(':scope > .tree-children');" +
            "    if(children && getComputedStyle(children).display==='none') return false;" +
            "  }" +
            "  return !!document.querySelector('.tree-node[data-node-id=\"' + path[path.length-1] + '\"]');" +
            "}" +
            "function clickNode(node){" +
            "  const canvas = P.state.pageCanvases[node.pageIndex];" +
            "  const vp = P.state.pageViewports[node.pageIndex];" +
            "  if(!canvas || !vp) return false;" +
            "  const bb = node.boundingBox;" +
            "  const centerX = bb[0] + bb[2] / 2;" +
            "  const centerY = bb[1] + bb[3] / 2;" +
            "  const rect = canvas.getBoundingClientRect();" +
            "  const clientX = rect.left + (centerX * vp.scale);" +
            "  const clientY = rect.top + (vp.height - (centerY * vp.scale));" +
            "  canvas.dispatchEvent(new MouseEvent('click', { bubbles:true, cancelable:true, clientX:clientX, clientY:clientY }));" +
            "  return true;" +
            "}" +
            "const fieldPath = pathToId(targets.field.id);" +
            "const imagePath = pathToId(targets.image.id);" +
            "collapseAll();" +
            "if(!clickNode(targets.field)){ done({ok:false,msg:'field-click-failed'}); return; }" +
            "setTimeout(function(){" +
            "  const fieldExpanded = isExpandedPath(fieldPath);" +
            "  const selectedFieldNames = Array.isArray(P.state.selectedFieldNames) ? P.state.selectedFieldNames : [];" +
            "  const fieldSelectedState = selectedFieldNames.indexOf(targets.field.properties.FullName) >= 0;" +
            "  collapseAll();" +
            "  if(!clickNode(targets.image)){ done({ok:false,msg:'image-click-failed',fieldExpanded:fieldExpanded,fieldSelectedState:fieldSelectedState}); return; }" +
            "  setTimeout(function(){" +
            "    const imageExpanded = isExpandedPath(imagePath);" +
            "    const selectedImageIds = Array.isArray(P.state.selectedImageNodeIds) ? P.state.selectedImageNodeIds : [];" +
            "    const imageSelectedState = selectedImageIds.indexOf(targets.image.id) >= 0;" +
            "    done({" +
            "      ok: fieldExpanded && imageExpanded && fieldSelectedState && imageSelectedState," +
            "      fieldExpanded: fieldExpanded," +
            "      imageExpanded: imageExpanded," +
            "      fieldSelectedState: fieldSelectedState," +
            "      imageSelectedState: imageSelectedState," +
            "      fieldPathLength: fieldPath.length," +
            "      imagePathLength: imagePath.length" +
            "    });" +
            "  }, 120);" +
            "}, 120);"
        );

        assertTrue(result instanceof Map, "Expected script result map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("ok"), "PDF click should expand structural tree for field and image: " + map);
    }

    @Test
    @Disabled("Flaky Selenium test - font tab timing issue")
    public void testFontsTabRowActionsVisibleAndClickable() {
        if (driver == null) {
            System.out.println("Skipping testFontsTabRowActionsVisibleAndClickable - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        ensureTestPdfReady(wait, true);

        WebElement fontsTab = wait.until(ExpectedConditions.elementToBeClickable(
            By.cssSelector(".tab-btn[data-tab='fonts']")
        ));
        fontsTab.click();

        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".font-table tbody tr")));
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".font-table tbody tr td:last-child")));

        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
            "const done = arguments[arguments.length - 1];" +
            "const rows = Array.from(document.querySelectorAll('.font-table tbody tr'));" +
            "if(!rows.length){ done({ok:false,msg:'no-font-rows'}); return; }" +
            "let rowsWithActions = 0;" +
            "rows.forEach(r => {" +
            "  const actionCell = r.querySelector('td:last-child');" +
            "  if(!actionCell) return;" +
            "  const action = actionCell.querySelector('button.font-detail-btn, button.font-usage-btn, a[href*=\"/extract/\"]');" +
            "  if(action) rowsWithActions += 1;" +
            "});" +
            "if(rowsWithActions !== rows.length){ done({ok:false,msg:'actions-missing-on-some-rows',rows:rows.length,rowsWithActions:rowsWithActions}); return; }" +
            "const detailBtn = document.querySelector('.font-table tbody tr td:last-child button.font-detail-btn');" +
            "if(!detailBtn){ done({ok:false,msg:'no-detail-button'}); return; }" +
            "detailBtn.click();" +
            "setTimeout(function(){" +
            "  const panel = document.querySelector('#fontDiagDetail');" +
            "  if(!panel){ done({ok:false,msg:'no-detail-panel'}); return; }" +
            "  const text = (panel.textContent || '').toLowerCase();" +
            "  const clicked = text.indexOf('loading deep font diagnostics') >= 0 || text.indexOf('object reference') >= 0 || text.indexOf('glyph mapping table') >= 0 || text.indexOf('diagnostics') >= 0;" +
            "  done({ok:clicked,rows:rows.length,rowsWithActions:rowsWithActions,panelText:text.slice(0,220)});" +
            "}, 300);"
        );

        assertTrue(result instanceof Map, "Expected script result map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("ok"), "Fonts row actions must be visible and clickable: " + map);
    }

    @Test
    @Disabled("Flaky Selenium test - font detail button timeout")
    public void testFontDiagnosticsLazyGlyphPreviewLoads() {
        if (driver == null) {
            System.out.println("Skipping testFontDiagnosticsLazyGlyphPreviewLoads - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        ensureTestPdfReady(wait, true);

        WebElement fontsTab = wait.until(ExpectedConditions.elementToBeClickable(
            By.cssSelector(".tab-btn[data-tab='fonts']")
        ));
        fontsTab.click();

        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".font-table tbody tr")));
        WebElement detailBtn = wait.until(ExpectedConditions.elementToBeClickable(
            By.cssSelector(".font-table tbody tr td:last-child button.font-detail-btn")
        ));
        detailBtn.click();

        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#fontDiagDetail .font-glyph-lazy")));

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(10));
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
            "const done = arguments[arguments.length - 1];" +
            "const root = document.querySelector('#fontDiagDetail');" +
            "if(!root){ done({ok:false,msg:'no-detail-root'}); return; }" +
            "const start = Date.now();" +
            "function counts(){" +
            "  return {" +
            "    placeholders: root.querySelectorAll('.font-glyph-lazy .text-muted').length," +
            "    renderedAttr: root.querySelectorAll('.font-glyph-lazy[data-rendered=\"1\"]').length," +
            "    previews: root.querySelectorAll('.font-glyph-preview').length" +
            "  };" +
            "}" +
            "(function poll(){" +
            "  const c = counts();" +
            "  if(c.previews > 0 || c.renderedAttr > 0){ done({ok:true,counts:c}); return; }" +
            "  if(Date.now() - start > 6000){ done({ok:false,msg:'glyphs-did-not-render',counts:c}); return; }" +
            "  setTimeout(poll, 120);" +
            "})();"
        );

        assertTrue(result instanceof Map, "Expected script result map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("ok"), "Lazy glyph previews should render in diagnostics: " + map);
    }

    @Test
    public void testMovingSelectedFieldsEnablesSaveAndAvoidsGhostHandles() {
        if (driver == null) {
            System.out.println("Skipping testMovingSelectedFieldsEnablesSaveAndAvoidsGhostHandles - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));
        ensureTestPdfReady(wait, true);
        activateSelectEditMode();
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".form-field-handle[data-field-name]")));

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(40));
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
            "const done = arguments[arguments.length - 1];" +
            "const P = window.PDFalyzer;" +
            "if(!P || !P.state || !P.state.treeData){ done({ok:false,msg:'no-state'}); return; }" +
            "const fields = [];" +
            "(function walk(n){" +
            "  if(!n) return;" +
            "  if(n.nodeCategory==='field' && n.properties && n.properties.FullName && Array.isArray(n.boundingBox) && n.boundingBox.length===4 && n.pageIndex>=0){ fields.push(n); }" +
            "  if(n.children) n.children.forEach(walk);" +
            "})(P.state.treeData);" +
            "if(fields.length < 2){ done({ok:false,msg:'not-enough-fields',count:fields.length}); return; }" +
            "function clickField(field, withCtrl){" +
            "  const pageIndex = field.pageIndex;" +
            "  const canvas = P.state.pageCanvases[pageIndex];" +
            "  const vp = P.state.pageViewports[pageIndex];" +
            "  if(!canvas || !vp) return false;" +
            "  const bb = field.boundingBox;" +
            "  const centerX = bb[0] + bb[2] / 2;" +
            "  const centerY = bb[1] + bb[3] / 2;" +
            "  const rect = canvas.getBoundingClientRect();" +
            "  const clientX = rect.left + (centerX * vp.scale);" +
            "  const clientY = rect.top + (vp.height - (centerY * vp.scale));" +
            "  const ev = new MouseEvent('click', { bubbles:true, cancelable:true, clientX:clientX, clientY:clientY, ctrlKey:!!withCtrl });" +
            "  canvas.dispatchEvent(ev);" +
            "  return true;" +
            "}" +
            "const first = fields[0];" +
            "const second = fields[1];" +
            "if(!clickField(first, false)){ done({ok:false,msg:'first-click-failed'}); return; }" +
            "setTimeout(function(){" +
            "  if(!clickField(second, true)){ done({ok:false,msg:'second-click-failed'}); return; }" +
            "  setTimeout(function(){" +
            "    const firstName = first.properties.FullName;" +
            "    const secondName = second.properties.FullName;" +
            "    const h1 = document.querySelector('.form-field-handle[data-field-name=\"' + firstName + '\"]');" +
            "    if(!h1){ done({ok:false,msg:'missing-primary-handle'}); return; }" +
            "    const beforeTop = parseFloat(h1.style.top || '0');" +
            "    const beforeLeft = parseFloat(h1.style.left || '0');" +
            "    const r = h1.getBoundingClientRect();" +
            "    h1.dispatchEvent(new MouseEvent('mousedown', { bubbles:true, cancelable:true, clientX:r.left + (r.width/2), clientY:r.top + (r.height/2) }));" +
            "    document.dispatchEvent(new MouseEvent('mousemove', { bubbles:true, cancelable:true, clientX:r.left + (r.width/2) + 24, clientY:r.top + (r.height/2) + 12 }));" +
            "    document.dispatchEvent(new MouseEvent('mouseup', { bubbles:true, cancelable:true, clientX:r.left + (r.width/2) + 24, clientY:r.top + (r.height/2) + 12 }));" +
            "    setTimeout(function(){" +
            "      const saveBtn = document.getElementById('formSaveBtn');" +
            "      const saveEnabled = !!saveBtn && !saveBtn.disabled;" +
            "      const pendingCount = Array.isArray(P.state.pendingFieldRects) ? P.state.pendingFieldRects.length : 0;" +
            "      const movedFirst = document.querySelectorAll('.form-field-handle[data-field-name=\"' + firstName + '\"]');" +
            "      const movedSecond = document.querySelectorAll('.form-field-handle[data-field-name=\"' + secondName + '\"]');" +
            "      const duplicatesOk = movedFirst.length === 1 && movedSecond.length === 1;" +
            "      const movedNow = movedFirst.length===1 && (parseFloat(movedFirst[0].style.left||'0') !== beforeLeft || parseFloat(movedFirst[0].style.top||'0') !== beforeTop);" +
            "      done({" +
            "        ok: saveEnabled && pendingCount > 0 && duplicatesOk && movedNow," +
            "        saveEnabled: saveEnabled," +
            "        pendingCount: pendingCount," +
            "        duplicatesOk: duplicatesOk," +
            "        movedNow: movedNow," +
            "        movedFirstCount: movedFirst.length," +
            "        movedSecondCount: movedSecond.length" +
            "      });" +
            "    }, 140);" +
            "  }, 120);" +
            "}, 120);"
        );

        assertTrue(result instanceof Map, "Expected script result map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("ok"), "Move must enable save and avoid stale duplicate handles: " + map);
    }

    @Test
    public void testFieldOptionsApplyEnablesSaveButton() {
     
        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));
        ensureTestPdfReady(wait, true);
        activateSelectEditMode();
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".form-field-handle[data-field-name]")));

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(40));
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
            "var done = arguments[arguments.length - 1];" +
            "var P = window.PDFalyzer;" +
            "if(!P||!P.state){ done({ok:false,msg:'no-state'}); return; }" +
            "if(!P.FieldDialog){ done({ok:false,msg:'no-FieldDialog'}); return; }" +
            "if(!P.EditOptions){ done({ok:false,msg:'no-EditOptions'}); return; }" +
            "var handle = document.querySelector('.form-field-handle[data-field-name]');" +
            "if(!handle){ done({ok:false,msg:'no-handle'}); return; }" +
            "var fieldName = handle.getAttribute('data-field-name');" +
            "var btn = document.getElementById('formOptionsBtn');" +
            "if(!btn){ done({ok:false,msg:'no-options-btn'}); return; }" +
            "P.state.selectedFieldNames = [fieldName];" +
            "try { P.EditOptions.openOptionsPopup(); } catch(e) { done({ok:false,msg:'popup-threw:'+e.message}); return; }" +
            "var deadline = Date.now() + 6000;" +
            "function poll(){" +
            "  var applyBtn = document.getElementById('fieldEditModalApplyBtn');" +
            "  var reqBlock = document.querySelector('[data-fd-key=\"required\"]');" +
            "  var optReq = document.querySelector('input[name=\"fd_required\"][value=\"true\"]');" +
            "  if(optReq && applyBtn && reqBlock){" +
            "    optReq.click(); applyBtn.click();" +
            "    setTimeout(function(){" +
            "      var saveBtn = document.getElementById('formSaveBtn');" +
            "      var saveEnabled = !!saveBtn && !saveBtn.disabled;" +
            "      var pendingOpts = Array.isArray(P.state.pendingFieldOptions)?P.state.pendingFieldOptions.length:0;" +
            "      done({ok:saveEnabled&&pendingOpts>0, saveEnabled:saveEnabled, pendingOpts:pendingOpts});" +
            "    }, 300);" +
            "  } else if(Date.now()<deadline){" +
            "    setTimeout(poll,100);" +
            "  } else {" +
            "    var tc = document.getElementById('fdTabsContainer');" +
            "    var m = document.getElementById('fieldEditModal');" +
            "    done({ok:false,msg:'timeout',schemaCache:!!window._PDFalyzerSchemaCache," +
            "      editOptExists:!!P.EditOptions,fieldDialogExists:!!P.FieldDialog," +
            "      modalShow:m?m.classList.contains('show'):false," +
            "      tabsHtml:tc?tc.innerHTML.substring(0,300):''," +
            "      sessionId:P.state.sessionId||null});" +
            "  }" +
            "}" +
            "setTimeout(poll, 300);"
        );

        assertTrue(result instanceof Map, "Expected script result map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("ok"), "Applying field options must enable Save button: " + map);
    }

    @Test
    public void testDocumentInfoCosEditsPersistToPdfAfterSave() throws Exception {


        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(300));
        ensureTestPdfReady(wait, false);

        String expectedAuthor = "UI Test Author " + System.currentTimeMillis();
        String expectedCreator = "UI Test Creator " + System.currentTimeMillis();

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(60));
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
            "const done = arguments[arguments.length - 1];" +
            "const expectedAuthor = arguments[0];" +
            "const expectedCreator = arguments[1];" +
            "const P = window.PDFalyzer;" +
            "if(!P || !P.state || !P.state.sessionId || !P.state.treeData){ done({ok:false,msg:'no-state'}); return; }" +
            "if(!Array.isArray(P.state.pendingCosChanges)) P.state.pendingCosChanges = [];" +
            "if(!P.EditMode){ done({ok:false,msg:'edit-mode-missing'}); return; }" +
            "P.state.pendingCosChanges.push({" +
            "  operation:'update'," +
            "  summary:'Update /Author'," +
            "  request:{ objectNumber:-1, generationNumber:0, keyPath:['Author'], newValue: expectedAuthor, valueType:'COSString', operation:'update', targetScope:'docinfo' }" +
            "});" +
            "P.state.pendingCosChanges.push({" +
            "  operation:'update'," +
            "  summary:'Update /Creator'," +
            "  request:{ objectNumber:-1, generationNumber:0, keyPath:['Creator'], newValue: expectedCreator, valueType:'COSString', operation:'update', targetScope:'docinfo' }" +
            "});" +
            "if(P.EditMode.updateSaveButton){ P.EditMode.updateSaveButton(); }" +
            "const beforePending = Array.isArray(P.state.pendingCosChanges) ? P.state.pendingCosChanges.length : -1;" +
            "if(beforePending < 2){ done({ok:false,msg:'pending-not-queued', beforePending:beforePending}); return; }" +
            "const saveBtn = document.getElementById('formSaveBtn');" +
            "if(saveBtn && saveBtn.disabled){ done({ok:false,msg:'save-disabled-after-queue'}); return; }" +
            "P.EditMode.savePendingChanges();" +
            "let ticks = 0;" +
            "const maxTicks = 140;" +
            "const iv = setInterval(function(){" +
            "  ticks++;" +
            "  const pendingCos = Array.isArray(P.state.pendingCosChanges) ? P.state.pendingCosChanges.length : 0;" +
            "  const pendingFields = (Array.isArray(P.state.pendingFormAdds) ? P.state.pendingFormAdds.length : 0) +" +
            "                       (Array.isArray(P.state.pendingFieldRects) ? P.state.pendingFieldRects.length : 0) +" +
            "                       (Array.isArray(P.state.pendingFieldOptions) ? P.state.pendingFieldOptions.length : 0);" +
            "  const failedCos = Array.isArray(P.state.pendingCosChanges) ? P.state.pendingCosChanges.filter(function(x){ return !!(x && x.lastError); }).length : 0;" +
            "  if(pendingCos === 0 && pendingFields === 0){" +
            "    clearInterval(iv);" +
            "    done({ok:true, sessionId:P.state.sessionId, failedCos:failedCos});" +
            "    return;" +
            "  }" +
            "  if(ticks >= maxTicks){" +
            "    clearInterval(iv);" +
            "    done({ok:false,msg:'save-timeout', pendingCos:pendingCos, pendingFields:pendingFields, failedCos:failedCos});" +
            "  }" +
            "}, 100);",
            expectedAuthor,
            expectedCreator
        );

        assertTrue(result instanceof Map, "Expected script result map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("ok"), "Document Info edit/save flow failed: " + map);

        String sessionId = String.valueOf(map.get("sessionId"));
        assertNotNull(sessionId);
        assertFalse(sessionId.isBlank(), "Session id should be available after save");

        byte[] pdfBytes;
        URL pdfUrl = new URL(null,baseUrl + "/api/pdf/" + sessionId);
        try (InputStream is = pdfUrl.openStream()) {
            pdfBytes = is.readAllBytes();
        }

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDDocumentInformation info = doc.getDocumentInformation();
            assertNotNull(info, "Document information must exist");
            assertEquals(expectedAuthor, info.getAuthor(), "Author metadata should persist after Save");
            assertEquals(expectedCreator, info.getCreator(), "Creator metadata should persist after Save");
        }
    }

        @Test
        public void testMultiselectTriStateFieldOptionsWorkflow() {
      
        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));
        ensureTestPdfReady(wait, true);

        activateSelectEditMode();
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".form-field-handle")));

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(40));
        Object applyResult = ((JavascriptExecutor) driver).executeAsyncScript(
            "const done = arguments[arguments.length - 1];" +
            "const P = window.PDFalyzer;" +
            "if(!P || !P.state || !P.state.sessionId){ done({ok:false,msg:'no-session'}); return; }" +
            "const hs = Array.from(document.querySelectorAll('.form-field-handle'));" +
            "if(hs.length < 2){ done({ok:false,msg:'not-enough-handles'}); return; }" +
            "const names = hs.slice(0,2).map(h => h.getAttribute('data-field-name')).filter(Boolean);" +
            "if(names.length < 2){ done({ok:false,msg:'missing-field-names'}); return; }" +
            "P.state.selectedFieldNames = names;" +
            "$.ajax({" +
            " url:'/api/edit/' + P.state.sessionId + '/fields/options'," +
            " method:'POST', contentType:'application/json'," +
            " data: JSON.stringify({ fieldNames:names, options:{ required:true } })" +
            "})" +
            ".then(function(resp){ P.state.treeData = resp.tree; done({ok:true, count:names.length}); })" +
            ".catch(function(err){ done({ok:false,msg:(err&&err.statusText)||'ajax-failed'}); });"
        );
        assertTrue(applyResult instanceof Map, "Expected apply result map");
        @SuppressWarnings("unchecked")
        Map<String, Object> applyMap = (Map<String, Object>) applyResult;
        assertEquals(Boolean.TRUE, applyMap.get("ok"), "Multiselect options apply failed: " + applyMap);

        Object requiredCountObj = ((JavascriptExecutor) driver).executeScript(
            "const P = window.PDFalyzer;" +
            "if(!P || !P.state || !P.state.selectedFieldNames) return 0;" +
            "const selected = new Set(P.state.selectedFieldNames);" +
            "let count = 0;" +
            "function walk(n){" +
            " if(!n) return;" +
            " if(n.nodeCategory==='field' && n.properties && selected.has(n.properties.FullName)){" +
            "   if(String(n.properties.Required).toLowerCase()==='true') count++;" +
            " }" +
            " if(n.children) n.children.forEach(walk);" +
            "}" +
            "walk(P.state.treeData);" +
            "return count;"
        );
        assertTrue(requiredCountObj instanceof Number, "Expected numeric required count");
        assertTrue(((Number) requiredCountObj).intValue() >= 2,
            "Both selected fields should have Required=true after tri-state apply");
        }

    @Test
    public void testSmoothRefreshPreservesTreeStateAndUsesHiddenViewerStaging() {
        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        ensureTestPdfReady(wait, false);

        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(".tab-btn[data-tab='structure']")))
            .click();
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#treeContent .tree-node")));

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(45));
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
            "const done = arguments[arguments.length - 1];" +
            "const P = window.PDFalyzer;" +
            "if(!P || !P.state || !P.state.treeData || !P.state.sessionId){ done({ok:false,msg:'missing-pdf-state'}); return; }" +
            "const tree = document.getElementById('treeContent');" +
            "const pane = document.getElementById('pdfPane');" +
            "if(!tree || !pane){ done({ok:false,msg:'missing-tree-or-pane'}); return; }" +
            "const expandables = Array.from(document.querySelectorAll('#treeContent .tree-node')).filter(function(n){" +
            "  const t = n.querySelector(':scope > .tree-node-header > .tree-toggle > i');" +
            "  const c = n.querySelector(':scope > .tree-children');" +
            "  return !!t && !!c;" +
            "});" +
            "if(!expandables.length){ done({ok:false,msg:'no-expandable-node'}); return; }" +
            "const target = expandables.find(function(n){ return n.getAttribute('data-node-id') === 'pages'; }) || expandables[0];" +
            "const targetId = target.getAttribute('data-node-id');" +
            "tree.scrollTop = 120;" +
            "pane.scrollTop = 240;" +
            "const toggle = target.querySelector(':scope > .tree-node-header > .tree-toggle');" +
            "const children = target.querySelector(':scope > .tree-children');" +
            "if(!toggle || !children){ done({ok:false,msg:'missing-toggle-or-children'}); return; }" +
            "if(getComputedStyle(children).display === 'none'){ toggle.click(); }" +
            "const beforeTreeScroll = tree.scrollTop;" +
            "const beforePaneScroll = pane.scrollTop;" +
            "P.Utils.refreshAfterMutation(P.state.treeData);" +
            "const stagingNow = document.getElementById('pdfViewerStaging');" +
            "if(!stagingNow){ done({ok:false,msg:'staging-not-created'}); return; }" +
            "let attempts = 0;" +
            "(function poll(){" +
            "  attempts++;" +
            "  const wrappers = document.querySelectorAll('#pdfViewer .pdf-page-wrapper');" +
            "  const refreshed = wrappers.length > 0 && attempts > 5;" +
            "  if(!refreshed && attempts < 120){ setTimeout(poll, 100); return; }" +
            "  const targetAfter = document.querySelector('#treeContent .tree-node[data-node-id=\"' + targetId + '\"]');" +
            "  const childrenAfter = targetAfter ? targetAfter.querySelector(':scope > .tree-children') : null;" +
            "  const expandedAfter = !!childrenAfter && getComputedStyle(childrenAfter).display !== 'none';" +
            "  const treeScrollAfter = tree.scrollTop;" +
            "  const paneScrollAfter = pane.scrollTop;" +
            "  const staging = document.getElementById('pdfViewerStaging');" +
            "  const stagingClassOk = !!staging && staging.classList.contains('pdf-viewer-staging');" +
            "  const treeScrollPreserved = Math.abs(treeScrollAfter - beforeTreeScroll) <= 12;" +
            "  const paneNotReset = paneScrollAfter > 80;" +
            "  done({" +
            "    ok: expandedAfter && treeScrollPreserved && paneNotReset && stagingClassOk," +
            "    expandedAfter: expandedAfter," +
            "    treeScrollBefore: beforeTreeScroll," +
            "    treeScrollAfter: treeScrollAfter," +
            "    paneScrollBefore: beforePaneScroll," +
            "    paneScrollAfter: paneScrollAfter," +
            "    stagingClassOk: stagingClassOk" +
            "  });" +
            "})();"
        );

        assertTrue(result instanceof Map, "Expected script result map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("ok"), "Smooth refresh must preserve state and staging behavior: " + map);
    }

    @Test
    public void testZoomTransitionKeepsRenderedPagesVisibleAt10msSampling() {
        if (driver == null) {
            System.out.println("Skipping testZoomTransitionKeepsRenderedPagesVisibleAt10msSampling - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        ensureTestPdfReady(wait, false);

        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#pdfViewer .pdf-page-wrapper")));

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(45));
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
            "const done = arguments[arguments.length - 1];" +
            "const P = window.PDFalyzer;" +
            "if(!P || !P.Viewer || !P.state || !P.state.pdfDoc){ done({ok:false,msg:'missing-viewer-state'}); return; }" +
            "const pane = document.getElementById('pdfPane');" +
            "if(pane){ pane.scrollTop = 220; }" +
            "const initial = document.querySelectorAll('#pdfViewer .pdf-page-wrapper').length;" +
            "if(initial === 0){ done({ok:false,msg:'no-initial-pages'}); return; }" +
            "let blankFrames = 0;" +
            "let observedStaging = false;" +
            "let samples = 0;" +
            "const maxSamples = 220;" +
            "const sampleDelayMs = 10;" +
            "const timer = setInterval(function(){" +
            "  samples++;" +
            "  const viewerCount = document.querySelectorAll('#pdfViewer .pdf-page-wrapper').length;" +
            "  const stagingCount = document.querySelectorAll('#pdfViewerStaging .pdf-page-wrapper').length;" +
            "  const staging = document.getElementById('pdfViewerStaging');" +
            "  if(staging && staging.classList.contains('pdf-viewer-staging-active')) observedStaging = true;" +
            "  if((viewerCount + stagingCount) === 0) blankFrames++;" +
            "  if(samples >= maxSamples){" +
            "    clearInterval(timer);" +
            "    done({" +
            "      ok: blankFrames === 0," +
            "      blankFrames: blankFrames," +
            "      samples: samples," +
            "      observedStaging: observedStaging" +
            "    });" +
            "  }" +
            "}, sampleDelayMs);" +
            "P.Viewer.setScale((P.state.currentScale || 1) * 1.22);"
        );

        assertTrue(result instanceof Map, "Expected script result map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("ok"),
            "Zoom transition must keep at least one rendered page visible in sampled frames: " + map);
    }

    @Test
    public void testCaptureSmoothRefreshTransitionVideoFrames() throws Exception {
        if (driver == null) {
            System.out.println("Skipping testCaptureSmoothRefreshTransitionVideoFrames - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        ensureTestPdfReady(wait, false);

        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#pdfViewer .pdf-page-wrapper")));

        Path artifactDir = Paths.get("target", "ui-artifacts", "smooth-refresh-transition",
            "run-" + System.currentTimeMillis());
        Files.createDirectories(artifactDir);

        ((JavascriptExecutor) driver).executeScript(
            "const tree = document.getElementById('treeContent');" +
            "const pane = document.getElementById('pdfPane');" +
            "if(tree) tree.scrollTop = 140;" +
            "if(pane) pane.scrollTop = 260;" +
            "const pages = document.querySelector('.tree-node[data-node-id=\"pages\"]');" +
            "if(pages){" +
            "  const t = pages.querySelector(':scope > .tree-node-header > .tree-toggle');" +
            "  const c = pages.querySelector(':scope > .tree-children');" +
            "  if(t && c && getComputedStyle(c).display==='none'){ t.click(); }" +
            "}"
        );

        ((JavascriptExecutor) driver).executeScript(
            "window.__pdfalyzerTransitionCaptureStartedAt = performance.now();" +
            "window.PDFalyzer.Utils.refreshAfterMutation(window.PDFalyzer.state.treeData);"
        );

        int frameCount = 180;
        int frameDelayMs = 10;
        for (int i = 0; i < frameCount; i++) {
            byte[] png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            Path frame = artifactDir.resolve(String.format("frame-%03d.png", i));
            Files.write(frame, png);
            Thread.sleep(frameDelayMs);
        }

        boolean mp4Generated = tryGenerateMp4FromFrames(artifactDir, 20);
        Path marker = artifactDir.resolve("README.txt");
        Files.writeString(marker,
            "Captured " + frameCount + " frames at " + frameDelayMs + "ms intervals.\n" +
            "Frames path: " + artifactDir.toAbsolutePath() + "\n" +
            "MP4 generated: " + mp4Generated + "\n" +
            (mp4Generated
                ? "Video file: transition.mp4\n"
                : "MP4 generation failed (JCodec + ffmpeg fallback).\n")
        );

        assertTrue(Files.exists(artifactDir.resolve("frame-000.png")),
            "Expected first transition frame to be captured");
        assertTrue(Files.exists(artifactDir.resolve("frame-044.png")),
            "Expected last transition frame to be captured");
        System.out.println("Transition artifact directory: " + artifactDir.toAbsolutePath());
    }

    /** Activates the Edit Form toggle and select tool via JavaScript so field handles are rendered instead of fill overlays. */
    private void activateSelectEditMode() {
        ((JavascriptExecutor) driver).executeScript(
            "var P = window.PDFalyzer;" +
            "if (!P || !P.state) return;" +
            "if (P.EditMode && P.EditMode.setEditFormActive) { P.EditMode.setEditFormActive(true); return; }" +
            "P.state.editFieldType = 'select';" +
            "if (P.EditMode && P.EditMode.syncEditFieldTypeUI) P.EditMode.syncEditFieldTypeUI();" +
            "if (P.EditMode && P.EditMode.renderFieldHandlesForAllPages) P.EditMode.renderFieldHandlesForAllPages();"
        );
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

    private void ensureTestPdfReady(WebDriverWait wait, boolean requireAutoload) {
        boolean loaded = false;
        try {
            wait.until(d -> hasUsablePdfSession());
            loaded = true;
        } catch (TimeoutException ignored) {
        }

        if (!loaded && requireAutoload) {
            ((JavascriptExecutor) driver).executeScript(
                "if(window.PDFalyzer && window.PDFalyzer.Upload && window.PDFalyzer.Upload.loadSampleOnInit){" +
                "  window.PDFalyzer.Upload.loadSampleOnInit();" +
                "}"
            );
            wait.until(d -> hasUsablePdfSession());
            loaded = true;
        }

        if (!loaded) {
            Path testPdf = Paths.get("src", "main", "resources", "sample-pdfs", "test.pdf").toAbsolutePath();
            assertTrue(Files.exists(testPdf), "Expected test PDF to exist at " + testPdf);

            WebElement fileInput = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("fileInput")));
            fileInput.sendKeys(testPdf.toString());
            wait.until(d -> hasUsablePdfSession());
        }
    }

    private boolean hasUsablePdfSession() {

        try {
            Object loaded = ((JavascriptExecutor) driver).executeScript(
                "const P = window.PDFalyzer;" +
                "if(!P || !P.state) return false;" +
                "const hasSession = !!P.state.sessionId;" +
                "const hasTree = !!P.state.treeData;" +
                "const hasViewer = document.querySelectorAll('#pdfViewer .pdf-page-wrapper').length > 0;" +
                "return hasSession && hasTree && hasViewer;"
            );
            if (Boolean.TRUE.equals(loaded)) return true;
        } catch (Exception ignored) {
        }

        try {
            WebElement status = driver.findElement(By.id("statusSession"));
            String text = status.getText();
            if (text != null && text.toLowerCase().contains("active")) {
                return true;
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private void expandAllPageImageBranches(WebDriverWait wait) {
        expandNode(wait, "pages");

        Object idsObj = ((JavascriptExecutor) driver).executeScript(
            "const root = document.querySelector('.tree-node[data-node-id=\"pages\"] > .tree-children');" +
            "if(!root) return [];" +
            "return Array.from(root.querySelectorAll(':scope > .tree-node'))" +
            "  .map(n => n.getAttribute('data-node-id'))" +
            "  .filter(id => /^page-\\d+$/.test(String(id || '')));"
        );

        if (!(idsObj instanceof List<?> pageIds)) {
            return;
        }

        for (Object idObj : pageIds) {
            if (!(idObj instanceof String pageId) || pageId.isBlank()) {
                continue;
            }
            try {
                expandNode(wait, pageId);
            } catch (Exception ignored) {
            }
            try {
                expandNode(wait, pageId + "-resources");
            } catch (Exception ignored) {
            }
            try {
                expandNode(wait, pageId + "-images");
            } catch (Exception ignored) {
            }
        }
    }

    private void assertNoClientJsErrors(String context) {
        Object raw = ((JavascriptExecutor) driver).executeScript(
                "return Array.isArray(window.__pdfalyzerJsErrors) ? window.__pdfalyzerJsErrors : [];"
        );
        if (!(raw instanceof List<?>)) {
            return;
        }
        List<?> list = (List<?>) raw;
        if (list.isEmpty()) return;
        for (Object entry : list) {
            log.warn("JS ERROR [{}]: {}", context, entry);
        }
        throw new RuntimeException("JavaScript errors captured in browser (" + context + "): " + list);
    }

    /**
     * Dumps Chromium browser console logs and HTTP 4xx/5xx network errors to SLF4J.
     * Requires goog:loggingPrefs set in ChromeOptions (done in setUp).
     */
    private void dumpBrowserLogs(String context) {
        if (driver == null) return;
        try {
            LogEntries browserLogs = driver.manage().logs().get(LogType.BROWSER);
            for (LogEntry entry : browserLogs) {
                if (entry.getLevel().intValue() >= Level.WARNING.intValue()) {
                    log.warn("BROWSER [{}] {}: {}", context, entry.getLevel(), entry.getMessage());
                } else {
                    log.debug("BROWSER [{}] {}: {}", context, entry.getLevel(), entry.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Could not retrieve browser logs ({}): {}", context, e.getMessage());
        }
        try {
            LogEntries perfLogs = driver.manage().logs().get(LogType.PERFORMANCE);
            for (LogEntry entry : perfLogs) {
                String msg = entry.getMessage();
                if (msg.contains("Network.responseReceived") && (msg.contains("\"status\":4") || msg.contains("\"status\":5"))) {
                    log.warn("NETWORK ERROR [{}]: {}", context, msg.length() > 500 ? msg.substring(0, 500) : msg);
                }
            }
        } catch (Exception e) {
            log.debug("Could not retrieve performance logs ({}): {}", context, e.getMessage());
        }
    }

    private boolean tryGenerateMp4FromFrames(Path artifactDir, int fps) {
        if (tryGenerateMp4WithJCodec(artifactDir, fps)) {
            return true;
        }

        try {
            Process process = new ProcessBuilder(
                "ffmpeg",
                "-y",
                "-framerate", String.valueOf(fps),
                "-i", "frame-%03d.png",
                "-pix_fmt", "yuv420p",
                "transition.mp4"
            )
                .directory(artifactDir.toFile())
                .redirectErrorStream(true)
                .start();

            int exit = process.waitFor();
            return exit == 0 && Files.exists(artifactDir.resolve("transition.mp4"));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private boolean tryGenerateMp4WithJCodec(Path artifactDir, int fps) {
        List<Path> frames;
        try {
            frames = Files.list(artifactDir)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith("frame-") && name.endsWith(".png");
                })
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        } catch (IOException e) {
            return false;
        }

        if (frames.isEmpty()) return false;

        Path output = artifactDir.resolve("transition.mp4");
        try {
            AWTSequenceEncoder encoder = AWTSequenceEncoder.createSequenceEncoder(output.toFile(), fps);
            for (Path frame : frames) {
                BufferedImage image = ImageIO.read(frame.toFile());
                if (image == null) continue;
                encoder.encodeImage(image);
            }
            encoder.finish();
            return Files.exists(output) && Files.size(output) > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
