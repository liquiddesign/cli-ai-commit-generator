import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
	kotlin("jvm") version "2.3.21"
	id("org.jetbrains.intellij.platform") version "2.15.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
	mavenCentral()
	intellijPlatform {
		defaultRepositories()
	}
}

dependencies {
	intellijPlatform {
		create(
			IntelliJPlatformType.PhpStorm,
			providers.gradleProperty("platformVersion").get()
		)
		bundledPlugin("Git4Idea")
		// IdeaTextPatchBuilder + UnifiedDiffWriter live in this internal module;
		// it is not on the default plugin compile classpath since 2026.x.
		bundledModule("intellij.platform.vcs.impl")
	}
}

kotlin {
	jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

intellijPlatform {
	pluginConfiguration {
		name = providers.gradleProperty("pluginName")
		version = providers.gradleProperty("pluginVersion")

		ideaVersion {
			sinceBuild = "261"
			// No upper bound — plugin should load on all 2026.x and later builds,
			// including EAPs/preview. The IntelliJ Platform Gradle Plugin omits
			// the until-build attribute when this provider yields null.
			untilBuild = provider { null }
		}
	}

	// Skip building the searchable-options index — it boots PhpStorm headlessly
	// and adds many minutes to the build for no runtime gain. Marketplace builds
	// can re-enable this if needed.
	buildSearchableOptions = false
}

tasks {
	wrapper {
		gradleVersion = "9.0.0"
	}
}
