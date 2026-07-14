package com.example.amrkpi.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards {@code /dashboard} to the static {@code dashboard.html} page (Chart.js + plain JS
 * polling the REST endpoints, no server-side templating or build step). Kept as a thin controller
 * rather than a static-resource mapping so the URL stays clean regardless of how static resources
 * are served.
 */
@Controller
public class DashboardController {

    /**
     * @return a Spring {@code forward:} view name resolving to the static dashboard page
     */
    @GetMapping("/dashboard")
    public String dashboard() {
        return "forward:/dashboard.html";
    }
}
