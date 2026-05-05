package de.vesterion.vistierie.llm;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/llm")
public class LlmController {
    @PostMapping("/complete")
    public String complete() { return "stub"; }
    @PostMapping("/vision")
    public String vision() { return "stub"; }
}
