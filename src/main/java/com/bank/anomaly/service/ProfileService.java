package com.bank.anomaly.service;

import com.bank.anomaly.config.RiskThresholdConfig;
import com.bank.anomaly.model.ClientProfile;
import com.bank.anomaly.model.Transaction;
import com.bank.anomaly.repository.ClientProfileRepository;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);
    private static final DateTimeFormatter HOUR_BUCKET_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY_BUCKET_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final ClientProfileRepository profileRepository;
    private final RiskThresholdConfig thresholdConfig;

    public ProfileService(ClientProfileRepository profileRepository,
                          RiskThresholdConfig thresholdConfig) {
        this.profileRepository = profileRepository;
        this.thresholdConfig = thresholdConfig;
    }

    /**
     * Get a client's behavioral profile. Returns a new empty profile if none exists.
     */
    @Observed(name = "profile.get_or_create", contextualName = "load-client-profile")
    public ClientProfile getOrCreateProfile(String clientId) {
        ClientProfile profile = profileRepository.findByClientId(clientId);
        if (profile == null) {
            profile = ClientProfile.builder()
                    .clientId(clientId)
                    .build();
        }
        return profile;
    }

    /**
     * Incrementally update the client profile with a new transaction using EWMA.
     * This should be called AFTER the transaction has been evaluated (so scoring
     * uses the pre-update profile).
     */
    @Observed(name = "profile.update", contextualName = "update-client-profile")
    public void updateProfile(ClientProfile profile, Transaction txn) {
        double alpha = thresholdConfig.getEwmaAlpha();
        long n = profile.getTotalTxnCount();

        // 1. Update transaction type counts
        profile.getTxnTypeCounts().merge(txn.getTxnType(), 1L, Long::sum);
        profile.setTotalTxnCount(n + 1);

        // 2. Update EWMA amount using Welford's online algorithm for variance
        if (n == 0) {
            // First transaction — initialize
            profile.setEwmaAmount(txn.getAmount());
            profile.setAmountM2(0.0);
        } else {
            // EWMA update for mean
            double oldMean = profile.getEwmaAmount();
            double newMean = alpha * txn.getAmount() + (1 - alpha) * oldMean;
            profile.setEwmaAmount(newMean);

            // Welford's online variance update
            double delta = txn.getAmount() - oldMean;
            double delta2 = txn.getAmount() - newMean;
            profile.setAmountM2(profile.getAmountM2() + delta * delta2);
        }

        // 3. Update per-type amount stats
        String txnType = txn.getTxnType();
        long typeCount = profile.getAmountCountByType().getOrDefault(txnType, 0L);
        double oldTypeMean = profile.getAvgAmountByType().getOrDefault(txnType, 0.0);

        if (typeCount == 0) {
            profile.getAvgAmountByType().put(txnType, txn.getAmount());
            profile.getAmountM2ByType().put(txnType, 0.0);
        } else {
            double newTypeMean = alpha * txn.getAmount() + (1 - alpha) * oldTypeMean;
            profile.getAvgAmountByType().put(txnType, newTypeMean);

            double delta = txn.getAmount() - oldTypeMean;
            double delta2 = txn.getAmount() - newTypeMean;
            double oldM2 = profile.getAmountM2ByType().getOrDefault(txnType, 0.0);
            profile.getAmountM2ByType().put(txnType, oldM2 + delta * delta2);
        }
        profile.getAmountCountByType().merge(txnType, 1L, Long::sum);

        // 4. Handle hourly TPS rollover
        String currentHourBucket = getHourBucket(txn.getTimestamp());
        String lastHourBucket = profile.getLastHourBucket();

        if (lastHourBucket != null && !lastHourBucket.equals(currentHourBucket)) {
            // Hour has rolled over — update hourly TPS stats with the completed hour's count
            long completedHourCount = profileRepository.getHourlyCount(
                    profile.getClientId() + ":" + lastHourBucket);
            long completedHourAmount = profileRepository.getHourlyAmount(
                    profile.getClientId() + ":" + lastHourBucket);

            updateHourlyTpsStats(profile, completedHourCount);
            updateHourlyAmountStats(profile, completedHourAmount);
        }
        profile.setLastHourBucket(currentHourBucket);

        // 4.5 Handle daily rollover — update daily EWMA stats
        String currentDayBucket = getDayBucket(txn.getTimestamp());
        String lastDayBucket = profile.getLastDayBucket();

        if (lastDayBucket != null && !lastDayBucket.equals(currentDayBucket)) {
            long completedDayAmountPaise = profileRepository.getDailyAmount(
                    profile.getClientId() + ":" + lastDayBucket);
            long completedDayNewBeneCount = profileRepository.getDailyNewBeneCount(
                    profile.getClientId() + ":newbene:" + lastDayBucket);

            updateDailyAmountStats(profile, completedDayAmountPaise);
            updateDailyNewBeneStats(profile, completedDayNewBeneCount);
        }
        profile.setLastDayBucket(currentDayBucket);

        // 5. Increment hourly counters in Aerospike (atomic)
        String counterKey = profile.getClientId() + ":" + currentHourBucket;
        profileRepository.incrementHourlyCounter(counterKey);
        profileRepository.addHourlyAmount(counterKey, (long) (txn.getAmount() * 100)); // store as paise

        // 5.3 Increment daily counters in Aerospike (atomic)
        String dailyCounterKey = profile.getClientId() + ":" + currentDayBucket;
        profileRepository.incrementDailyCounter(dailyCounterKey);
        profileRepository.addDailyAmount(dailyCounterKey, (long) (txn.getAmount() * 100));

        // 5.5 Update beneficiary stats (if beneficiary data present)
        String beneKey = txn.getBeneficiaryKey();
        if (beneKey != null) {
            long beneCount = profile.getBeneficiaryTxnCounts().getOrDefault(beneKey, 0L);

            // Track distinct beneficiary count (increment on first encounter)
            if (beneCount == 0) {
                profile.setDistinctBeneficiaryCount(profile.getDistinctBeneficiaryCount() + 1);
            }

            // Track new-beneficiary for daily velocity (Rule 11)
            if (beneCount == 0) {
                String dailyBeneCounterKey = profile.getClientId() + ":newbene:" + currentDayBucket;
                profileRepository.incrementDailyNewBeneCounter(dailyBeneCounterKey);
            }

            // Track daily beneficiary amount for cross-channel detection (Rule 13)
            String dailyBeneAmtKey = profile.getClientId() + ":beneDaily:" + currentDayBucket + ":" + beneKey;
            profileRepository.addDailyBeneficiaryAmount(dailyBeneAmtKey, (long) (txn.getAmount() * 100));

            // Increment beneficiary transaction count
            profile.getBeneficiaryTxnCounts().merge(beneKey, 1L, Long::sum);

            // EWMA + Welford for per-beneficiary amount
            if (beneCount == 0) {
                profile.getEwmaAmountByBeneficiary().put(beneKey, txn.getAmount());
                profile.getAmountM2ByBeneficiary().put(beneKey, 0.0);
            } else {
                double oldBeneMean = profile.getEwmaAmountByBeneficiary().getOrDefault(beneKey, 0.0);
                double newBeneMean = alpha * txn.getAmount() + (1 - alpha) * oldBeneMean;
                profile.getEwmaAmountByBeneficiary().put(beneKey, newBeneMean);

                double beneDelta = txn.getAmount() - oldBeneMean;
                double beneDelta2 = txn.getAmount() - newBeneMean;
                double oldBeneM2 = profile.getAmountM2ByBeneficiary().getOrDefault(beneKey, 0.0);
                profile.getAmountM2ByBeneficiary().put(beneKey, oldBeneM2 + beneDelta * beneDelta2);
            }

            // Atomic increment beneficiary hourly counters
            String beneCounterKey = profile.getClientId() + ":" + beneKey + ":" + currentHourBucket;
            profileRepository.incrementBeneficiaryCounter(beneCounterKey);
            profileRepository.addBeneficiaryAmount(beneCounterKey, (long) (txn.getAmount() * 100));
        }

        // 6. Update timestamp and save
        profile.setLastUpdated(System.currentTimeMillis());
        profileRepository.save(profile);
    }

    /**
     * Get the current hourly transaction count for a client.
     */
    public long getCurrentHourlyCount(String clientId, long timestamp) {
        String counterKey = clientId + ":" + getHourBucket(timestamp);
        return profileRepository.getHourlyCount(counterKey);
    }

    /**
     * Get the current hourly total amount for a client.
     */
    public long getCurrentHourlyAmount(String clientId, long timestamp) {
        String counterKey = clientId + ":" + getHourBucket(timestamp);
        return profileRepository.getHourlyAmount(counterKey);
    }

    /**
     * Get the current hourly transaction count for a specific beneficiary.
     */
    public long getCurrentBeneficiaryCount(String clientId, String beneKey, long timestamp) {
        String counterKey = clientId + ":" + beneKey + ":" + getHourBucket(timestamp);
        return profileRepository.getBeneficiaryCount(counterKey);
    }

    /**
     * Get the current hourly total amount for a specific beneficiary.
     */
    public long getCurrentBeneficiaryAmount(String clientId, String beneKey, long timestamp) {
        String counterKey = clientId + ":" + beneKey + ":" + getHourBucket(timestamp);
        return profileRepository.getBeneficiaryAmount(counterKey);
    }

    public long getCurrentDailyCount(String clientId, long timestamp) {
        String counterKey = clientId + ":" + getDayBucket(timestamp);
        return profileRepository.getDailyCount(counterKey);
    }

    public long getCurrentDailyAmount(String clientId, long timestamp) {
        String counterKey = clientId + ":" + getDayBucket(timestamp);
        return profileRepository.getDailyAmount(counterKey);
    }

    public long getCurrentDailyNewBeneCount(String clientId, long timestamp) {
        String counterKey = clientId + ":newbene:" + getDayBucket(timestamp);
        return profileRepository.getDailyNewBeneCount(counterKey);
    }

    public long getCurrentDailyBeneficiaryAmount(String clientId, String beneKey, long timestamp) {
        String counterKey = clientId + ":beneDaily:" + getDayBucket(timestamp) + ":" + beneKey;
        return profileRepository.getDailyBeneficiaryAmount(counterKey);
    }

    /**
     * Update the EWMA hourly TPS stats when an hour rolls over.
     */
    private void updateHourlyTpsStats(ClientProfile profile, long hourCount) {
        long completedHours = profile.getCompletedHoursCount();

        if (completedHours == 0) {
            profile.setEwmaHourlyTps(hourCount);
            profile.setTpsM2(0.0);
        } else {
            double alpha = thresholdConfig.getEwmaAlpha();
            // Use a faster alpha for hourly stats since we have fewer data points
            double hourlyAlpha = Math.min(0.1, alpha * 10);

            double oldMean = profile.getEwmaHourlyTps();
            double newMean = hourlyAlpha * hourCount + (1 - hourlyAlpha) * oldMean;
            profile.setEwmaHourlyTps(newMean);

            double delta = hourCount - oldMean;
            double delta2 = hourCount - newMean;
            profile.setTpsM2(profile.getTpsM2() + delta * delta2);
        }

        profile.setCompletedHoursCount(completedHours + 1);
    }

    /**
     * Update the EWMA hourly amount stats when an hour rolls over.
     */
    private void updateHourlyAmountStats(ClientProfile profile, long hourAmountPaise) {
        double hourAmount = hourAmountPaise / 100.0;
        long completedHours = profile.getCompletedHoursCount();

        if (completedHours == 0) {
            profile.setEwmaHourlyAmount(hourAmount);
            profile.setHourlyAmountM2(0.0);
        } else {
            double alpha = thresholdConfig.getEwmaAlpha();
            double hourlyAlpha = Math.min(0.1, alpha * 10);

            double oldMean = profile.getEwmaHourlyAmount();
            double newMean = hourlyAlpha * hourAmount + (1 - hourlyAlpha) * oldMean;
            profile.setEwmaHourlyAmount(newMean);

            double delta = hourAmount - oldMean;
            double delta2 = hourAmount - newMean;
            profile.setHourlyAmountM2(profile.getHourlyAmountM2() + delta * delta2);
        }
    }

    private void updateDailyAmountStats(ClientProfile profile, long dayAmountPaise) {
        double dayAmount = dayAmountPaise / 100.0;
        long completedDays = profile.getCompletedDaysCount();
        double dailyAlpha = Math.min(0.1, thresholdConfig.getEwmaAlpha() * 10);

        if (completedDays == 0) {
            profile.setEwmaDailyAmount(dayAmount);
            profile.setDailyAmountM2(0.0);
        } else {
            double oldMean = profile.getEwmaDailyAmount();
            double newMean = dailyAlpha * dayAmount + (1 - dailyAlpha) * oldMean;
            profile.setEwmaDailyAmount(newMean);

            double delta = dayAmount - oldMean;
            double delta2 = dayAmount - newMean;
            profile.setDailyAmountM2(profile.getDailyAmountM2() + delta * delta2);
        }
        profile.setCompletedDaysCount(completedDays + 1);
    }

    private void updateDailyNewBeneStats(ClientProfile profile, long dayNewBeneCount) {
        long completedDays = profile.getCompletedDaysForBeneCount();
        double dailyAlpha = Math.min(0.1, thresholdConfig.getEwmaAlpha() * 10);

        if (completedDays == 0) {
            profile.setEwmaDailyNewBeneficiaries(dayNewBeneCount);
            profile.setDailyNewBeneM2(0.0);
        } else {
            double oldMean = profile.getEwmaDailyNewBeneficiaries();
            double newMean = dailyAlpha * dayNewBeneCount + (1 - dailyAlpha) * oldMean;
            profile.setEwmaDailyNewBeneficiaries(newMean);

            double delta = dayNewBeneCount - oldMean;
            double delta2 = dayNewBeneCount - newMean;
            profile.setDailyNewBeneM2(profile.getDailyNewBeneM2() + delta * delta2);
        }
        profile.setCompletedDaysForBeneCount(completedDays + 1);
    }

    public static String getHourBucket(long epochMillis) {
        return HOUR_BUCKET_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String getDayBucket(long epochMillis) {
        return DAY_BUCKET_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }
}
