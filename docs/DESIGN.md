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
  - `isSystemPromptAvailable()` (nano-v3+) and `isStructuredOutputFeatureAvailable()`
  - Request options: `temperature`, `topK`, `seed`, `maxOutputTokens`, `systemInstruction`, `promptPrefix`, and `modelConfig { releaseStage; preference = FAST|FULL }`.
- **Limits.**
  - Input must be under about 4000 tokens; output is capped at 4096.
  - There is **no chat/session object and no roles**, so multi-turn history is ours to render.
  - **There is no native tool or function calling.** Google has promised it, but it had not shipped as of beta4. The design must switch to native tools once they ship; see `LanguageModel.capabilities`.
  - Structured output (Alpha) only works with compile-time KSP `@Generable` classes and is prompt-based. Runtime JSON Schemas are not supported, so we prompt, validate and repair ourselves.
- **Operational limits.**
  - Foreground only: `BACKGROUND_USE_BLOCKED` (30).
  - Per-app quota: `BUSY` (9). Battery quota: `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` (27).
  - Non-configurable safety filters surface as codes 4, 11 and 15.
- **Terms.** Read https://developers.google.com/ml-kit/genai-terms before shipping. Among other things, they restrict use in services likely to be accessed by under-18s. The README must say this prominently.

## Modules (Gradle, package root `com.spacecorps.oam`)

| Module | Kind | Contents |
|---|---|---|
| `oam-core` | Kotlin/JVM library, no Android dependency | JSON, the JSON Schema subset and validator, `LanguageModel`, `Agent`, the tool loop, `ToolPolicy`, events, structured output, the transcript/prompt renderer, `ScriptedLanguageModel`, and `OpenAICompatibleModel` (for development: runs the loop against any OpenAI-compatible server, for example `oam serve` from open-apple-models) |
| `oam-game` | Kotlin/JVM | `Persona`, `Emotion`, `NPC`, `NPCOptions`, `DialogueTurn`, `NPCMemory`, `WorldState`, `DecisionEngine`, `ContentGenerator`. Mirrors OpenAppleModelsGame. |
| `oam-bridge` | Kotlin/JVM | `BridgeEngine`: JSON-RPC 2.0 wire protocol v1.0, identical to open-apple-models `docs/PROTOCOL.md` (session/*, tool/call, npc/*, decision/*, world/*, content/generate, scripted models) |
| `oam-mlkit` | Android library (AAR) | `GeminiNanoModel : LanguageModel` over the ML Kit Prompt API; availability and download; error mapping; `OamJni` (the JNI entry for native hosts such as Space3d) |
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
}
```

The following follow open-apple-models:

- `AgentTool` is a name, a description, a JSON Schema for parameters, and either a local suspend handler or **external** execution. For external tools the host answers through `AgentRun.submit(output, callId)`.
- `ToolOutput` is `Text`, `Json` or `Error`.
- `ToolPolicy` has a `choice` (Auto, None, Required, Explicit, Tool(name)), `maxToolRounds`, `maxToolCalls` and `enabledTools`.
- `AgentEvent` is one of `ModelStep`, `Text(delta, text, isReset)`, `Partial`, `ToolCallStarted`, `ToolCallRequested`, `ToolCallCompleted` or `Completed(AgentResponse)`.
- `AgentError(code)` uses the same codes as Apple: model_unavailable, guardrail_violation, refusal, context_size_exceeded, rate_limited, unsupported_language, invalid_schema, tool_failed, cancelled, busy, invalid_request, generation_failed.
- `Agent` serializes turns, rolls back failed turns, and retries transient errors (`BUSY`) with backoff.

## Tool calling without native support

Each **model step** is one of two kinds:

1. **Decide step.** This runs when tools are enabled and the budget allows. The model must output one JSON object, the *step envelope*:
   `{"action": "<tool name>" | "respond", "arguments": { … }}`.
   - `Explicit` and `Auto` offer every enabled tool plus `respond`. Prompt-based tool use needs an explicit decision anyway, so on Android `Auto` behaves like `Explicit`.
   - `Required` offers only tools.
   - `Tool(name)` skips the decision and asks directly for that tool's arguments.
   - The envelope is parsed leniently: strip code fences, take the first balanced JSON object, accept common synonyms.
   - The tool name is checked against the enabled set, and the arguments against the tool's JSON Schema. If either fails, there is **one repair attempt**: the step is re-prompted with the validation error.
2. **Respond step.** This produces the natural-language (streamed) or schema-constrained final answer, with all tool results so far included.

Per turn: decide → execute (local, or external through the host) → record → decide again, until `respond` is chosen or the round/call budgets run out. Then respond. When the budget runs out, the respond step is forced.

When `capabilities.nativeToolCalling` becomes true, `GeminiNanoModel` hands tools to ML Kit and the envelope is skipped. The public API does not change.

## Prompt rendering (no roles, ~4K tokens)

- The system instruction (persona + rules) goes in `systemInstruction` when it is available. Otherwise it becomes a prefix. It is kept byte-stable across turns so ML Kit's prefix caching can help.
- History is rendered as labelled lines (`Player: …`, `Mira: …`, `[check_menu → {…}]`), trimmed oldest-first to fit `maxInputTokens` minus a reserve.
- `NPC` compacts older turns into a summary note, as on Apple.

## Structured output

The JSON Schema goes into the prompt as compact text. The output is parsed and validated against the schema: enums, ranges, required fields, types. There is one repair retry. Keys are reordered to schema order. Enum decisions (`DecisionEngine`) are validated, and a lenient match maps near-misses such as case or whitespace differences.

## JNI interface (for native hosts)

```kotlin
object OamJni {
    @JvmStatic fun create(context: Context, nativeHandle: Long): Long   // returns a bridge id
    @JvmStatic fun send(bridgeId: Long, jsonLine: String)               // non-blocking
    @JvmStatic fun destroy(bridgeId: Long)
    @JvmStatic external fun nativeDeliver(nativeHandle: Long, jsonLine: String)  // implemented by the host .so
}
```

- The host (Rust) passes an opaque `nativeHandle`. Every outgoing JSON-RPC message (responses, `session/event`, `tool/call`, …) is delivered by calling `nativeDeliver` on a background thread.
- The messages are exactly the open-apple-models protocol v1.0, so a host implements the protocol once and injects the Apple backend (`oam_bridge_*` C ABI) or the Android backend (`OamJni`) per platform.
- The host's `.so` must export `Java_com_spacecorps_oam_jni_OamJni_nativeDeliver`.
