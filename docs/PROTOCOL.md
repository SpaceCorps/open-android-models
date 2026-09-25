# Bridge protocol (v1.0) on Android

open-android-models speaks **the same JSON-RPC 2.0 bridge protocol as open-apple-models**, version 1.0. The
reference is open-apple-models' [`docs/PROTOCOL.md`](https://github.com/SpaceCorps/open-apple-models/blob/main/docs/PROTOCOL.md).
It covers framing, ordering guarantees, every method, notification and parameter, the error table, tool definitions,
JSON Schema support and scripted models. This page lists only what differs on Android.

A game engine implements the protocol once. On Apple platforms it loads the Apple backend (`oam_bridge_*` C ABI or
`oam stdio`). On Android it loads this one (`OamJni`, below). The method names, parameters, result fields, event types,
`tool/call` round trips, error codes (`data.code` strings) and ordering guarantees are the same on both.

The implementation is `BridgeEngine` in the `oam-bridge` module. Its game methods come from `GameExtension`, and it is
wired into `OamJni` in `oam-mlkit`.

## How it is checked

- **Unit tests.** `./gradlew :oam-bridge:test` ports open-apple-models' bridge test suites, using scripted models.
- **Conformance.** `AppleConformanceTest` runs the same scripted JSON-RPC scenarios through Apple's own `oam stdio`
  and through `BridgeEngine`, then compares the exchanges: responses, event sequences, `tool/call` and `tool/cancel`
  params, and `world/changed`. The differences described on this page are normalized before comparing. The last run
  matched 90 of 90 exchanges.

  ```sh
  OAM_APPLE_BRIDGE=/path/to/oam ./gradlew :oam-bridge:test --tests '*AppleConformanceTest*'
  ```
- **Live model.** `ProxyBridgeEvalTest` drives sessions, NPCs, decisions and content against a real small model
  through `oam serve` (`OAM_PROXY_EVAL=1`).
- **Real JNI.** `JniProbeTest` in `oam-mlkit` drives the bridge through a native host library (`OAM_JNI_PROBE_LIB`).

## 1. Transports

| Transport | How |
|---|---|
| JNI (native hosts such as Rust, C or C++ engines) | `OamJni.create(context, nativeHandle)` returns a bridge id. `OamJni.send(id, line)` takes one message per call and never blocks. Every outgoing message arrives through the host's `nativeDeliver(nativeHandle, line)`. `OamJni.destroy(id)` ends the bridge. |
| Kotlin, in process | `BridgeEngine(configuration) { line -> … }` with `receive(line)`, or `call(method, params, id)`. This mirrors the Swift `BridgeEngine`. |

There is no stdio server and no C ABI on Android. The JNI transport plays the role of the C ABI:

- **Framing.** Each `send` and each `nativeDeliver` carries exactly one JSON message, with no trailing newline.
- **Delivery.** `nativeDeliver` runs on a JVM background thread that is already attached. Deliveries are in order and
  never concurrent, so the callback is never re-entered.
- **Destroy.** Once `destroy` returns, `nativeDeliver` is never called again for that handle, so the handle can be
  freed. Destroying cancels the bridge's work the way `oam_bridge_destroy` does: turns in flight get no response.
- **Registering `nativeDeliver`.** The host either exports `Java_com_spacecorps_oam_jni_OamJni_nativeDeliver(JNIEnv*,
  jclass, jlong, jstring)` or registers it with `RegisterNatives`. Libraries loaded by `NativeActivity` or
  `GameActivity` are not searched for JNI symbols, so those hosts must use `RegisterNatives`.
- **Finding the class.** Look up `com.spacecorps.oam.jni.OamJni` through the activity's class loader. `FindClass` on
  a native thread only sees system classes.
- **Building the app.** The app must be built with Gradle, so that the ML Kit AAR and its manifest entries are merged,
  with the host's `.so` under `jniLibs`.
- **Text encoding.** JNI passes strings as *modified* UTF-8. In that encoding, characters outside the Basic
  Multilingual Plane, such as emoji, become two 3-byte surrogates that strict UTF-8 decoders reject.
  - **Outgoing messages:** the bridge writes such characters as JSON `\uXXXX\uXXXX` escapes. Every delivered line is
    therefore valid UTF-8, and the JSON value is unchanged.
  - **Incoming messages:** build the `jstring` with `NewString` (UTF-16) or the `jni` crate's `new_string`, or escape
    non-ASCII characters as `\u` sequences. Do not pass raw UTF-8 containing emoji to `NewStringUTF`.
- **Blocking calls.** There is no blocking call such as `oam_call_blocking`, so error `-32024 timeout` is never
  produced. Kotlin hosts can use `BridgeEngine.call` from a coroutine instead.
