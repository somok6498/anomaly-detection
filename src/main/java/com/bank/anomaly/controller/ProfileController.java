package com.bank.anomaly.controller;

import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profiles")
@Tag(name = "Profiles", description = "View client behavioral profiles built from historical transaction data")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @Operation(summary = "Get client behavioral profile",
            description = "Returns the client's EWMA stats, transaction type distribution, " +
                    "hourly TPS averages, and per-type amount averages.")
    @GetMapping("/{clientId}")
    public ResponseEntity<ClientProfile> getProfile(
            @Parameter(description = "Client ID", example = "CLIENT-001")
            @PathVariable String clientId) {
        ClientProfile profile = profileService.getOrCreateProfile(clientId);
        if (profile.getTotalTxnCount() == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(profile);
    }
}
