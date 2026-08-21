import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La clé de signature vit hors du projet : `keystore.properties` la désigne,
// et n'existe que sur la machine qui publie. Absent, le build release reste
// possible — il sort seulement non signé, donc non installable.
val signature = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "fr.gotatanka.macsn"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.gotatanka.macsn"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            val store = signature.getProperty("storeFile")
            if (store != null) {
                storeFile = file(store)
                storePassword = signature.getProperty("storePassword")
                keyAlias = signature.getProperty("keyAlias")
                keyPassword = signature.getProperty("keyPassword")
                // v3 autorise la rotation de clé plus tard sans casser les
                // mises à jour ; v2 couvre les Android 8 du parc.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Toujours la même clé, sinon la mise à jour est refusée par
            // Android et il faut désinstaller — les lots enregistrés avec.
            if (signature.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures { viewBinding = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Orientation EXIF : le recadrage sur la ligne « Serial » doit se faire
    // dans le même repère que celui où ML Kit a rendu ses coordonnées.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Caméra
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Reconnaissance de texte embarquée (modèle latin inclus dans l'APK, ~4 Mo)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    // Implémentation réelle d'org.json : sans elle, le stub Android du
    // classpath de test renvoie des valeurs par défaut et LotStore ne
    // serait pas testable en JVM.
    testImplementation("org.json:json:20240303")
}