- **Choosing the engine.** `OamJni.engineFactory` defaults to `BridgeEngineFactory`. All bridges share one lazily
  created `GeminiNanoModel`. If the ML Kit client cannot be created, `"system"` requests fail with
  `model_unavailable` and scripted models keep working. Replace the factory in `Application.onCreate` to change the
  model options, limits or extensions.
- **Foreground only.** Gemini Nano runs only while the app is the top foreground app. In the background, turns fail
  with `-32005 rate_limited`.

## 2. `initialize` and `model/availability`

- `server.name` is `"open-android-models"`.
- The `model` object (also returned by `model/availability`) keeps Apple's five fields and adds a few:

  | Field | Android |
  |---|---|
  | `available`, `variant` | as on Apple. `variant` is the Gemini Nano model name when known |
  | `reason` | `device_not_eligible`, `model_not_ready` (downloadable or downloading), `aicore_unavailable`, `needs_system_update`, `not_enough_disk_space` or `unknown`. Apple's `apple_intelligence_not_enabled` does not occur |
  | `contextSize` | the **input** token budget per request, about 4000. Output has its own cap. On Apple, input and output share one 8192-token window |
  | `supportedLanguages` | always `[]`, because ML Kit cannot be asked |
  | `status` (new) | `available`, `downloadable`, `downloading` or `unavailable`, so a host can offer the download |
  | `detail`, `maxOutputTokens`, `bytesDownloaded`, `totalBytes` (new) | a readable reason, the output cap (up to 4096), and download progress |

- `capabilities` is the same as on Apple, including every method, the notifications, `clientRequests: ["tool/call"]`,
  `models: ["system", "scripted"]`, `maxSessions` and `batch: false`.

## 3. Turns and model steps

Gemini Nano has no native tool calling. Each tool decision is a model step of its own: the model answers with a JSON
step envelope, `{"action": "<tool>" | "respond", "arguments": {…}}`. See [DESIGN.md](DESIGN.md). What changes on the
wire:

- **More steps per turn.** Where Apple takes one inference to call a tool and one to answer, Android takes a *decide*
  step, then the tool round, then another decide step (or none once the budget is spent), then the reply step. Each
  step sends a `modelStep` event. Don't infer tool rounds by counting steps; use `toolCalls`.
- **Two extra fields on each step.** `result.steps[]` and `modelStep` events carry `kind` (`decide`,
  `toolArguments`, `respond` or `structured`) and `isRepair` (true when the step re-asks after invalid output).
  Everything else matches Apple. `toolCallingMode` is:
  - `allowed` for a decide step, which offers the tools and `respond`
  - `required` for a step that must call a tool
  - `disallowed` for the reply step
- **`"auto"` behaves like `"explicit"`.** Every decision is explicit on Android, so either way the model chooses
  between the tools and `respond`. `"required"` and `{"tool": name}` force a call on the first step only, as on Apple.
  With `{"tool": name}` the decision is skipped entirely: the model writes only the arguments, or does nothing at all
  if the tool takes none.
- **One tool call per step.** Apple can run several calls of one step in parallel. Android makes at most one call per
  decide step, so the `tool/call` requests of a multi-call turn arrive one at a time, each after the previous answer.
- **A repeated call ends the round.** An identical successful call repeated within a turn is treated as the model
  choosing to reply. Small models otherwise repeat lookups.
- **Timing.** These numbers come from `ProxyBridgeEvalTest`, with Apple's ~3B model standing in for Gemini Nano; nothing
  has been measured on a Nano device yet. A decide step takes about 0.75 s.

  | Request | Time |
  |---|---|
  | plain session turn | 1.2–2.3 s |
  | session turn with one client tool | 1.8–3.5 s, or up to 5.6 s when the decision needs a repair |
  | NPC turn with a grounding client tool | 2.0–4.4 s |
  | `npc/bark` | 0.6–1.4 s |
  | `decision/decide` | 1.3–2.1 s |
  | `content/generate` | 0.7–1.5 s |

## 4. Schemas, tool arguments and structured output

Apple constrains decoding to the generation schema. Gemini Nano has no constrained decoding for runtime schemas, so
the bridge describes the schema in the prompt, then coerces, validates and repairs once. What this means for hosts:

- **Undeclared keys are dropped.** Object schemas that declare `properties` but not `additionalProperties` are treated
  as `"additionalProperties": false`, so arguments and structured output never contain undeclared keys, as on Apple.
  An explicit `additionalProperties` is kept. Members of `allOf` stay open.
- **`tool/call` arguments always match the schema.** Arguments that are still invalid after the repair are recorded
  in `toolCalls` as an error output (`"The call was not made: …"`), and no `tool/call` is sent.
- **An unusable structured answer fails the turn with `-32012 generation_failed`.** This can't happen on Apple.
  - NPC fallback lines (`fallbackOnGuardrail`) and a decision's `fallbackOptionID` cover it, as well as guardrail
    violations and refusals.
  - In NPC `automatic` mode, it triggers the plain-text retry.
