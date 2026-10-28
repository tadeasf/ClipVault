# minifiedCheck test APK only: androidx.test references compile-time errorprone annotations that are not
# shipped. Nothing here concerns app code.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
