param(
    [string]$Benchmark = "CompressionBenchmark"
)

mvn -DskipTests package
java -jar target\benchmarks.jar $Benchmark -prof gc