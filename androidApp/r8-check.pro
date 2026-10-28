# minifiedCheck build type only: keep the sync stack as if the (step 8) UI called it, so R8 has to shrink,
# optimise and rename BouncyCastle and kotlinx-serialization around it. Not used by real release builds.
-keep class cz.tadeasf.clipvault.sync.** { *; }

# The instrumentation runner loads these from the app under test; the app itself never calls them.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
# The existing instrumented tests call app internals and coroutines directly; keep what they touch so the
# whole on-device suite can run against the minified app. BouncyCastle and kotlinx-serialization stay
# fully shrunk and renamed.
-keep class cz.tadeasf.clipvault.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.collection.** { *; }
-keep class com.google.common.util.concurrent.ListenableFuture { *; }
-keep class androidx.lifecycle.** { *; }
