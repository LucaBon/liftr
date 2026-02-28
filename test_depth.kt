fun main() {
    val minHipKneeDistance = -0.15f
    val actualDistance = -0.13f
    val tolerance = 0.02f
    
    val result = actualDistance <= (minHipKneeDistance + tolerance)
    println("actualDistance: $actualDistance")
    println("minHipKneeDistance: $minHipKneeDistance")
    println("tolerance: $tolerance")
    println("threshold: ${minHipKneeDistance + tolerance}")
    println("Test: $actualDistance <= ${minHipKneeDistance + tolerance}")
    println("Result: $result")
}
