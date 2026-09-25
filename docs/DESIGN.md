# open-android-models — design

Agentic tool calling, NPC dialogue and AI decisions on Android's **built-in** on-device model: Gemini Nano, run by the AICore system service. Apps reach it through the **ML Kit GenAI Prompt API** (`com.google.mlkit:genai-prompt`). This is the Android sibling of [open-apple-models](https://github.com/SpaceCorps/open-apple-models). The concepts, the names and the JSON-RPC wire protocol are the same, so a game engine can inject either backend per platform.

## Platform facts (September 2026)

These facts drive the design.

- **Devices.** Gemini Nano only runs on AICore devices:
  - nano-v4 (Gemma 4 E2B/E4B based): Pixel 11, Galaxy Z Flip8 and Fold8.
  - nano-v3 (Gemma 3n based): Pixel 9 and 10, Galaxy S26, some OPPO/vivo/OnePlus/Honor/Sony/Sharp.
  - nano-v2: some others.
  - Minimum SDK is 26. There is no emulator support and no support on unlocked bootloaders.
- **API surface.** `Generation.getClient()` returns a `GenerativeModel`, with:
  - `checkStatus()`, which returns AVAILABLE, DOWNLOADABLE, DOWNLOADING or UNAVAILABLE
  - `download(): Flow<DownloadStatus>` and `warmup()`
  - `generateContent(request)` and `generateContentStream(request): Flow<GenerateContentResponse>`
  - `countTokens`, `getTokenLimit()`, `getBaseModelName()`
  - `isSystemPromptAvailable()` (nano-v3+), `isCachingFeatureAvailable()`, `isThinkingModeAvailable()` (nano-v4) and `isStructuredOutputFeatureAvailable()`
  - Request options: `temperature`, `topK`, `seed`, `maxOutputTokens`, `systemInstruction`, `promptPrefix`, `enableThinking`, and `modelConfig { releaseStage; preference = FAST|FULL }`.
