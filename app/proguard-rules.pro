# Project-specific R8 rules. Retrofit, OkHttp, kotlinx.serialization, Hilt and Room ship their own
# consumer rules; add entries here only when a release build proves one is missing.

# The Anthropic SDK depends on opentelemetry-api, which carries @AutoValue, a compile-time-only
# annotation that isn't shipped. Nothing reads it at runtime (R8 itself proposed this rule).
-dontwarn com.google.auto.value.AutoValue