- **Enforced constraints.** `pattern`, `minLength`, `maxLength`, `exclusiveMinimum` and `exclusiveMaximum` are
  validated, with a repair, instead of being described and warned about. Warnings name the keywords that are ignored,
  such as `format`, `multipleOf` and `uniqueItems`.
- **`schema/validate` and `tools/validate`.** `generationSchema` is the JSON Schema as it is enforced (closed as
  described above). There is also an extra `rendered` field: the text the model is shown.
- **`data.schemaPath` is more precise.** It points at the offending keyword (`#/type`) rather than the schema node
  (`#`).

## 5. Sessions

- **Transcripts are platform-specific.** `session/transcript` returns
  `{"type": "open-android-models.Transcript", "version": "1.0", "transcript": {…}}`. Pass it back as `history`, as on
  Apple. Transcripts, and the `transcript` inside `npc/state`, cannot be moved between platforms. Instructions and
  tool definitions are restored from them as on Apple.
- **`entries` in `session/list`** counts prompts, responses, tool calls and tool outputs, as on Apple.
- **Options:**

  | Option | Android |
  |---|---|
  | `temperature` | ML Kit accepts 0–1; higher values are clamped |
  | `sampling` | `"greedy"` means top-K 1. `{"topK": n, "seed"?}` works as on Apple. `{"topP": p}` is accepted with a warning and ignored, apart from its `seed`, because ML Kit has no nucleus sampling |
  | `maxResponseTokens` | capped at 4096 |
  | `trimHistory`, `reservedResponseTokens` | Trimming fits the ~4000-token *input* budget. `reservedResponseTokens` is the safety margin below it (default 256; Apple's default is 1024) |
  | `maxAttempts` | Retries a single model step, never a whole turn. Default 3 (Apple: 2) |
  | `userLabel`, `assistantLabel` (Android only) | How the two sides are labelled in the conversation the model reads, default `User`/`Assistant`. For a character, use `Player` and its name. Apple warns about these as unknown keys |

- **`session/compact`** writes its summary with the session's own model.

## 6. Game methods

- **NPC `toolChoice` defaults to `"explicit"`.** That is also what open-apple-models' code does, although its
  PROTOCOL.md says `"auto"`. On Android the two behave the same.
- **Structured NPC replies** use the keys `emotion`, `line`, `player_options` and `ends_conversation`, as in
  open-apple-models' scripts. Close variants such as `playerOptions` are matched too. Keep personas short: the model
  has about 4000 input tokens for everything.
- **`decision/decideMany`** starts its decisions in request order, each after taking one of `maxConcurrency` slots.
- **Limits** are the same as on Apple: 64 sessions, 128 NPCs, 64 worlds and 256 subscriptions.

## 7. Errors

The error table is Apple's. ML Kit failures map onto it like this:

| ML Kit | Code |
|---|---|
| `BUSY`, per-app or per-device battery quota (27, 28), `BACKGROUND_USE_BLOCKED` (30) | `-32005 rate_limited`, with `data.retryAfter` and `data.retryAfterSeconds` when ML Kit gives a delay |
| safety filters (4, 11, 15) | `-32002 guardrail_violation` |
| `REQUEST_TOO_LARGE` | `-32004 context_size_exceeded` |
| not available or supported, no disk space, needs a system update, AICore incompatible | `-32001 model_unavailable` |
| output still invalid after a repair; anything else | `-32012 generation_failed` |

`-32024 timeout` is never produced (see section 1). Error *messages* may be worded differently from Apple's. Branch on
`code` and `data.code`.

## 8. Scripted models

Scripts are written exactly as for open-apple-models, and the same script plays the same way on both platforms.

- **Decide steps.** The scripted model answers the agent's decide steps from the script. A `toolCalls` step becomes
  one step envelope per call. An answer step (`text`, `json` or `template`) makes the decision `respond` and is then
  played as the reply.
- **Calls run one after another.** The calls of one `toolCalls` step run in sequence, not in parallel.
- **Call ids are generated.** A scripted `"id"` is ignored; call ids are always `call_…`.

## 9. Extending the protocol from Kotlin

The extension API mirrors the Swift one:

- `BridgeExtension`: `register(registry, engine)`, `notificationMethods` and `shutdown()`
- `BridgeMethodRegistry.register(method) { request -> … }`
- `BridgeReply.Result` or `BridgeReply.Deferred { … }`
- `BridgeRequest.drive(run, stream, context, eventMethod)` runs an agent turn with streaming and client tools
- `BridgeSession.schedule { … }` and `BridgeSession.commit()` give per-object ordering
- `BridgeCoding` and `GameCoding` hold the shared JSON shapes

Put the extension in `BridgeConfiguration(extensions = listOf(GameExtension(), QuestMethods()))`. For the JNI
transport, set it through `BridgeEngineFactory(configure = { model -> BridgeConfiguration(systemModel = model,
extensions = …) })`.
