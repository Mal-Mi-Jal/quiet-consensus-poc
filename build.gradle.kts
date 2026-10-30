plugins {
    java
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.quietconsensus"
version = "0.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 시뮬레이션/LLM 평가 결과를 콘솔에서 보려고 표준 출력을 보여 준다
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
    // -Dollama=true 로 실행하면 LLM 평가 테스트가 켜진다
    systemProperty("ollama", System.getProperty("ollama", "false"))
    systemProperty("evalSet", System.getProperty("evalSet", "all"))
}
