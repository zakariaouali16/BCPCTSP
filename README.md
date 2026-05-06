# PMARL Comparison Maven Project

This Maven version runs the comparison between:

1. Greedy prize
2. Greedy ratio
3. PMARL / Q-learning
4. ILP/exact candidate-subset baseline

## Requirements

- Java JDK 17 or newer
- Apache Maven

## Run

From this folder:

```bash
mvn clean compile
mvn exec:java -Dexec.args="usa_towns_with_rewards1000.csv 20 5000 42 16"
```

Arguments are:

```text
csvFile runs budget seed ilpCandidateLimit
```

Example quick test:

```bash
mvn exec:java -Dexec.args="usa_towns_with_rewards1000.csv 1 5000 42 16"
```

## Build a runnable jar

```bash
mvn clean package
java -jar target/pmarl-comparison-1.0-SNAPSHOT.jar usa_towns_with_rewards1000.csv 20 5000 42 16
```
