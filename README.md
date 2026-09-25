# open-android-models

[![CI](https://github.com/SpaceCorps/open-android-models/actions/workflows/ci.yml/badge.svg)](https://github.com/SpaceCorps/open-android-models/actions/workflows/ci.yml)
![Status: pre-release](https://img.shields.io/badge/status-pre--release-orange)
![Android minSdk 26](https://img.shields.io/badge/Android-minSdk%2026-green)
![Kotlin 2.4](https://img.shields.io/badge/Kotlin-2.4-purple)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)

**Agentic tool calling, NPC dialogue and AI decisions on Android's built-in on-device model.** Gemini Nano runs inside Android's AICore system service. Apps reach it through the ML Kit GenAI Prompt API, which has no tool calling, no roles and no chat session. This library adds those on top: runtime JSON Schema tools that run in your app or in your game engine, a controlled tool loop, structured output, and a game layer with NPCs, world state, decisions and content generation. There are no API keys or server costs: inference runs on the device.

It is the Android sibling of [open-apple-models](https://github.com/SpaceCorps/open-apple-models), which does the same on Apple's Foundation Models. The two share concepts, type names and a JSON-RPC 2.0 protocol (v1.0). A game engine implements the protocol once and injects the Apple backend (C ABI) or the Android backend (JNI) per platform.

> [!WARNING]
> **Pre-release (0.1.0-SNAPSHOT). Not yet run on a Gemini Nano device.** Everything is tested on the JVM with a scripted model. The prompt loop was also tuned and measured live against a *proxy*: Apple's ~3B on-device model served by open-apple-models' `oam serve`. Nothing has run on AICore hardware yet, because the Android emulator cannot run Gemini Nano and no supported device was available. Nothing is published to a remote Maven repository yet (build from source, see [Install](#install)), and APIs may change.

> [!IMPORTANT]
> **Read the [ML Kit GenAI terms](https://developers.google.com/ml-kit/genai-terms) before you ship anything built on this.** Google's terms for these APIs prohibit using them in services directed at, or likely to be accessed by, people under 18. That rules out many games. They also require developers to be 18 or older, restrict use to the features described in the ML Kit GenAI documentation, forbid preview models in production, and let Google enforce rate limits. This library is MIT-licensed, but the model it calls is governed by Google's terms. This summary is not legal advice.

## Contents

- [What it does](#what-it-does)
- [Requirements and supported devices](#requirements-and-supported-devices)
- [How tool calling works without native support](#how-tool-calling-works-without-native-support)
- [Modules](#modules)
- [Install](#install)
- [Quick start](#quick-start)
- [The sample app](#the-sample-app)
- [Native hosts and game engines](#native-hosts-and-game-engines)
- [Errors](#errors)
- [Testing](#testing)
- [Limitations](#limitations)
- [Relation to open-apple-models](#relation-to-open-apple-models)
- [Contributing](#contributing)
- [License](#license)

## What it does

- **Tool calling on a model without it.** Each tool decision is a model step that answers with a small JSON *step envelope*. The envelope is parsed leniently, the arguments are validated against the tool's JSON Schema, and invalid output gets one repair attempt. Tool choices mirror open-apple-models: `Auto`, `None`, `Required`, `Explicit` and a named `Tool`, plus per-turn round and call budgets.
- **Runtime tools.** A tool is a name, a description and a JSON Schema. It either runs a Kotlin `suspend` handler in your app, or it is **external**: the call is handed to your host (a game engine, a script, a UI) and the turn waits for the result.
- **Structured output** from a runtime JSON Schema: coerced, validated, repaired once and returned with keys in schema order.
- **Game AI.** `NPC` dialogue with memory, relationships, world-state tools, streaming and fallback lines; `DecisionEngine` for enum-constrained choices; `ContentGenerator` for schema-shaped items and quests.
- **A native bridge.** `OamJni` runs the JSON-RPC protocol shared with open-apple-models, for engines written in Rust, C or C++.
- **Development without a device.** `ScriptedLanguageModel` plays deterministic scripts. `OpenAICompatibleModel` runs the same loop on the desktop against any OpenAI-compatible server, such as `oam serve`.

## Requirements and supported devices

Gemini Nano runs only on AICore devices. As of September 2026, ML Kit's device list for the Prompt API shows:

| Model version | Devices |
|---|---|
| nano-v4 (Gemma 4 based, Fast E2B and Full E4B variants) | Pixel 11 series, Galaxy Z Flip8, Galaxy Z Fold8 and Z Fold8 Ultra |
| nano-v3 (Gemma 3n based) | Pixel 9 and Pixel 10 series, Galaxy S26 series, some OPPO, vivo, OnePlus, Honor, Sony and Sharp models |
| nano-v2 | some Xiaomi, POCO, OnePlus and Motorola models, and the Galaxy Z Fold7 |

The list changes. Check [ML Kit GenAI](https://developers.google.com/ml-kit/genai) for the current one.

- **Android:** minSdk 26. Devices with an unlocked bootloader are not supported, and **the emulator cannot run Gemini Nano**. Use the [scripted model](#testing) there.
- **Model download:** on a supported device the model may still need downloading (`ModelAvailability.Downloadable`). `GeminiNanoModel.download()` starts the download, or follows one AICore is already running.
- **Features by version:** system instructions need nano-v3 or later, and thinking mode needs nano-v4. On older models the library puts the system instruction at the top of the prompt instead.
- **Limits:** input must stay under about 4000 tokens (instructions, tools, history and the task together). Output is capped at 4096 tokens.
- **Foreground only:** inference runs only while your app is the top foreground app.
- **Quotas:** AICore enforces a per-app quota and battery quotas. Google does not publish the numbers.
- **Safety filters** are built in and cannot be configured.
- **ML Kit dependency:** `com.google.mlkit:genai-prompt:1.0.0-beta4`, the newest release at the time of writing. It is a beta.

For what your build needs (Kotlin, compileSdk, JDK, AGP), see [Install](#install).

## How tool calling works without native support

ML Kit has no tool-calling API (through 1.0.0-beta4), no roles and no chat object. `Agent` builds all three on top of one call: a prompt in, streamed text out.

```text
turn ──▶ decide step ──▶ {"action": "check_menu", "arguments": {"item": "stew"}}
              │                      │ validate arguments against the tool's schema
              │                      ▼
              │           run the tool (local handler, or external: your host answers)
              │                      │ record the result in the turn
              │◀─────────────────────┘ decide again, until...
              ▼
         {"action": "respond"} or the round/call budget is spent
              │
              ▼
         respond step ──▶ streamed reply (or JSON for a schema turn)
```

- **Decide steps.** The model sees the enabled tools and must answer with one JSON object, the step envelope: `{"action": "<tool name>" | "respond", "arguments": {…}}`. Decide steps run at temperature 0 by default (`AgentConfiguration.decisionTemperature`).
- **Lenient parsing.** The parser strips code fences and prose, takes the first balanced JSON object, accepts common synonyms for both keys (`tool`, `name`, `args`, `parameters`, …) and for `respond` (`reply`, `answer`, …), and matches tool names ignoring case and separators.
- **Validation and one repair.** The tool name must be one of the offered tools. The arguments are coerced and validated against the tool's JSON Schema. If the output cannot be parsed or either check fails, the step is asked again once, quoting the problem. If a call to a known tool is still invalid after that, it is recorded as an error output ("The call was not made: …") so the reply can mention it; the tool never runs with invalid arguments. Output that still names no usable action ends the tool loop, and the model replies.
- **Tool choice.**

  | `ToolChoice` | First step | Later steps |
  |---|---|---|
  | `Auto` | decide between the tools and `respond`. **On Android this behaves exactly like `Explicit`**, because prompt-based tool use needs an explicit decision anyway | decide freely |
  | `Explicit` | decide between the tools and `respond` | decide freely |
  | `Required` | only tools are offered (with a single enabled tool, the model writes its arguments directly) | decide freely |
  | `Tool(name)` | no decision: the model writes only that tool's arguments, and a tool without parameters runs with no model step at all | decide freely |
  | `None` | no tools; reply directly | |

- **Budgets.** `ToolPolicy(maxToolRounds = 4, maxToolCalls = 12)` by default. When a budget is spent, the reply step is forced.
- **One call per decide step.** Several calls in one turn run one after another, each after the previous result.
- **Repeated calls end the round.** An identical successful call repeated within a turn is read as a decision to reply, because small models otherwise repeat lookups. Turn this off with `AgentConfiguration(dedupesToolCalls = false)`.
- **Prompt layout.** The system instruction (your instructions plus the context note) stays byte-identical across steps and turns. `GeminiNanoModel` sends it as ML Kit's system instruction where the device supports one (nano-v3 and later). Otherwise it leads the prompt as a stable prefix, sent as ML Kit's `PromptPrefix` (implicit prefix caching) on devices that report caching support. After it come the conversation and a short task for the step; a decide step's task lists the offered tools. History is rendered as labelled lines (`User:` / `Assistant:` by default, `Player:` and the character's name for an NPC) and trimmed oldest turn first to fit the input budget. Token counts are estimated at about four characters per token; the model's own `countTokens` is used once a prompt passes 60% of the input budget.
- **Native tools later.** Google has said tool calling will come to the Prompt API, but it has not shipped. The seam is `ModelCapabilities.nativeToolCalling`, fed by `NanoFeatures.nativeToolCalling`. Both are always `false` today, and `Agent` always runs the envelope loop. The plan is for `GeminiNanoModel` to hand tools to ML Kit once it can, without changing the public API.

### Proxy measurements

These numbers come from the opt-in live evals in this repository (see [Testing](#opt-in-live-checks)). **They are proxy results: the model was Apple's ~3B on-device model served by `oam serve` from open-apple-models, not Gemini Nano.** Apple's guardrails are also not Gemini Nano's safety filters: on the proxy, weapon talk was blocked often. Treat the numbers as evidence that the loop works on a small on-device model, not as Gemini Nano performance.

| Scenario (oam-core eval) | Result |
|---|---|
| Innkeeper, right action per player line (3 runs × 9 lines) | 27/27; 45/45 step envelopes valid on the first try |
| Same lines plus 4 more, fresh context each | 13/13 |
| Forced tool arguments (`Tool(name)` and `Required`) | 6/6 |
| Gate guard with 5 tools | 8/10 (both misses were judgment calls); 11/11 envelopes valid |

The oam-game eval's final run (2 runs of each scenario) passed 32 of 40 checks:

| Scenario (oam-game eval) | Result |
|---|---|
| NPC innkeeper, right first action | 10/10 |
| Structured NPC replies the proxy's guardrails let through | 16/16 valid on the first try, no repairs |
| Weapon-shop NPC | the proxy blocked 6/6 structured replies; the text retry answered 4 with correct facts, 2 got fallback lines |
| Decisions | "cornered goblin" chose `flee` 2/2; "sleeping knight" chose `beg` where `attack` was expected, 2/2 (a judgment miss) |

| Scenario (oam-bridge eval) | Result |
|---|---|
| Guard turns (tool for "open the north gate", none for small talk) | 6/6 |
| NPC turns with a grounding tool, correct facts | 6/6 |

| Latency on the proxy | Time |
|---|---|
| decide step | about 0.75 s |
| agent turn (oam-core eval) | about 2.0 s, p90 2.6 s |
| NPC turn (oam-game eval) | 2.4 s on average, p90 3.4 s |
| bridge: session turn with one client tool | 1.8–3.5 s, up to 5.6 s when the decision needed a repair |
| bridge: `npc/bark` / `decision/decide` / `content/generate` | 0.6–1.4 s / 1.3–2.1 s / 0.7–1.5 s |

## Modules

Gradle modules, package root `com.spacecorps.oam`:

| Module | Kind | What it is |
|---|---|---|
| [`oam-core`](oam-core) | Kotlin/JVM library, no Android dependency | `Agent`, `AgentTool`, `ToolPolicy`/`ToolChoice`, `AgentEvent`, `AgentError`, the JSON Schema subset and validator, the prompt-envelope tool loop, structured output, `Transcript`. Also `ScriptedLanguageModel` (`com.spacecorps.oam.testing`) and `OpenAICompatibleModel` (`com.spacecorps.oam.openai`) |
| [`oam-game`](oam-game) | Kotlin/JVM library | `NPC`, `Persona`, `Emotion`, `NPCOptions`, `NPCMemory`, `DialogueTurn`, `WorldState`, `DecisionEngine`, `ContentGenerator` (`com.spacecorps.oam.game`). Depends on `oam-core` |
| [`oam-bridge`](oam-bridge) | Kotlin/JVM library | `BridgeEngine`: the JSON-RPC 2.0 protocol v1.0 shared with open-apple-models (`com.spacecorps.oam.bridge`). Depends on `oam-core` and `oam-game` |
| [`oam-mlkit`](oam-mlkit) | Android library (AAR) | `GeminiNanoModel`, `GeminiNanoOptions`, `AvailabilityMonitor`, `GeminiNanoErrors` (`com.spacecorps.oam.mlkit`); `OamJni` and `BridgeEngineFactory` for native hosts (`com.spacecorps.oam.jni`). Depends on `oam-core` and `oam-bridge`, and so brings in everything |
| [`sample`](sample) | Android app | Mira's Tavern: an innkeeper on Gemini Nano, with a scripted fallback |

Only `oam-mlkit` and `sample` need Android. The other three are plain JVM code, fully unit-tested on the desktop.

## Install

Nothing is published to a remote repository yet: not Maven Central, not JitPack. Build from source in one of two ways. Both use the coordinates `com.spacecorps.oam:<module>:0.1.0-SNAPSHOT` (group and version come from [`gradle.properties`](gradle.properties)).

**Composite build.** Clone this repository next to your project and include it. Gradle then substitutes its modules for those coordinates.

```sh
git clone https://github.com/SpaceCorps/open-android-models
```

```kotlin
// settings.gradle.kts of your project
includeBuild("../open-android-models")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.spacecorps.oam:oam-mlkit:0.1.0-SNAPSHOT")  // Gemini Nano, plus core, game and bridge
}

// or, in a plain JVM module (tools, servers, tests):
dependencies {
    implementation("com.spacecorps.oam:oam-game:0.1.0-SNAPSHOT")   // plus oam-core
}
```

**Local Maven.** In this repository, `./gradlew publishToMavenLocal` publishes the four libraries to `~/.m2`: JARs for `oam-core`, `oam-game` and `oam-bridge`, the AAR for `oam-mlkit`, each with a sources JAR. Add `mavenLocal()` to your repositories and use the same dependencies as above.

Your build needs:

- **Kotlin 2.3 or newer**, because the classes carry Kotlin 2.4 metadata. AGP 9's built-in Kotlin defaults to KGP 2.2, so put a newer plugin on the build classpath in your root `build.gradle.kts`: `id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false`.
- **Android:** `minSdk` 26 or higher. `oam-mlkit` does not force this repository's compileSdk (37.1) on you; ML Kit's AndroidX dependencies ask for compileSdk 33.
- **JVM modules:** Java 21.
- **Composite builds with Android:** the same AGP version as this repository (9.4.1), because AGP refuses two versions in one build. Gradle also builds this repository with a Java 21 toolchain.

Both ways were checked with a throwaway consumer (Gradle 9.7.1, AGP 9.4.1, Kotlin 2.4.20) when publishing was added (commits `0905844` and `df59290`): an Android app on `oam-mlkit` with compileSdk 36 and 37.1, and a JVM module on `oam-game` and `oam-bridge`.

`oam-mlkit`'s R8 keep rules for `OamJni` ship in the AAR ([`oam-mlkit/consumer-rules.pro`](oam-mlkit/consumer-rules.pro)).

## Quick start

**No device?** Everything except Gemini Nano itself runs on the desktop. With JDK 21 and the Android SDK set up (see [Contributing](#contributing)), `./gradlew :sample:testDebugUnitTest` plays Mira's scripted evening through the real agent and tools, and `ScriptedLanguageModel` ([Testing](#testing)) lets you script any of the examples below.

In the examples, `model` is any `LanguageModel`: `GeminiNanoModel()` on a device, a `ScriptedLanguageModel` in tests, or `OpenAICompatibleModel("http://127.0.0.1:19997/v1", model = "system")` against `oam serve` on a Mac.

The Kotlin examples from here on are part of the build: `ReadmeExamplesTest` in `oam-game` runs the JVM ones against scripted models, and oam-mlkit's unit tests compile the Android ones, which need a device to run. The build fails if an example here and its tested copy differ, and the `OamJni` outline is checked against `OamJni.kt`.

### An agent with tools (oam-core)

```kotlin
import com.spacecorps.oam.*

val inventory = AgentTool.local(
    name = "check_inventory",
    description = "Look up how many of an item the blacksmith has and its price in gold.",
    parameters = JsonSchema.obj("item" to JsonSchema.string(description = "Item name")),
) { call ->
    val item = call.string("item")
    ToolOutput.of(mapOf("item" to item, "stock" to 3, "price_gold" to 45))
}

val gorm = Agent(
    model = model,
    instructions = "You are Gorm, a grumpy blacksmith in a fantasy game. Reply in at most two sentences.",
    tools = listOf(inventory),
)

// Ground the answer: the first step must call a tool, then the model replies.
val reply = gorm.respond("Got any iron swords? How much?", ToolPolicy(choice = ToolChoice.Required))
println(reply.text)
println(reply.toolCalls.map { it.call.name })  // [check_inventory]
```

`JsonSchema.obj(...)` builds a closed object schema with every property required. `JsonSchema.parse(text)` takes a schema as JSON text instead. Agents are `AutoCloseable`; turns on one agent run in order.

### External tools: let your game act

```kotlin
val openGate = AgentTool.external(
    name = "open_gate",
    description = "Ask the game to open a named gate. Returns whether it opened.",
    parameters = JsonSchema.obj("gate" to JsonSchema.string()),
)
val guard = Agent(model, instructions = "You are a castle guard. Use tools to act.", tools = listOf(openGate))

val run = guard.run("Please open the north gate.")
run.events.collect { event ->
    when (event) {
        is AgentEvent.ToolCallRequested -> {
            val opened = game.openGate(event.call.string("gate"))  // your game code
            run.submit(ToolOutput.of(mapOf("opened" to opened)), event.call.id)
        }
        is AgentEvent.Text -> print(event.delta)  // stream the reply
        is AgentEvent.Completed -> println("\n${event.response.toolCalls.size} tool call(s)")
        else -> Unit
    }
}
```

`agent.respond(prompt, externalTools = { call -> … })` does the same without collecting events.

**Cancelling a turn.** `run.cancel()`, or cancelling the coroutine that collects `run.events` or awaits `respond`, cancels the turn. Its pending external calls get an error output, and the turn fails with `AgentErrorCode.CANCELLED` and leaves no trace in the history. Adding a finished turn to the history is its point of no return: a cancel that arrives after that has no effect, and the turn completes normally. So a turn is either in the history or cancelled, never both. A collector that only stops early, for example with `first()`, does not cancel the turn.

### Structured output

```kotlin
val decision = gorm.respond(
    "A customer offers 30 gold for an iron sword. Check stock, then decide.",
    schema = JsonSchema.obj(
        "reasoning" to JsonSchema.string(description = "One short sentence"),
        "choice" to JsonSchema.string(enum = listOf("sell", "refuse", "haggle")),
    ),
    policy = ToolPolicy(choice = ToolChoice.Tool("check_inventory")),
)
println(decision.structured)  // {"reasoning":"…","choice":"haggle"}: validated, keys in schema order
```

Output that is still invalid after one repair fails the turn with `AgentErrorCode.GENERATION_FAILED`. Apple's constrained decoding cannot fail this way; on Android it can, so plan a fallback.

### NPCs, decisions and content (oam-game)

```kotlin
import com.spacecorps.oam.game.*

val world = WorldState.of(
    "player" to mapOf("name" to "Aria", "gold" to 60),
    "time_of_day" to "evening",
)

val blacksmith = NPC(
    persona = Persona(
        name = "Gorm",
        role = "the village blacksmith",
        personality = "Gruff and proud, but fair.",
        speakingStyle = "Short, blunt sentences. Calls people 'lad'.",
    ),
    model = model,
    tools = listOf(inventory),
    world = world,                              // adds read_world_state
    options = NPCOptions(
        groundingTool = "check_inventory",      // look up stock before every reply
        worldContextPaths = listOf("player.name", "player.gold", "time_of_day"),
    ),
)

val turn = blacksmith.talk("Evening! Got any iron swords? How much?")
println("${turn.emotion}: ${turn.line}")  // an Emotion (such as PROUD) and the spoken line
println(turn.playerOptions)               // suggested replies
println(turn.isFallback)                  // true if a canned line replaced an unusable reply

// Typewriter streaming
blacksmith.talkStream("Can I afford a shield too?").events.collect { event ->
    when (event) {
        is DialogueEvent.LineDelta -> dialogueBox.append(event.text)
        is DialogueEvent.LineReset -> dialogueBox.replace(event.text)
        else -> Unit
    }
}

// Enemy AI: always one of your option ids
val choice = DecisionEngine(model).decide(
    situation = "The player, at full health, charges Snik with a flaming sword.",
    options = listOf(
        DecisionOption("flee", "Run into the tunnels"),
        DecisionOption("attack", "Stab with the rusty dagger"),
        DecisionOption("beg", "Beg for mercy and offer loot"),
    ),
    actor = Persona(name = "Snik", role = "a timid, greedy goblin"),
    fallbackOptionId = "flee",  // used if the answer is blocked or stays invalid
)
println(choice.optionId)

// Schema-shaped content
val ring = ContentGenerator(model).generate(
    prompt = "A cursed ring for a level 3 player.",
    schema = JsonSchema.obj(
        "name" to JsonSchema.string(),
        "curse" to JsonSchema.string(description = "One sentence"),
        "value_gold" to JsonSchema.integer(minimum = 1, maximum = 500),
    ),
)
```

By default (`NPCReplyFormat.AUTOMATIC`) an NPC turn asks for a structured reply (emotion, line, player options, whether the conversation ends). If that output stays invalid, is blocked by the safety filters or is refused, the turn is retried once as an emotion-tagged text line, with tools off. Only then does a fallback line appear. NPCs also have memory tools (`NPCOptions(memoryTools = NPCMemoryTool.ALL)`), secrets that unlock with the relationship score, background history compaction, `bark(situation)` for one-liners, and save/restore through `saveState()` and `NPC.restore(...)`.

Keep personas short and tool lists small: Gemini Nano has about 4000 input tokens for everything, and every decide step carries the tool list.

### Gemini Nano in an Android app (oam-mlkit)

In a coroutine, for example one launched in a view model's scope:

```kotlin
import com.spacecorps.oam.*
import com.spacecorps.oam.mlkit.*

val nano = GeminiNanoModel()  // or GeminiNanoModel(GeminiNanoOptions(preference = GeminiNanoOptions.Preference.FAST))

suspend fun prepare(): Boolean = when (val availability = nano.availability()) {
    ModelAvailability.Available -> true
    ModelAvailability.Downloadable, is ModelAvailability.Downloading -> {
        nano.download().collect { event ->
            if (event is DownloadEvent.Progress) showProgress(event.fraction)  // null when the size is unknown
        }
        true
    }
    is ModelAvailability.Unavailable -> {
        showMessage("Gemini Nano is not available: ${availability.reason}")  // e.g. device_not_eligible
        false
    }
}
if (!prepare()) return

// Then use it like any LanguageModel, while the app is in the foreground.
val mira = Agent(
    nano,
    instructions = "You are Mira, the innkeeper of the Sleeping Stag. Reply in at most two sentences.",
    tools = listOf(menuTool),
)
try {
    println(mira.respond("Evening! What have you got that's warm?").text)
} catch (error: AgentError) {
    when (error.code) {
        AgentErrorCode.RATE_LIMITED -> showMessage("Busy, over quota or in the background. Retry later.")
        AgentErrorCode.GUARDRAIL_VIOLATION -> showMessage("Blocked by the safety filters.")
        else -> showMessage("${error.code.wireName}: ${error.message} (ML Kit code ${error.mlKitErrorCode})")
    }
}
```

- `download()` throws `AgentError` with `MODEL_UNAVAILABLE` when the device cannot run Gemini Nano or the download fails.
- `AvailabilityMonitor(nano, viewModelScope).state` is a `StateFlow<ModelAvailability?>` for status UIs. It polls and follows downloads.
- `nano.detectedFeatures` reports what the device supports (base model name, token limit, system instructions, prefix caching, thinking), and `nano.capabilities` updates from it.
- `GeminiNanoOptions(thinkingFor = setOf(GenerationKind.DECIDE))` turns on thinking mode for decide steps on nano-v4 devices. It adds latency, so it is off by default.
- `GeminiNanoOptions(releaseStage = GeminiNanoOptions.ReleaseStage.PREVIEW)` selects AICore Developer Preview models, which the terms forbid in production.
- Call `nano.close()` when you are done with it.

## The sample app

[`sample`](sample) is **Mira's Tavern**: Mira the innkeeper, a port of open-apple-models' `oam demo tavern`. She has two tools, `check_menu` (read) and `take_order` (serves the item and takes the player's gold), and uses `Agent` directly with `ToolChoice.Explicit` and at most two tool rounds per turn.

```sh
./gradlew :sample:installDebug   # with a device or emulator connected over adb
```

- **Gemini Nano** (the default) shows the model's availability and offers a download button when the model is downloadable.
- **Scripted** switches to a keyword-driven stand-in (`ScriptedMira`) that runs through the same agent, tools and prompt loop. Use it on the emulator or on any phone without Gemini Nano.

`./gradlew :sample:testDebugUnitTest` plays the scripted evening through the real agent and tools, with no device needed.

The APK builds and the scripted test passes, but the sample has not yet been installed on a device or an emulator.

## Native hosts and game engines

`OamJni` (in `oam-mlkit`) is the JNI entry point for engines written in Rust, C or C++. It speaks the **same JSON-RPC 2.0 protocol, version 1.0, as open-apple-models**: the same methods (`session/*`, `tool/call`, `npc/*`, `decision/*`, `world/*`, `content/generate`, scripted models), the same parameters, events and error codes. The protocol is the dependency-injection seam: an engine implements it once and injects the Apple backend (the `oam_bridge_*` C ABI) or this one per platform. The libraries are designed so that an engine such as SpaceCorps' Space3d can work this way.

In outline (see [`OamJni.kt`](oam-mlkit/src/main/kotlin/com/spacecorps/oam/jni/OamJni.kt)):

```kotlin
object OamJni {
    @JvmStatic var engineFactory: MessageEngineFactory                  // defaults to BridgeEngineFactory()
    @JvmStatic fun create(context: Context, nativeHandle: Long): Long   // bridge id, 0 on failure
    @JvmStatic fun send(bridgeId: Long, jsonLine: String)               // one message; never blocks
    @JvmStatic fun destroy(bridgeId: Long)
    @JvmStatic external fun nativeDeliver(nativeHandle: Long, jsonLine: String)  // implemented by your .so
}
```

The host passes an opaque `nativeHandle`. Every outgoing message (responses, `session/event`, `tool/call`, …) arrives through `nativeDeliver(handle, line)`, which your library implements:

```c
JNIEXPORT void JNICALL
Java_com_spacecorps_oam_jni_OamJni_nativeDeliver(JNIEnv *env, jclass cls, jlong handle, jstring line);
```

Host checklist:

1. **Register `nativeDeliver`.** Export the symbol above, which the JVM finds when your library was loaded with `System.loadLibrary`, or register the method with `RegisterNatives` on the `OamJni` class. Libraries loaded by `NativeActivity` or `GameActivity` are not searched for JNI symbols, so those hosts must use `RegisterNatives`. Doing both covers either case.
2. **Find the class through the activity's class loader** (`com.spacecorps.oam.jni.OamJni`). `FindClass` on a native thread only sees system classes.
3. **Delivery.** `nativeDeliver` runs on an attached JVM background thread, in order, never concurrently. Copy the string and return quickly. Once `destroy` returns, `nativeDeliver` is never called again for that handle. Do not call `destroy` while holding a lock that your `nativeDeliver` takes.
4. **Text encoding.** JNI uses modified UTF-8. Outgoing lines escape characters outside the Basic Multilingual Plane (emoji) as `\uXXXX\uXXXX`, so every delivered line is valid UTF-8. For incoming text, create the `jstring` with `NewString` (UTF-16) or the Rust `jni` crate's `new_string`; do not pass raw UTF-8 containing emoji to `NewStringUTF`.
5. **Build the app with Gradle**, with your `.so` under `jniLibs`, so the ML Kit AAR and its manifest entries (the AICore bind permission and package query) are merged. Packaging tools that bypass Gradle cannot do this.
6. **Foreground only.** In the background, turns fail with `-32005 rate_limited`.

To change the model or limits, replace the factory before the first `create`, for example in `Application.onCreate`:

```kotlin
OamJni.engineFactory = BridgeEngineFactory(
    systemModel = { GeminiNanoModel(GeminiNanoOptions(preference = GeminiNanoOptions.Preference.FAST)) },
    configure = { model -> BridgeConfiguration(systemModel = model, maxSessions = 16) },
)
```

Kotlin hosts can skip JNI and run the engine in process:

```kotlin
val engine = BridgeEngine(BridgeConfiguration(systemModel = GeminiNanoModel())) { line -> println(line) }
engine.receive("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
```

`initialize` reports `server.name` `"open-android-models"` and `protocolVersion` `"1.0"`. There is no stdio server and no C ABI on Android.

**Where Android differs on the wire:** turns take more model steps (each step carries `kind` and `isRepair`), a turn makes at most one tool call per step, `"auto"` behaves like `"explicit"`, undeclared argument keys are dropped, an unusable structured answer can fail with `-32012 generation_failed`, transcripts cannot move between platforms, and availability adds `status`, `detail` and download progress. [docs/PROTOCOL.md](docs/PROTOCOL.md) lists every difference, and open-apple-models' [PROTOCOL.md](https://github.com/SpaceCorps/open-apple-models/blob/main/docs/PROTOCOL.md) is the full reference.

**Cancellation.** `session/cancel` and `npc/cancel` answer each cancelled request with `-32009 cancelled` once its turn has rolled back, and outstanding `tool/call`s get `tool/cancel`. A turn that was already in the history when the cancel reached it gets its normal result instead, so every response matches the history.

**Conformance.** `AppleConformanceTest` runs the same scripted JSON-RPC scenarios through Apple's `oam stdio` and through `BridgeEngine` and compares the exchanges after normalizing the documented differences. At commit `2911da4`, against open-apple-models' `oam` 0.1.0, it matched 90 of 90 exchanges in 6 scenarios. `JniProbeTest` drives the bridge through a real native library (`oam-mlkit/src/test/native/probe_host.c`) loaded into a desktop JVM; it passed at the same commit, on macOS. The JNI path has not yet run inside an Android app, so the checklist above comes from the JNI rules and `OamJni`'s design, not from a shipped host.

## Errors

Every failure is an `AgentError` with an `AgentErrorCode`, the same twelve codes as open-apple-models (`wireName` is the string on the JSON-RPC wire). `GeminiNanoErrors.toAgentError` maps ML Kit's `GenAiException.ErrorCode`:

| ML Kit error (code) | `AgentErrorCode` | Bridge error |
|---|---|---|
| `BUSY` (9): per-app inference quota | `RATE_LIMITED` | `-32005 rate_limited` |
| `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` (27), per-device battery quota (28) | `RATE_LIMITED` | `-32005 rate_limited` |
| `BACKGROUND_USE_BLOCKED` (30): app not in the foreground | `RATE_LIMITED` | `-32005 rate_limited` |
| `REQUEST_PROCESSING_ERROR` (4), `RESPONSE_PROCESSING_ERROR` (11), `RESPONSE_GENERATION_ERROR` (15): safety filters | `GUARDRAIL_VIOLATION` | `-32002 guardrail_violation` |
| `REQUEST_TOO_LARGE` (12) | `CONTEXT_SIZE_EXCEEDED` | `-32004 context_size_exceeded` |
| `NOT_AVAILABLE` (8), `NOT_SUPPORTED` (16), `NOT_ENOUGH_DISK_SPACE` (501), `NEEDS_SYSTEM_UPDATE` (604), `AICORE_INCOMPATIBLE` (-101) | `MODEL_UNAVAILABLE` | `-32001 model_unavailable` |
| `CANCELLED` (7) | `CANCELLED` | `-32009 cancelled` |
| `REQUEST_TOO_SMALL` (-100), `INVALID_INPUT_IMAGE` (-102) | `INVALID_REQUEST` | `-32011 invalid_request` |
| `STRUCTURED_OUTPUT_REQUEST_ERROR` (-104) | `INVALID_SCHEMA` | `-32007 invalid_schema` |
| `UNKNOWN` (0), `CACHE_PROCESSING_ERROR` (-103), anything else | `GENERATION_FAILED` | `-32012 generation_failed` |

- **`BUSY` maps to `RATE_LIMITED`, not `BUSY`.** Quotas and background use all become `RATE_LIMITED`, as Apple's rate limit does. ML Kit's retry delay, when it gives one, becomes `AgentError.retryAfter`.
- **Retries.** `RetryPolicy` retries a single model step, never a whole turn, so a tool never runs twice. By default it makes 3 attempts starting at 300 ms and doubling. It retries `GENERATION_FAILED`, `BUSY` and `RATE_LIMITED` (unless ML Kit asks to wait longer than 5 s). It retries guardrail violations only with `retriesGuardrailViolations = true`.
- **The raw code.** `error.mlKitErrorCode` returns ML Kit's code when the error came from ML Kit.
- **Unavailable reasons.** `ModelAvailability.Unavailable.reason` is one of `device_not_eligible`, `aicore_unavailable`, `needs_system_update`, `not_enough_disk_space`, `model_not_ready` or `unknown` (`GeminiNanoErrors.Reasons`).

## Testing

```sh
./gradlew build                          # everything: JVM tests, Android unit tests, lint, APKs
./gradlew :oam-core:test :oam-game:test :oam-bridge:test
./gradlew :oam-mlkit:testDebugUnitTest :sample:testDebugUnitTest
```

At commit `92325c6`, `./gradlew build` runs 408 JVM tests: oam-core 130, oam-game 112, oam-bridge 92, oam-mlkit 71 and sample 3 (the Android modules' debug unit tests). Nine of them are the opt-in live checks below, skipped by default. Six are `ReadmeExamplesTest`, which runs this README's examples and checks them against the README. `oam-mlkit` is tested through a fake of its internal ML Kit client, so its tests run without a device. The GitHub Actions workflow ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs `./gradlew build` and `publishToMavenLocal` on Ubuntu with JDK 21, on pushes and pull requests to `main`; the opt-in checks skip there.

For your own tests, `ScriptedLanguageModel` plays a script, one entry per model request:

```kotlin
import com.spacecorps.oam.testing.ScriptedLanguageModel
import com.spacecorps.oam.testing.ScriptedLanguageModel.Step

val model = ScriptedLanguageModel(
    Step.call("check_inventory", "item" to "iron sword"),  // decide: call the tool
    Step.respond(),                                        // decide: reply
    Step.Text("Three swords, 45 gold each."),              // the reply
)
val agent = Agent(model, tools = listOf(inventory))
val response = agent.respond("Swords?")
check(response.toolCalls.single().call.name == "check_inventory")
check(model.requests.map { it.kind } == listOf(GenerationKind.DECIDE, GenerationKind.DECIDE, GenerationKind.RESPOND))
```

`ScriptedLanguageModel(steps, style = ScriptedLanguageModel.ScriptStyle.NATIVE)` instead plays open-apple-models' scripts unchanged: a `ToolCalls` step answers the decide steps and an answer step becomes the reply. The bridge's `{"type": "scripted"}` models use this style, so one script plays the same way on both platforms.

### Opt-in live checks

| Environment variables | Test | What it does |
|---|---|---|
| `OAM_PROXY_EVAL=1`, optional `OAM_PROXY_URL`, `OAM_PROXY_MODEL` (default `system`), `OAM_PROXY_EVAL_RUNS` (1–10) | `ProxyEvalTest` (oam-core), `GameProxyEvalTest` (oam-game), `ProxyBridgeEvalTest` (oam-bridge) | Runs agent, NPC, decision, content and bridge scenarios against a live OpenAI-compatible server. `OAM_PROXY_EVAL_ONLY` picks oam-game scenarios by name; its report goes to `oam-game/build/game-proxy-eval.txt` |
| `OAM_APPLE_BRIDGE=/path/to/oam` | `AppleConformanceTest` (oam-bridge) | Compares the bridge with open-apple-models' `oam stdio`, exchange by exchange |
| `OAM_JNI_PROBE_LIB`, optional `OAM_JNI_PROBE_OUT` | `JniProbeTest` (oam-mlkit) | Drives `OamJni` through a real native library |

The proxy evals default to `http://127.0.0.1:19997/v1` (oam-core and oam-game) and `http://127.0.0.1:19996/v1` (oam-bridge); set `OAM_PROXY_URL` to use one server for all three. To run them against Apple's model as a stand-in, on a Mac that runs open-apple-models:

```sh
# in a checkout of open-apple-models
swift build -c release --product oam
.build/release/oam serve --port 19997

# in this repository
OAM_PROXY_EVAL=1 OAM_PROXY_EVAL_RUNS=3 ./gradlew :oam-core:test --tests '*ProxyEvalTest*'
OAM_PROXY_EVAL=1 OAM_PROXY_URL=http://127.0.0.1:19997/v1 ./gradlew :oam-bridge:test --tests '*ProxyBridgeEvalTest*'
OAM_APPLE_BRIDGE=/path/to/open-apple-models/.build/release/oam ./gradlew :oam-bridge:test --tests '*AppleConformanceTest*'
```

The JNI probe needs the host library built for your JVM's platform (macOS shown):

```sh
clang -shared -fPIC -I$JAVA_HOME/include -I$JAVA_HOME/include/darwin \
  -o /tmp/libprobehost.dylib oam-mlkit/src/test/native/probe_host.c
OAM_JNI_PROBE_LIB=/tmp/libprobehost.dylib OAM_JNI_PROBE_OUT=/tmp/probe.txt \
  ./gradlew :oam-mlkit:testDebugUnitTest --tests '*JniProbeTest*'
```

There is no on-device test suite yet.

## Limitations

- **Never run on Gemini Nano.** Accuracy, latency, the safety filters' behavior and some ML Kit details are unmeasured on real hardware: whether the finish reason only arrives on the last streamed chunk, and whether `getTokenLimit()` is the input limit or the whole context.
- **No native tool calling.** Every tool decision costs a model step (about 0.75 s on the proxy), a turn makes one call per step, and parallel calls run one after another.
- **Small context.** About 4000 input tokens for instructions, tools, history and the task. History is trimmed and can be compacted, but personas and tool lists must stay short.
- **Structured output can fail.** There is no constrained decoding for runtime schemas, so output can still be invalid after the repair (`GENERATION_FAILED`). `NPC` and `DecisionEngine` fall back; your own schema turns should too.
- **Platform rules.** Foreground only, unpublished quotas, non-configurable safety filters, a narrow device list, and a beta ML Kit API.
- **Token usage is estimated** (about four characters per token), because ML Kit reports no usage.
- **Transcripts are platform-specific.** Saved sessions and NPC transcripts cannot move between Android and Apple.
- **`OpenAICompatibleModel` is for desktop development.** It uses `java.net.http`, which Android does not ship, and is not meant for production.
- **ML Kit features not used yet:** explicit prefix caches and ML Kit's own structured output, which only accepts classes compiled with KSP.
- **Not on a remote Maven repository.** Build from source; see [Install](#install).

## Relation to open-apple-models

The two libraries share their design, their type names where the platforms allow, and the JSON-RPC protocol. The difference is underneath: Apple's framework has native, schema-constrained tool calls, which open-apple-models steers step by step; Gemini Nano has plain text generation, so this library runs the whole tool loop itself.

| open-apple-models (Swift) | open-android-models (Kotlin) |
|---|---|
| FoundationModels `SystemLanguageModel` | `GeminiNanoModel` (oam-mlkit) over ML Kit's `GenerativeModel` |
| `SteeredLanguageModel` (per-step steering of Apple's native tool loop) | no public equivalent: `Agent` runs its own prompt-envelope loop |
| `Agent`, `AgentRun`, `AgentEvent`, `AgentResponse`, `AgentError` | same names |
| `AgentTool` (local closure or `.external`) | `AgentTool.local(...)`, `AgentTool.external(...)`, `AgentTool.typed<A>(...)` |
| `ToolPolicy`, `ToolChoice` (`.auto`, `.none`, `.required`, `.explicit`, `.tool(name)`) | `ToolPolicy`, `ToolChoice` (`Auto`, `None`, `Required`, `Explicit`, `Tool(name)`); `Auto` behaves like `Explicit` |
| `JSONSchema` converted to a `GenerationSchema` (constrained decoding) | `JsonSchema` (validated, coerced and repaired after generation) |
| `JSONValue` | kotlinx.serialization `JsonElement` |
| `ScriptedLanguageModel` + `ModelScript` (OpenAppleModelsTesting) | `ScriptedLanguageModel` (`com.spacecorps.oam.testing`), `ENVELOPE` or `NATIVE` style |
| OpenAppleModelsGame: `NPC`, `Persona`, `NPCOptions`, `NPCMemory`, `DialogueTurn`, `DialogueStream`, `WorldState`, `DecisionEngine`, `ContentGenerator` | oam-game: same names |
| `NPCMemoryTools` | `NPCMemoryTool` (enum) |
| OpenAppleModelsBridge `BridgeEngine`, `BridgeExtension` | oam-bridge `BridgeEngine`, `BridgeExtension` |
| `oam_bridge_create` / `oam_bridge_send` / `oam_bridge_destroy` (libOpenAppleModelsFFI) | `OamJni.create` / `send` / `destroy`, plus your `nativeDeliver` |
| `oam stdio`, `oam serve`, the `oam` CLI | none on Android. `OpenAICompatibleModel` is a *client* for servers such as `oam serve` |
| 8192-token context shared by input and output | about 4000 input tokens, output capped separately at 4096 |

## Contributing

You need:

- **JDK 21** for the Java toolchain. If Gradle runs on another JDK, the foojay toolchain resolver can download 21.
- **The Android SDK** with the `android-37.1` platform. Point `ANDROID_HOME` at it, or set `sdk.dir` in `local.properties`.

```sh
export ANDROID_HOME=/path/to/android/sdk
./gradlew build
```

`./gradlew build` compiles every module, runs the JVM tests, runs Android lint and assembles the sample. The library modules use Kotlin's explicit API mode and treat compiler warnings as errors, and `oam-mlkit`'s lint treats warnings as errors too.

- Keep behavior in step with open-apple-models where the platforms allow. Protocol changes must keep `AppleConformanceTest` passing.
- Prompts were tuned against a live small model. If you change prompt wording, run the proxy evals before and after.
- The README's Kotlin examples have tested copies in [`ReadmeExamplesTest.kt`](oam-game/src/test/kotlin/com/spacecorps/oam/readme/ReadmeExamplesTest.kt) (run against scripted models) and [`ReadmeAndroidExamples.kt`](oam-mlkit/src/test/kotlin/com/spacecorps/oam/readme/ReadmeAndroidExamples.kt) (compiled only). Change both together: the build fails when they differ.
- Commit messages start with the module or area: `oam-core: …`, `oam-mlkit: …`, `build: …`, `docs: …`.
- Design notes are in [docs/DESIGN.md](docs/DESIGN.md), the Android protocol differences in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## License

MIT, © 2026 SpaceCorps Technology OÜ. See [LICENSE](LICENSE).

Not affiliated with Google or Apple. Android, Gemini, Gemini Nano, AICore and ML Kit are Google's names for its products. Use of Gemini Nano through ML Kit is subject to the [ML Kit GenAI terms](https://developers.google.com/ml-kit/genai-terms).
