.PHONY: build test clean

build:
	./gradlew clean build

test:
	./gradlew test

clean:
	./gradlew clean

# Convenience for local development
run:
	@echo "Run in CloudStream after building"