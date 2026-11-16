# NovaTV uses no reflection-based serialization (org.json is used manually),
# so the default Android + library consumer rules are sufficient.
# Media3 and OkHttp ship their own consumer ProGuard rules.

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp optional platform integrations not present on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
