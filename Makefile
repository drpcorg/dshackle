all: build-foundation build-main

build-foundation:
	cd foundation && ../gradlew build publishToMavenLocal

build-main:
	./gradlew build

clean:
	./gradlew clean;
	cd foundation && ../gradlew clean