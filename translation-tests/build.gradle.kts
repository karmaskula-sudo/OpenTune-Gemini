plugins { kotlin("jvm") version "2.2.21" }

// Independent JVM check of the actual production sources; no Android SDK or API key.
// This does not substitute for the Android build with upstream's Kotlin toolchain.
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDir("../app/src/main/java")
        kotlin.srcDir("../core/src/main/java")
        kotlin.include("com/opentune/ui/player/LyricsTranslation.kt", "com/opentune/ui/player/GeminiLyricsStore.kt", "com/opentune/ui/player/GeminiResponse.kt", "com/opentune/data/lyrics/Lyrics.kt")
    }
    test {
        kotlin.srcDir("../app/src/test/java")
        kotlin.include("com/opentune/ui/player/LyricsTranslationTest.kt", "com/opentune/ui/player/GeminiResponseTest.kt")
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("org.robolectric:android-all:14-robolectric-10818077")
    testImplementation("junit:junit:4.13.2")
}
