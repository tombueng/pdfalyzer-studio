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
        public void testAddFieldUsesModalDialogNotPrompt() {
        if (driver == null) {
            System.out.println("Skipping testAddFieldUsesModalDialogNotPrompt - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        wait.until(ExpectedConditions.or(
            ExpectedConditions.textToBePresentInElementLocated(By.id("statusFilename"), "test.pdf"),
            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".toast-msg.text-success"))
        ));

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
        wait.until(ExpectedConditions.or(
                ExpectedConditions.textToBePresentInElementLocated(By.id("statusFilename"), "test.pdf"),
                ExpectedConditions.presenceOfElementLocated(By.cssSelector(".toast-msg.text-success"))
        ));

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

    @Test
    public void testFieldSelectionSyncBetweenTreeAndPdfView() {
        if (driver == null) {
            System.out.println("Skipping testFieldSelectionSyncBetweenTreeAndPdfView - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        wait.until(ExpectedConditions.or(
            ExpectedConditions.textToBePresentInElementLocated(By.id("statusFilename"), "test.pdf"),
            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".toast-msg.text-success"))
        ));

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
        wait.until(ExpectedConditions.or(
            ExpectedConditions.textToBePresentInElementLocated(By.id("statusFilename"), "test.pdf"),
            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".toast-msg.text-success"))
        ));
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
        wait.until(ExpectedConditions.or(
            ExpectedConditions.textToBePresentInElementLocated(By.id("statusFilename"), "test.pdf"),
            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".toast-msg.text-success"))
        ));
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

    @Test
    public void testMovingSelectedFieldsEnablesSaveAndAvoidsGhostHandles() {
        if (driver == null) {
            System.out.println("Skipping testMovingSelectedFieldsEnablesSaveAndAvoidsGhostHandles - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        wait.until(ExpectedConditions.or(
            ExpectedConditions.textToBePresentInElementLocated(By.id("statusFilename"), "test.pdf"),
            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".toast-msg.text-success"))
        ));
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
        if (driver == null) {
            System.out.println("Skipping testFieldOptionsApplyEnablesSaveButton - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        wait.until(ExpectedConditions.or(
            ExpectedConditions.textToBePresentInElementLocated(By.id("statusFilename"), "test.pdf"),
            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".toast-msg.text-success"))
        ));
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".form-field-handle[data-field-name]")));

        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(40));
        Object result = ((JavascriptExecutor) driver).executeAsyncScript(
            "const done = arguments[arguments.length - 1];" +
            "const P = window.PDFalyzer;" +
            "if(!P || !P.state){ done({ok:false,msg:'no-state'}); return; }" +
            "const handle = document.querySelector('.form-field-handle[data-field-name]');" +
            "if(!handle){ done({ok:false,msg:'no-handle'}); return; }" +
            "const fieldName = handle.getAttribute('data-field-name');" +
            "if(!fieldName){ done({ok:false,msg:'no-field-name'}); return; }" +
            "P.state.selectedFieldNames = [fieldName];" +
            "const btn = document.getElementById('formOptionsBtn');" +
            "if(!btn){ done({ok:false,msg:'no-options-btn'}); return; }" +
            "btn.click();" +
            "setTimeout(function(){" +
            "  const optReq = document.getElementById('optRequiredTrue');" +
            "  const applyBtn = document.getElementById('applyFieldOptionsBtn');" +
            "  const reqBlock = document.getElementById('optRequiredBlock');" +
            "  if(!optReq || !applyBtn || !reqBlock){ done({ok:false,msg:'options-controls-missing'}); return; }" +
            "  const blockVisible = !reqBlock.classList.contains('field-option-hidden');" +
            "  if(!blockVisible){ done({ok:false,msg:'required-block-hidden'}); return; }" +
            "  optReq.click();" +
            "  applyBtn.click();" +
            "  setTimeout(function(){" +
            "    const saveBtn = document.getElementById('formSaveBtn');" +
            "    const saveEnabled = !!saveBtn && !saveBtn.disabled;" +
            "    const pendingOpts = Array.isArray(P.state.pendingFieldOptions) ? P.state.pendingFieldOptions.length : 0;" +
            "    done({ ok: saveEnabled && pendingOpts > 0, saveEnabled: saveEnabled, pendingOpts: pendingOpts });" +
            "  }, 180);" +
            "}, 140);"
        );

        assertTrue(result instanceof Map, "Expected script result map");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("ok"), "Applying field options must enable Save button: " + map);
    }

        @Test
        public void testMultiselectTriStateFieldOptionsWorkflow() {
        if (driver == null) {
            System.out.println("Skipping testMultiselectTriStateFieldOptionsWorkflow - ChromeDriver not available");
            return;
        }

        driver.get(baseUrl);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        wait.until(ExpectedConditions.or(
            ExpectedConditions.textToBePresentInElementLocated(By.id("statusFilename"), "test.pdf"),
            ExpectedConditions.presenceOfElementLocated(By.cssSelector(".toast-msg.text-success"))
        ));

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

    private void assertNoClientJsErrors(String context) {
        if (driver == null) return;
        Object raw = ((JavascriptExecutor) driver).executeScript(
                "return Array.isArray(window.__pdfalyzerJsErrors) ? window.__pdfalyzerJsErrors : [];"
        );
        if (!(raw instanceof List<?>)) {
            return;
        }
        List<?> list = (List<?>) raw;
        if (list.isEmpty()) return;

        throw new RuntimeException("JavaScript errors captured in browser (" + context + "): " + list);
    }
}
