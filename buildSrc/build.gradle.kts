plugins {
    `kotlin-dsl`
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("org.yaml:snakeyaml:1.24")
    implementation("dshackle:foundation:1.0.0")
    implementation("com.squareup:kotlinpoet:2.3.0")
}
