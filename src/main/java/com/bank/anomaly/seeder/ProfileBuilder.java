package com.bank.anomaly.seeder;

import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.repository.TransactionRepository;
import com.bank.anomaly.service.BeneficiaryGraphService;
import com.bank.anomaly.service.IsolationForestTrainingService;
import com.bank.anomaly.service.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Profile("seed")
@Order(2)
public class ProfileBuilder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ProfileBuilder.class);

    private final TransactionRepository transactionRepository;
    private final JdbcTemplate jdbc;
    private final ProfileService profileService;
    private final IsolationForestTrainingService ifTrainingService;
    private final BeneficiaryGraphService beneficiaryGraphService;

    public ProfileBuilder(TransactionRepository transactionRepository,
                          JdbcTemplate jdbc,
                          ProfileService profileService,
                          IsolationForestTrainingService ifTrainingService,
                          BeneficiaryGraphService beneficiaryGraphService) {
        this.transactionRepository = transactionRepository;
        this.jdbc = jdbc;
        this.profileService = profileService;
        this.ifTrainingService = ifTrainingService;
        this.beneficiaryGraphService = beneficiaryGraphService;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Building client profiles from historical data ===");

        List<String> clientIds = jdbc.queryForList(
                "SELECT DISTINCT client_id FROM transactions ORDER BY client_id",
                String.class);

        log.info("Found {} distinct clients in transactions table", clientIds.size());

        for (String clientId : clientIds) {
            List<Transaction> txns = transactionRepository.findByClientId(clientId, 1_000_000, null).data();
            txns.sort(Comparator.comparingLong(Transaction::getTimestamp));

            ClientProfile profile = profileService.getOrCreateProfile(clientId);

            for (Transaction txn : txns) {
                profileService.updateProfile(profile, txn);
            }

            log.info("  Built profile for {}: {} txns, EWMA amount={}, EWMA hourly TPS={}, types={}, distinct beneficiaries={}",
                    clientId, txns.size(),
                    String.format("%.2f", profile.getEwmaAmount()),
                    String.format("%.2f", profile.getEwmaHourlyTps()),
                    profile.getTxnTypeCounts().keySet(),
                    profile.getDistinctBeneficiaryCount());
        }

        log.info("=== Profile building complete for {} clients ===", clientIds.size());

        log.info("=== Training Isolation Forest models ===");
        ifTrainingService.trainForClients(clientIds, 100, 256);
        log.info("=== Isolation Forest training complete ===");

        log.info("=== Building beneficiary graph ===");
        beneficiaryGraphService.refreshGraph();
        log.info("=== Beneficiary graph build complete ===");
    }
}
