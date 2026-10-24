# Keep domain & Firestore serialization models intact under R8
-keep class com.vumiq.cashbook.domain.** { *; }
-keep class com.vumiq.cashbook.data.** { *; }

# Firebase, Credential Manager, Google Identity & WorkManager rules
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
-dontwarn com.google.android.libraries.identity.googleid.**
