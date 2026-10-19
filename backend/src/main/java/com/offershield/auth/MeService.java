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
     * A student (USER) sees the college of their own join request, whatever its status, and that request's status
     * (NONE without one). Staff never act as students: a TPO, or an admin, sees the college they manage (none for an
     * admin without one) and status NONE, even if an old join request from their student days is still stored.
     */
    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        AppUser user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getRole() != Role.USER) {
            CollegeSummary managed = user.getTpoCollegeId() == null ? null
                    : collegeService.findSummary(user.getTpoCollegeId()).orElse(null);
            return new MeResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(), managed, NO_MEMBERSHIP);
        }
        Optional<MembershipResponse> membership = membershipService.findForUser(userId);
        CollegeSummary college = membership.map(m -> new CollegeSummary(m.collegeId(), m.collegeName())).orElse(null);
        String membershipStatus = membership.map(m -> m.status().name()).orElse(NO_MEMBERSHIP);
        return new MeResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(), college, membershipStatus);
    }
}
