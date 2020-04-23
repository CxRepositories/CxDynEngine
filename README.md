# Checkmarx Dynamic Engines

Service for dynamically provisioning Checkmarx engine servers based on the scan queue.

### Overview

This service is written in Java, using [Spring Boot](https://projects.spring.io/spring-boot/ "Rocks!").  It uses [Gradle](https://gradle.org/ "Is Cool!") for build and dependency management.

### Build

    ./gradlew clean build

*To skip tests:*

    ./gradlew build -x test

### Run

To run the dynamic engine service,

*From command line:*

```
java -Djasypt.encryptor.password='CxR0cks!!' -Dspring.profiles.active=aws -jar cx-dyn-engines-app-0.7.0-SNAPSHOT.jar
```

or

```
java -jar cx-dyn-engines-app-<ver>-SNAPSHOT.jar  \
--spring.profiles.active=aws \
--spring.config.location=application.yml
```

*Required Overrides*
CX_USERNAME | --cx.userName
CX_PASSWORD | --cx.password
AWS_ACCESS_KEY_ID (if not using AWS IAM Instance Role)
AWS_SECRET_ACCESS_KEY (if not using AWS IAM Instance Role)
*See https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html*

Using

*Using Gradle*

```
./gradlew bootRun or ./gradlew clean bootRun
```

#### DB Changes
There is a value that needs to be updated in the dbo. CxComponentConfiguration table -> NumberOfPromotableScans needs to have a value set to 0 (it is initially 3).

