plugins {
    id("java")
    application
}

group = "top.fifthlight.udprecorder"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.illposed.osc:javaosc-core:0.8")
}

application {
    mainClass = "UdpRecorder"
}
