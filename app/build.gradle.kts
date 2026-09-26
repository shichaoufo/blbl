plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf")
}

/**
 * 当前代码对应的版本号，取自 `CHANGELOG.md` 里最新的版本标题（如 `## 0.1.29`）。
 *
 * 该标题与上游的 git tag / Release（`v0.1.29`）一一对应，所以它就是这份源码的真实版本。
 * 这里原先的兜底值是写死的 `"0.1.0"`：本地直接 Build（Android Studio 或裸 `./gradlew`）
 * 不传 `-PversionName` 时，APK 就会带着一个与真实版本无关的旧号 ——
 * 系统「应用信息」里看不到真实版本，App 内「检查更新」也会拿它去和上游比较。
 */
val changelogVersionName: String =
    Regex("""^#{1,6}\s+\[?v?(\d+(?:\.\d+)+)""", RegexOption.MULTILINE)
        .find(rootProject.file("CHANGELOG.md").takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty())
        ?.groupValues
        ?.get(1)
        ?.takeIf { it.isNotBlank() }
        ?: "0.0.0"

/**
 * 由 [changelogVersionName] 推导的 versionCode（`0.1.29` → `129`）。
 *
 * 只用于本地直接 Build 时的兜底：CI 与正式发布流程会显式传 `-PversionCode`（上游用
 * GitHub run_number）。取这个基数是为了让本地包比 CI 的 run_number 大，避免
 * 「装过 CI 包之后再装本地包」被系统判为版本降级而拒绝安装。
 */
val changelogVersionCode: Int =
    Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?""")
        .find(changelogVersionName)
        ?.let { m ->
            val major = m.groupValues[1].toIntOrNull() ?: 0
            val minor = m.groupValues[2].toIntOrNull() ?: 0
            val patch = m.groupValues[3].toIntOrNull() ?: 0
            major * 10_000 + minor * 100 + patch
        }
        ?: 1

android {
    namespace = "blbl.cat3399"
    compileSdk = 36

    fun propOrEnv(name: String): String? {
        val fromProp = project.findProperty(name) as String?
        if (!fromProp.isNullOrBlank()) return fromProp
        val fromEnv = System.getenv(name)
        if (!fromEnv.isNullOrBlank()) return fromEnv
        return null
    }

    defaultConfig {
        applicationId = "blbl.cat3399"
        minSdk = 21
        targetSdk = 36
        // 版本号优先级：-PversionName / versionCode（CI 与正式发布流程传参）
        //   → CHANGELOG.md 推导出的真实版本（本地直接 Build 走这条）→ 兜底值。
        versionCode = propOrEnv("versionCode")?.toIntOrNull() ?: changelogVersionCode
        versionName = propOrEnv("versionName") ?: changelogVersionName

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.keystore")
            storePassword = propOrEnv("RELEASE_STORE_PASSWORD")
            keyAlias = propOrEnv("RELEASE_KEY_ALIAS")
            keyPassword = propOrEnv("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
        jniLibs {
            // IjkPlayer native libs are shipped as an on-demand plugin (downloaded when needed).
            excludes += setOf("**/libijkplayer.so")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        register("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.72.0"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                register("java") {
                    option("lite")
                }
            }
            task.plugins {
                register("grpc") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(files("libs/ijkplayer-cmake-release.aar"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.brotli:dec:0.1.2")

    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")

    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    implementation("io.grpc:grpc-okhttp:1.72.0")
    implementation("io.grpc:grpc-protobuf-lite:1.72.0")
    implementation("io.grpc:grpc-stub:1.72.0")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // 单测里需要真实可用的 org.json：android.jar 中的 org.json 在 JVM 单测下是
    // 抛异常的空桩（"not mocked"），解析 DANMU_MSG 表情的回归测试需要它。
    testImplementation("org.json:json:20240303")
}

// Enforce theme-token usage in layouts so adding new theme presets doesn't silently break contrast.
val checkThemeTokens =
    tasks.register("checkThemeTokens") {
        group = "verification"
        description = "Fails if layouts reference fixed palette colors instead of theme attributes."

        doLast {
            val resDir = file("src/main/res")
            val layoutDirs =
                resDir
                    .listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("layout") }
                    .orEmpty()

            fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

            // Match whole resource refs (word boundary) to avoid false positives like
            // `@color/blbl_text_on_media` or `@drawable/blbl_focus_bg_round_danger`.
            fun containsWholeToken(line: String, token: String): Boolean {
                var fromIndex = 0
                while (true) {
                    val idx = line.indexOf(token, startIndex = fromIndex)
                    if (idx < 0) return false
                    val before = line.getOrNull(idx - 1)
                    val after = line.getOrNull(idx + token.length)
                    val beforeOk = before == null || !isWordChar(before)
                    val afterOk = after == null || !isWordChar(after)
                    if (beforeOk && afterOk) return true
                    fromIndex = idx + token.length
                }
            }

            val forbidden =
                listOf(
                    "@color/blbl_bg",
                    "@color/blbl_surface",
                    "@color/blbl_text",
                    "@color/blbl_text_secondary",
                    "@color/blbl_focus_stroke",
                    "@drawable/blbl_focus_bg_round",
                )

            val violations = mutableListOf<String>()
            for (dir in layoutDirs) {
                dir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
                    .forEach { f ->
                        val relPath = f.relativeTo(projectDir).invariantSeparatorsPath
                        val lines = f.readLines(Charsets.UTF_8)
                        for ((index, line) in lines.withIndex()) {
                            for (token in forbidden) {
                                if (containsWholeToken(line, token)) {
                                    violations.add("$relPath:${index + 1}: $token")
                                }
                            }
                        }
                    }
            }

            if (violations.isNotEmpty()) {
                val msg =
                    buildString {
                        appendLine("Theme token check failed: layouts must use theme attributes, not fixed palette colors.")
                        appendLine(
                            "Use ?attr/colorOnSurface, ?android:attr/textColorSecondary, ?attr/colorBackground, " +
                                "?attr/colorSurface, ?attr/blblOnPageBackdrop, ?attr/blblFocusBgRound, " +
                                "?attr/blblFocusStrokeColor, etc.",
                        )
                        appendLine("Violations:")
                        violations.forEach { appendLine("  $it") }
                    }
                throw GradleException(msg)
            }
        }
    }

tasks.named("preBuild").configure {
    dependsOn(checkThemeTokens)
}
