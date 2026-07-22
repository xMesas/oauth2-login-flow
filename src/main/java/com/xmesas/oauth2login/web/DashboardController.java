package com.xmesas.oauth2login.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.security.Principal;
import java.util.Map;

@Controller
public class DashboardController {

    @GetMapping("/")
    public String home() {
        return "home";
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal OAuth2User principal, Model model) {
        model.addAttribute("name", principal.getAttribute("preferred_username"));
        model.addAttribute("email", principal.getAttribute("email"));
        return "dashboard";
    }

    /** JSON view of the same authenticated principal - straightforward for automated tests. */
    @GetMapping("/api/me")
    @ResponseBody
    public Map<String, Object> me(@AuthenticationPrincipal OAuth2User principal, Principal authPrincipal) {
        return Map.of(
                "username", principal.getAttribute("preferred_username"),
                "email", principal.getAttribute("email"),
                "authenticatedAs", authPrincipal.getName());
    }
}
