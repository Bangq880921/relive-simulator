package xyz.qwewqa.relive.simulator.server

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import xyz.qwewqa.relive.simulator.core.stage.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.random.Random

val simulationResults = ConcurrentHashMap<String, SimulationResult>()
val simulationJobs = ConcurrentHashMap<String, Job>()

private val tokenChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
fun generateToken(): String {
    return Array(32) { tokenChars.random() }.joinToString("")
}

private val pool = Executors
    .newFixedThreadPool((Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(1))
    .asCoroutineDispatcher()

private const val SIMULATE_CHUNK_SIZE = 10000
private const val SIMULATE_RESULT_UPDATE_INTERVAL = 10000

fun simulate(parameters: SimulationParameters): String {
    val token = generateToken()
    simulationResults[token] = SimulationResult(
        parameters.maxIterations,
        0,
        emptyList(),
        null,
    )
    if (parameters.maxIterations == 1) {
        simulationJobs[token] = simulateSingle(parameters, token)
    } else {
        simulationJobs[token] = simulateMany(parameters, token)
    }
    return token
}

private fun simulateSingle(parameters: SimulationParameters, token: String) = CoroutineScope(pool).launch {
    val loadout = parameters.createStageLoadoutOrReportError(token) ?: return@launch
    val stage =
        loadout.create(Random(Random(parameters.seed).nextInt()),
            StageConfiguration(logging = true))  // Seed the same was as one iteration of simulateMany
    val result = stage.play(parameters.maxTurns)
    val log = stage.logger.toString()
    simulationResults[token] = SimulationResult(
        parameters.maxIterations,
        1,
        listOf(SimulationResultValue(result.toSimulationResult(), 1)),
        log,
        error = (result as? PlayError)?.exception?.stackTraceToString()
    )
}

private data class IterationResult(val index: Int, val seed: Int, val result: StageResult)

private val StageResult.resultPriority
    get() = when (this) {
        ExcludedRun -> 0
        is Victory -> 1
        is OutOfTurns -> 2
        is TeamWipe -> 3
        is PlayError -> 4
    }

private fun simulateMany(parameters: SimulationParameters, token: String) = CoroutineScope(pool).launch {
    val loadout = parameters.createStageLoadoutOrReportError(token) ?: return@launch
    val resultsChannel = Channel<IterationResult>(SIMULATE_CHUNK_SIZE)
    val seedProducer = Random(parameters.seed)
    (0 until parameters.maxIterations).asSequence().map { it to seedProducer.nextInt() }.chunked(SIMULATE_CHUNK_SIZE)
        .forEach { seeds ->
            launch(pool) {
                seeds.map { (index, seed) ->
                    resultsChannel.send(
                        IterationResult(
                            index,
                            seed,
                            loadout.create(Random(seed), StageConfiguration(logging = false)).play(parameters.maxTurns),
                        )
                    )
                }
            }
        }
    var resultCount = 0
    val resultCounts = mutableMapOf<SimulationResultType, Int>()
    var firstApplicableIteration: IterationResult? = null
    while (resultCount < parameters.maxIterations) {
        val nextIteration = resultsChannel.receive()
        if (firstApplicableIteration == null ||
            firstApplicableIteration.result.resultPriority < nextIteration.result.resultPriority ||
            (firstApplicableIteration.result.resultPriority == nextIteration.result.resultPriority &&
                    nextIteration.index < firstApplicableIteration.index)
        ) {
            firstApplicableIteration = nextIteration
        }
        val nextResult = nextIteration
            .result
            .toSimulationResult()
        resultCount++
        resultCounts[nextResult] = resultCounts.getOrDefault(nextResult, 0) + 1
        if (resultCount % SIMULATE_RESULT_UPDATE_INTERVAL == 0) {
            simulationResults[token] = SimulationResult(
                parameters.maxIterations,
                resultCount,
                resultCounts.map { (k, v) -> SimulationResultValue(k, v) },
                null
            )
        }
    }
    val loggedResult = firstApplicableIteration?.let {
        val stage = loadout.create(Random(it.seed), StageConfiguration(logging = true))
        val playResult = stage.play(parameters.maxTurns)
        "Iteration ${it.index + 1}\n${stage.logger}" to playResult
    }
    simulationResults[token] = SimulationResult(
        parameters.maxIterations,
        resultCount,
        resultCounts.map { (k, v) -> SimulationResultValue(k, v) },
        loggedResult?.first,
        false,
        (loggedResult?.second as? PlayError)?.exception?.stackTraceToString(),
    )
}

private fun SimulationParameters.createStageLoadoutOrReportError(token: String) = try {
    createStageLoadout()
} catch (e: Exception) {
    simulationResults[token] = SimulationResult(
        maxIterations,
        0,
        emptyList(),
        "Error occurred during setup.",
        cancelled = true,
        error = e.stackTraceToString(),
    )
    null
}

fun StageResult.toSimulationResult() = when (this) {
    ExcludedRun -> SimulationResultType.Excluded
    is OutOfTurns -> SimulationResultType.End
    is PlayError -> SimulationResultType.Error
    is TeamWipe -> SimulationResultType.Wipe(turn)
    is Victory -> SimulationResultType.Victory(turn)
}
