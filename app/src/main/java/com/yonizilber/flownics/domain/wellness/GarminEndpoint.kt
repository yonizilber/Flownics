package com.yonizilber.flownics.domain.wellness

import java.time.LocalDate

/**
 * The Garmin Connect Personal API's read endpoints Flownics knows, with their paths as in
 * Garmin's own client (connect-personal-api-python). [maxDays] is how many days one request may
 * cover (1 for a single day; 0 for one without dates). [synced] ones are fetched on every sync and
 * kept; the others are only for looking at in the data explorer.
 */
enum class GarminEndpoint(val path: String, val maxDays: Int, val synced: Boolean) {
    SLEEP("/sleep-service/sleep/dailySleep?date={date}", 1, true),
    SLEEP_SUMMARIES("/sleep-service/sleep/dailySleepSummariesByDate?startDate={start}&endDate={end}", 31, false),
    HRV("/hrv-service/hrv/{date}", 1, true),
    HRV_SUMMARIES("/hrv-service/hrv/daily/{start}/{end}", 28, false),
    HEART_RATE("/wellness-service/wellness/dailyHeartRate?date={date}", 1, true),
    BODY_BATTERY("/wellness-service/wellness/bodyBattery/reports/daily?startDate={start}&endDate={end}", 31, true),
    STRESS("/usersummary-service/stats/stress/daily/{start}/{end}", 28, true),
    DAILY_SUMMARIES("/usersummary-service/v2/dailysummaries?startDate={start}&endDate={end}", 31, true),
    SPO2("/wellness-service/wellness/daily/spo2acclimation/{date}", 1, false),
    VO2_MAX("/metrics-service/metrics/maxmet/daily/{start}/{end}", 31, true),
    STEPS("/usersummary-service/stats/steps/daily/{start}/{end}", 28, false),
    WELLNESS_ACTIVITY("/wellnessactivity-service/activity/summary/range/{start}/{end}", 31, false),
    WEIGHT("/weight-service/weight/range/{start}/{end}?includeAll=true", 31, false),
    FITNESS_AGE("/fitnessage-service/fitnessage/v2/{date}", 1, false),
    HEART_RATE_ZONES("/biometric-service/heartRateZones/v2", 0, false),
    PROFILE_SETTINGS("/userprofile-service/userprofile/settings", 0, false),
    ACTIVITIES("/activitylist-service/activities/search/activities?start=0&limit=20&startDate={start}&endDate={end}", 31, false),
    ;

    /** The path for one day, or for the days from [start] to [end]. */
    fun pathFor(start: LocalDate, end: LocalDate = start): String {
        require(maxDays == 0 || !end.isBefore(start)) { "end before start" }
        return path.replace("{date}", start.toString()).replace("{start}", start.toString()).replace("{end}", end.toString())
    }
}
