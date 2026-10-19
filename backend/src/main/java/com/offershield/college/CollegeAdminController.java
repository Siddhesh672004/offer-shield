package com.offershield.college;

import com.offershield.auth.AppOidcUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Admin-only college management (P3-01). The acting admin always comes from the session principal. */
@RestController
@RequestMapping("/api/admin/colleges")
@PreAuthorize("hasRole('ADMIN')")
public class CollegeAdminController {

    private final CollegeService collegeService;

    public CollegeAdminController(CollegeService collegeService) {
        this.collegeService = collegeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CollegeResponse create(@Valid @RequestBody CreateCollegeRequest request,
                                  @AuthenticationPrincipal AppOidcUser admin) {
        return collegeService.create(request, admin.userId());
    }

    @GetMapping
    public List<AdminCollegeResponse> list() {
        return collegeService.listForAdmin();
    }

    @PostMapping("/{id}/tpos")
    public AssignTpoResponse assignTpo(@PathVariable UUID id, @Valid @RequestBody AssignTpoRequest request,
                                       @AuthenticationPrincipal AppOidcUser admin) {
        return collegeService.assignTpo(id, request, admin.userId());
    }
}
