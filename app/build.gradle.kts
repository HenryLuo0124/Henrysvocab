import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.file.Files

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.eng.henrysvocab"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.eng.henrysvocab"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

@DisableCachingByDefault(because = "Copying a single local photo is cheaper than caching it")
abstract class GenerateForestAsset : DefaultTask() {
    // InputFiles allows a missing path. Do not skip on empty: the old asset must be removed.
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourcePhoto: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = outputDirectory.get().asFile
        Files.createDirectories(directory.toPath())
        val asset = directory.resolve("forest.jpg")
        val photo = sourcePhoto.orNull?.asFile
        if (photo != null && photo.isFile) {
            photo.copyTo(asset, overwrite = true)
        } else {
            Files.deleteIfExists(asset.toPath())
        }
    }
}

val checkoutDirectory = rootProject.layout.projectDirectory
val defaultForestPhoto = "F:/resources/7.jpg"
val forestPhotoPath = providers.gradleProperty("forestPhotoPath").let { configuredPath ->
    // The drive path is absolute on Windows only; never look for a relative F: folder elsewhere.
    if (File(defaultForestPhoto).isAbsolute) configuredPath.orElse(defaultForestPhoto)
    else configuredPath
}
val forestPhoto = forestPhotoPath.map(checkoutDirectory::file)

androidComponents {
    onVariants { variant ->
        val assets = variant.sources.assets ?: return@onVariants
        val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
        val generator = tasks.register<GenerateForestAsset>("generate${variantName}ForestAsset") {
            sourcePhoto.set(forestPhoto)
        }
        assets.addGeneratedSourceDirectory(generator, GenerateForestAsset::outputDirectory)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}