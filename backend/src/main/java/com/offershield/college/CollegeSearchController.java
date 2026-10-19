package com.offershield.college;

import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Any signed-in user may search ACTIVE colleges (the security chain already requires a session for /api/**). */
@RestController
@RequestMapping("/api/colleges")
public class CollegeSearchController {

    private final CollegeService collegeService;

    public CollegeSearchController(CollegeService collegeService) {
        this.collegeService = collegeService;
    }

    @GetMapping
    public List<CollegeSearchItem> search(@RequestParam(required = false) @Size(max = 200) String q) {
        return collegeService.search(q);
    }
}
