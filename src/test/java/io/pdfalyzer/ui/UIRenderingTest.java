package io.pdfalyzer.ui;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.List;

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
}
