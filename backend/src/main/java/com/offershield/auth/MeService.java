package com.offershield.auth;

import com.offershield.college.CollegeService;
import com.offershield.college.CollegeSummary;
import com.offershield.common.web.NotFoundException;
import com.offershield.membership.MembershipResponse;
import com.offershield.membership.MembershipService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class MeService {

    private static final String NO_MEMBERSHIP = "NONE";

    private final AppUserRepository users;
    private final CollegeService collegeService;
    private final MembershipService membershipService;

    public MeService(AppUserRepository users, CollegeService collegeService, MembershipService membershipService) {
        this.users = users;
        this.collegeService = collegeService;
        this.membershipService = membershipService;
    }

    /**
     * A TPO (or an admin assigned to a college) sees the college they manage; anyone else sees the college of their
     * own join request, whatever its status. {@code membershipStatus} is that request's status, or NONE.
     */
    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        AppUser user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        Optional<MembershipResponse> membership = membershipService.findForUser(userId);
        CollegeSummary college = user.getTpoCollegeId() != null
                ? collegeService.findSummary(user.getTpoCollegeId()).orElse(null)
                : membership.map(m -> new CollegeSummary(m.collegeId(), m.collegeName())).orElse(null);
        String membershipStatus = membership.map(m -> m.status().name()).orElse(NO_MEMBERSHIP);
        return new MeResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(), college, membershipStatus);
    }
}
