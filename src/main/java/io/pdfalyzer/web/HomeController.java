package io.pdfalyzer.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class HomeController {

    @GetMapping("/")
    public String index() {
        return "index";
    }

    /**
     * Server-rendered redirect page for browser extension.
     * Sets the session ID in localStorage and redirects to the main app,
     * where restoreSessionOnInit() picks it up naturally.
     * This avoids any dependency on cached JS files or URL param parsing.
     */
    @GetMapping("/open/{sessionId}")
    public String openSession(@PathVariable("sessionId") String sessionId, Model model) {
        model.addAttribute("sessionId", sessionId);
        return "open-session";
    }
}
