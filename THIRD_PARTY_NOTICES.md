# Third-party notices

The release build of this app includes the open-source libraries below, all
licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
The app shows the same list and the full license text in **About & privacy →
Open-source licenses** (`app/src/main/res/raw/apache_license_2_0.txt`).

| Component | Maven groups (release runtime classpath) |
|---|---|
| AndroidX (Android Jetpack) libraries | `androidx.*` |
| Material Components for Android | `com.google.android.material` |
| Kotlin standard library | `org.jetbrains.kotlin` |
| kotlinx.coroutines | `org.jetbrains.kotlinx` |
| JetBrains Java Annotations | `org.jetbrains:annotations` |
| Error Prone annotations | `com.google.errorprone` |
| Guava ListenableFuture (empty placeholder artifact) | `com.google.guava:listenablefuture` |

None of these artifacts ships a `NOTICE` file, so Apache 2.0 §4(d) adds nothing
beyond the license text itself (checked 2026-10-10 against every JAR/AAR on the
release runtime classpath).

Test-only libraries (JUnit, Robolectric, AndroidX Test, fragment-testing) and
build tools are not distributed with the app.

**When dependencies change**, list the release runtime classpath and check each
new artifact's license and any `NOTICE` file:

```bash
./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath
```

Then update this file, `licenses_intro` in `app/src/main/res/values/strings.xml`
and, for a license other than Apache 2.0, the license text the app shows.

The app's own source code has no license file. Choosing one is up to the owner;
until then, all rights are reserved by default.
