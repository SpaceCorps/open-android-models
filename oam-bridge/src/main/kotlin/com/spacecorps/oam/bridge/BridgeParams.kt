package com.spacecorps.oam.bridge

import com.spacecorps.oam.EmptyJsonObject
import com.spacecorps.oam.arrayValue
import com.spacecorps.oam.boolValue
import com.spacecorps.oam.doubleValue
import com.spacecorps.oam.isJsonNull
import com.spacecorps.oam.longValue
import com.spacecorps.oam.stringValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Typed access to a request's by-name `params` object.
 *
 * Every accessor throws [BridgeError.invalidParams] with a message naming the
 * offending parameter (for example `'options.toolChoice'`), so handlers can
 * simply call them. An explicit `null` is treated like an absent parameter.
 *
 * @property obj The raw parameters.
 * @property path Prefix used in error messages (`options.` for nested objects).
 */
public class BridgeParams(public val obj: JsonObject, public val path: String = "") {
    /** The value for [key], or `null` when absent or `null`. */
    public operator fun get(key: String): JsonElement? = obj[key]?.takeUnless { it.isJsonNull }

    /** Whether [key] is present, even as `null` (a present key wins over saved defaults). */
    public fun hasKey(key: String): Boolean = key in obj

    /** Whether [key] is present and not `null`. */
    public operator fun contains(key: String): Boolean = get(key) != null

    /** The full parameter name for messages. */
    public fun name(key: String): String = path + key

    // MARK: Required

    /** The value of [key]. @throws BridgeError when it is missing. */
    public fun value(key: String): JsonElement = get(key) ?: throw missing(key)

    /** The string [key]. @throws BridgeError when it is missing or not a string. */
    public fun string(key: String): String = optionalString(key) ?: throw missing(key)

    /** The integer [key]. @throws BridgeError when it is missing or not an integer. */
    public fun int(key: String): Int = optionalInt(key) ?: throw missing(key)

    /** The object [key] as nested params. @throws BridgeError when it is missing or not an object. */
    public fun nested(key: String): BridgeParams = optionalNested(key) ?: throw missing(key)

    // MARK: Optional

    /** The string [key], or `null`. @throws BridgeError when it is not a string. */
    public fun optionalString(key: String): String? {
        val value = get(key) ?: return null
        return value.stringValue ?: throw mistyped(key, "a string", value)
    }

    /** The integer [key], or `null`. @throws BridgeError when it is not an integer or below [minimum]. */
    public fun optionalInt(key: String, minimum: Int? = null): Int? {
        val value = get(key) ?: return null
        val long = value.longValue ?: throw mistyped(key, "an integer", value)
        if (minimum != null && long < minimum) throw BridgeError.invalidParams("Parameter '${name(key)}' must be at least $minimum.")
        if (long > Int.MAX_VALUE || long < Int.MIN_VALUE) throw BridgeError.invalidParams("Parameter '${name(key)}' is out of range.")
        return long.toInt()
    }

    /**
     * A finite number, or `null`.
     *
     * @throws BridgeError when it is not a number, is not finite, or is outside [minimum]…[maximum].
     */
    public fun optionalDouble(key: String, minimum: Double? = null, maximum: Double? = null): Double? {
        val value = get(key) ?: return null
        val double = value.doubleValue ?: throw mistyped(key, "a number", value)
        if (!double.isFinite()) throw BridgeError.invalidParams("Parameter '${name(key)}' must be a finite number.")
        if (minimum != null && double < minimum) throw BridgeError.invalidParams("Parameter '${name(key)}' must be at least $minimum.")
        if (maximum != null && double > maximum) throw BridgeError.invalidParams("Parameter '${name(key)}' must be at most $maximum.")
        return double
    }

    /**
     * A time limit in seconds (`timeoutSeconds`, `toolTimeoutSeconds`): a
     * finite number from 0 (no limit) to [MAX_TIMEOUT_SECONDS]. Convert it with
     * [BridgeCoding.timeout].
     *
     * @throws BridgeError for anything else.
     */
    public fun optionalSeconds(key: String): Double? {
        val value = get(key) ?: return null
        val seconds = value.doubleValue
        if (seconds == null || !seconds.isFinite() || seconds < 0 || seconds > MAX_TIMEOUT_SECONDS) {
            throw BridgeError.invalidParams(
                "Parameter '${name(key)}' must be a number of seconds from 0 to ${MAX_TIMEOUT_SECONDS.toLong()} (0 means no limit); got ${JsonText.shown(value)}.",
            )
        }
        return seconds
    }

    /** The boolean [key], or `null`. @throws BridgeError when it is not a boolean. */
    public fun optionalBool(key: String): Boolean? {
        val value = get(key) ?: return null
        return value.boolValue ?: throw mistyped(key, "a boolean", value)
    }

    /** The array [key], or `null`. @throws BridgeError when it is not an array. */
    public fun optionalArray(key: String): JsonArray? {
        val value = get(key) ?: return null
        return value.arrayValue ?: throw mistyped(key, "an array", value)
    }

    /** The object [key], or `null`. @throws BridgeError when it is not an object. */
    public fun optionalObject(key: String): JsonObject? {
        val value = get(key) ?: return null
        return value as? JsonObject ?: throw mistyped(key, "an object", value)
    }

    /** The object [key] as nested params (messages name `key.member`), or `null`. */
    public fun optionalNested(key: String): BridgeParams? = optionalObject(key)?.let { BridgeParams(it, name(key) + ".") }

    /** The array of strings [key], or `null`. @throws BridgeError when it is not an array of strings. */
    public fun optionalStrings(key: String): List<String>? {
        val array = optionalArray(key) ?: return null
        return array.mapIndexed { index, element ->
            element.stringValue ?: throw mistyped("$key[$index]", "a string", element)
        }
    }

    /** Keys not in [allowed], as `Unknown parameter '…' was ignored.` warnings. */
    public fun unknownKeys(allowed: Set<String>): List<String> =
        obj.keys.filter { it !in allowed }.map { "Unknown parameter '${name(it)}' was ignored." }

    private fun missing(key: String) = BridgeError.invalidParams("Missing required parameter '${name(key)}'.")

    private fun mistyped(key: String, kind: String, value: JsonElement) =
        BridgeError.invalidParams("Parameter '${name(key)}' must be $kind; got ${JsonText.shown(value)}.")

    public companion object {
        /** The largest accepted `…TimeoutSeconds` value: one day. `0` means no limit. */
        public const val MAX_TIMEOUT_SECONDS: Double = 86_400.0

        /**
         * Wraps a `params` member. Absent or `null` params are an empty object;
         * by-position (array) params are rejected.
         *
         * @throws BridgeError when [value] is not an object.
         */
        public fun of(value: JsonElement?, path: String = ""): BridgeParams = when {
            value == null || value.isJsonNull -> BridgeParams(EmptyJsonObject, path)
            value is JsonObject -> BridgeParams(value, path)
            else -> throw BridgeError.invalidParams(if (path.isEmpty()) "params must be an object." else "'${path.dropLast(1)}' must be an object.")
        }
    }
}
