package com.offershield.auth;

import com.offershield.college.CollegeService;
import com.offershield.college.CollegeSummary;
import com.offershield.common.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class MeService {

    /** Placeholder until P3 adds student memberships. */
    private static final String NO_MEMBERSHIP = "NONE";

    private final AppUserRepository users;
    private final CollegeService collegeService;

    public MeService(AppUserRepository users, CollegeService collegeService) {
        this.users = users;
        this.collegeService = collegeService;
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        AppUser user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        CollegeSummary college = user.getTpoCollegeId() == null
                ? null
                : collegeService.findSummary(user.getTpoCollegeId()).orElse(null);
        return new MeResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(), college, NO_MEMBERSHIP);
    }
}