- **Limits.**
  - Input must be under about 4000 tokens; output is capped at 4096.
  - There is **no chat/session object and no roles**, so multi-turn history is ours to render.
  - **There is no native tool or function calling.** Google has promised it, but it had not shipped as of beta4. The design must switch to native tools once they ship; see [Tool calling without native support](#tool-calling-without-native-support).
  - Structured output (Alpha) only works with compile-time KSP `@Generable` classes and is prompt-based. Runtime JSON Schemas are not supported, so we prompt, validate and repair ourselves.
- **Operational limits.**
  - Foreground only: `BACKGROUND_USE_BLOCKED` (30).
  - Per-app quota: `BUSY` (9). Battery quotas: `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` (27) and a per-device one (28).
  - Non-configurable safety filters surface as codes 4, 11 and 15.
- **Terms.** Read https://developers.google.com/ml-kit/genai-terms before shipping. Among other things, they restrict use in services likely to be accessed by under-18s. The README must say this prominently.

## Modules (Gradle, package root `com.spacecorps.oam`)

| Module | Kind | Contents |
|---|---|---|
| `oam-core` | Kotlin/JVM library, no Android dependency | JSON, the JSON Schema subset and validator, `LanguageModel`, `Agent`, the tool loop, `ToolPolicy`, events, structured output, the transcript/prompt renderer, `ScriptedLanguageModel`, and `OpenAICompatibleModel` (for development: runs the loop against any OpenAI-compatible server, for example `oam serve` from open-apple-models) |
| `oam-game` | Kotlin/JVM | `Persona`, `Emotion`, `NPC`, `NPCOptions`, `DialogueTurn`, `NPCMemory`, `WorldState`, `DecisionEngine`, `ContentGenerator`. Mirrors OpenAppleModelsGame. |
| `oam-bridge` | Kotlin/JVM | `BridgeEngine`: the JSON-RPC 2.0 wire protocol v1.0 of open-apple-models' `docs/PROTOCOL.md` (session/*, tool/call, npc/*, decision/*, world/*, content/generate, scripted models). The same methods, parameters, events and error codes; the Android differences are listed in [PROTOCOL.md](PROTOCOL.md) |
| `oam-mlkit` | Android library (AAR) | `GeminiNanoModel : LanguageModel` over the ML Kit Prompt API; availability, download and `AvailabilityMonitor`; error mapping; `OamJni` and `BridgeEngineFactory`, the JNI entry that runs oam-bridge for native hosts such as a game engine written in Rust or C++ |
| `sample` | Android app | Mira the innkeeper: availability/download UI, `check_menu`/`take_order` tools, and a scripted-model toggle so it works without a Gemini Nano device |

Everything except `oam-mlkit` and `sample` is plain JVM code, so it is fully unit-tested on the desktop with the scripted model.

The four libraries are published as `com.spacecorps.oam:<module>:<version>`, with the version set in `gradle.properties`. `oam-core`, `oam-game` and `oam-bridge` are JARs and `oam-mlkit` is an AAR (its release variant); each comes with a sources JAR. There is no remote repository yet. To consume them, either:

- run `./gradlew publishToMavenLocal` and add `mavenLocal()`, or
- add `includeBuild("path/to/open-android-models")` to the consumer's `settings.gradle.kts`. Gradle then substitutes the projects for the same coordinates.

Consumers need Kotlin 2.3 or newer, because the classes carry Kotlin 2.4 metadata. AGP 9's built-in Kotlin defaults to KGP 2.2, so add the plugin to the build classpath: `id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false`. JVM consumers also need Java 21. An Android build that includes this one must use the same AGP version (9.4.1), since AGP refuses two versions in one build. Android consumers do not need this repository's compileSdk (37.1): `oam-mlkit` declares its minSdk as the AAR's minimum compileSdk, and ML Kit's AndroidX dependencies ask for 33.

## Core contracts

```kotlin
interface LanguageModel {
    val capabilities: ModelCapabilities            // nativeToolCalling=false today, systemInstructions, maxInputTokens, maxOutputTokens
    suspend fun availability(): ModelAvailability  // available | downloadable | downloading | unavailable(reason)
    suspend fun countTokens(text: String): Int?    // null when unknown → estimate chars/4
    fun generate(request: GenerationRequest): Flow<GenerationChunk>  // streaming; last chunk isFinal with full text + usage
    suspend fun prewarm(systemInstruction: String? = null, promptPrefix: String? = null) {}  // ML Kit's warmup()
}
```

The following follow open-apple-models:

- `AgentTool` is a name, a description, a JSON Schema for parameters, and either a local suspend handler or **external** execution. For external tools the host answers through `AgentRun.submit(output, callId)`.
- `ToolOutput` is `Text`, `Json` or `Error`.
- `ToolPolicy` has a `choice` (Auto, None, Required, Explicit, Tool(name)), `maxToolRounds`, `maxToolCalls` and `enabledTools`.
- `AgentEvent` is one of `ModelStep`, `Text(delta, text, isReset)`, `Partial`, `ToolCallStarted`, `ToolCallRequested`, `ToolCallCompleted` or `Completed(AgentResponse)`.
- `AgentError(code)` uses the same codes as Apple: model_unavailable, guardrail_violation, refusal, context_size_exceeded, rate_limited, unsupported_language, invalid_schema, tool_failed, cancelled, busy, invalid_request, generation_failed.
- `Agent` serializes turns and rolls back failed turns. `RetryPolicy` retries a failed model step, never a whole turn, so a tool never runs twice: by default 3 attempts with exponential backoff from 300 ms, for `generation_failed`, `busy` and `rate_limited` (unless the model asks to wait more than 5 s).
- **Cancellation.** `AgentRun.cancel()` (or cancelling the coroutine that collects the run) stops the turn first, then resolves its pending external calls with an error output; the turn fails with `cancelled` and leaves no trace. Adding a finished turn to the history is its point of no return (`AgentRun.commit`), atomic with `cancel()`: a turn is either committed or cancelled, never both, and a cancel that arrives after the commit has no effect. The bridge's session and NPC drivers treat a cancelled request as a request to cancel the turn and report its real outcome once it has ended: `-32009 cancelled` after it has rolled back, or its result if it committed first, so a response always matches the history.

## Tool calling without native support

Each **model step** is one of two kinds:

1. **Decide step.** This runs when tools are enabled and the budget allows. The model must output one JSON object, the *step envelope*:
   `{"action": "<tool name>" | "respond", "arguments": { … }}`.
   - `Explicit` and `Auto` offer every enabled tool plus `respond`. Prompt-based tool use needs an explicit decision anyway, so on Android `Auto` behaves like `Explicit`.
   - `Required` offers only tools. With a single enabled tool it asks for that tool's arguments directly, as `Tool(name)` does.
   - `Tool(name)` skips the decision and asks directly for that tool's arguments. A tool without parameters runs with no model step at all.
   - The envelope is parsed leniently: strip code fences, take the first balanced JSON object, accept common synonyms, and match tool names ignoring case and separators.
   - The tool name is checked against the enabled set, and the arguments are coerced and validated against the tool's JSON Schema. If either fails, there is **one repair attempt**: the step is re-prompted with the validation error. A call to a known tool that is still invalid is recorded as an error output ("The call was not made: …") and the tool never runs; output that still names no usable action ends the tool loop.
   - An identical successful call repeated within a turn is read as a decision to reply (`AgentConfiguration.dedupesToolCalls`), because small models repeat lookups.
2. **Respond step.** This produces the natural-language (streamed) or schema-constrained final answer, with all tool results so far included.

Per turn: decide → execute (local, or external through the host) → record → decide again, until `respond` is chosen or the round/call budgets run out. Then respond. When the budget runs out, the respond step is forced.

**Native tool calling is a seam, not a feature yet.** `ModelCapabilities.nativeToolCalling` (fed by `NanoFeatures.nativeToolCalling`) is always false, because ML Kit (through 1.0.0-beta4) has no tool-calling API, and `Agent` always runs the envelope loop. `GenerationRequest.tools` and `GeminiNanoModel`'s request mapping are where tools will go. The plan is that once ML Kit ships tool calling, `GeminiNanoModel` detects it and the agent hands tools to the model instead of running the envelope, without changing the public API.

## Prompt rendering (no roles, ~4K tokens)

- The system instruction (instructions plus the context note) is kept byte-stable across a turn's steps and across turns. It goes in ML Kit's `systemInstruction` where the device supports one (nano-v3+). Otherwise it leads the prompt as the request's prompt prefix, which `GeminiNanoModel` sends as ML Kit's `PromptPrefix` (implicit prefix caching) on devices that report caching support.
- The rest of the prompt is the conversation and a short task section for the step. A decide step's task section lists the offered tools with their argument schemas, so the tool list comes after the conversation, not in the prefix.
- History is rendered as labelled lines (`Player: …`, `Mira: …`, `[check_menu {…} → …]`), trimmed oldest turn first to fit `maxInputTokens` minus a reserve (`ContextPolicy.reservedTokens`, 256 by default). Token counts are estimated at about four characters per token; the model's `countTokens` is used once the estimate passes 60% of that budget.
- `NPC` compacts older turns into a summary note, as on Apple.

## Structured output

The JSON Schema goes into the prompt as compact text. The output is parsed and validated against the schema: enums, ranges, required fields, types. There is one repair retry. Keys are reordered to schema order. Enum decisions (`DecisionEngine`) are validated, and a lenient match maps near-misses such as case or whitespace differences.

## JNI interface (for native hosts)

```kotlin
object OamJni {
    @JvmStatic var engineFactory: MessageEngineFactory                  // defaults to BridgeEngineFactory()
    @JvmStatic fun create(context: Context, nativeHandle: Long): Long   // returns a bridge id, 0 on failure
    @JvmStatic fun send(bridgeId: Long, jsonLine: String)               // non-blocking
    @JvmStatic fun destroy(bridgeId: Long)
    @JvmStatic external fun nativeDeliver(nativeHandle: Long, jsonLine: String)  // implemented by the host's native library
}
```

- The host (Rust, C or C++) passes an opaque `nativeHandle`. Every outgoing JSON-RPC message (responses, `session/event`, `tool/call`, …) is delivered by calling `nativeDeliver` on an attached JVM background thread, in order and never concurrently. Once `destroy` returns, `nativeDeliver` is never called for that handle again.
- The messages are the open-apple-models protocol v1.0 (with the Android differences in [PROTOCOL.md](PROTOCOL.md)), so a host implements the protocol once and injects the Apple backend (`oam_bridge_*` C ABI) or the Android backend (`OamJni`) per platform.
- **Registering `nativeDeliver`.** The host either exports `Java_com_spacecorps_oam_jni_OamJni_nativeDeliver`, which the JVM finds when the library was loaded with `System.loadLibrary`, or registers the method with `RegisterNatives` on the `OamJni` class. Libraries loaded by `NativeActivity` or `GameActivity` are not searched for JNI symbols, so those hosts must use `RegisterNatives`. Registering and also exporting the symbol covers both cases.
- **Finding the class.** Load `com.spacecorps.oam.jni.OamJni` through the activity's class loader: `FindClass` on a native thread only sees system classes.
- **Text.** JNI strings are modified UTF-8, so the bridge escapes characters outside the Basic Multilingual Plane as `\uXXXX\uXXXX` in outgoing lines (`JniText`), and hosts should build incoming strings with `NewString` (UTF-16) rather than passing raw UTF-8 with emoji to `NewStringUTF`.
- `OamJni.engineFactory` (a `BridgeEngineFactory` by default) creates each bridge's engine; all its bridges share one lazily created `GeminiNanoModel`. The R8 keep rules for `OamJni` ship in the AAR's `consumer-rules.pro`.
- [PROTOCOL.md §1](PROTOCOL.md#1-transports) is the host checklist.
