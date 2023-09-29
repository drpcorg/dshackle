all: build-foundation build-main

build-foundation:
	cd foundation && ../gradlew build publishToMavenLocal

build-main:
	./gradlew build

test: build-foundation
	./gradlew check

clean:
	./gradlew clean;
	cd foundation && ../gradlew clean