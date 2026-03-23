#!/usr/bin/env bash
clear
kill $(lsof -ti:8080) 2>/dev/null || true
./gradlew clean bootJar -q && java -jar build/libs/grimo-0.0.1-SNAPSHOT.jar
