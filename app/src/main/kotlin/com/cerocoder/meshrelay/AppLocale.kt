package com.cerocoder.meshrelay

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import com.cerocoder.meshrelay.settings.LanguageOption
import java.util.Locale

/**
 * The language chosen in Settings, applied to [this] context - for an activity's
 * or a service's `attachBaseContext`, which is the only place that can apply it
 * to the whole app.
 *
 * **Why not a composition local.** This used to be a `LocalizedApp` composable
 * providing an overridden `LocalContext` around the whole UI, and that reached
 * less of the app than it looked like it did. Every `Popup` and `Dialog` -
 * so every dropdown menu, every sort menu, every confirmation - is hosted in its
 * own `AbstractComposeView`, and each of those provides `LocalContext` afresh
 * from its own window's context, shadowing anything an ancestor provided. The
 * result on a real phone was an app in Spanish whose menus were all in English
 * (field issue F-5). Applying the locale at the base context puts it underneath
 * every window the app opens, so there is nothing left to shadow it.
 *
 * Returns [this] unchanged for [LanguageOption.SYSTEM], and whenever the
 * settings are not reachable - a context from a test or a tooling harness, or one
 * taken before `Application.onCreate`.
 */
internal fun Context.withChosenLanguage(): Context {
    val container = (applicationContext as? MeshRelayApp)?.containerOrNull ?: return this
    val locale = localeFor(container.settings.settings.value.language) ?: return this
    return withLocale(locale)
}

/**
 * The locale to force, or `null` for [LanguageOption.SYSTEM] - which is not a
 * locale but the absence of an override, and must stay distinguishable from one:
 * forcing the system's own locale would pin the app to whatever it was at
 * startup.
 *
 * `forLanguageTag` rather than the `Locale(String)` constructor, which is
 * deprecated from Java 19 on and would warn under the JDK 21 the build uses.
 */
internal fun localeFor(language: LanguageOption): Locale? = when (language) {
    LanguageOption.SYSTEM -> null
    LanguageOption.EN -> Locale.ENGLISH
    LanguageOption.ES -> Locale.forLanguageTag("es")
}

/**
 * This context with [locale] forced, for resource lookups and nothing else.
 *
 * The return type is [ContextWrapper] rather than [Context] deliberately, and it
 * is the type that carries the fix for the crash this function used to cause. See
 * [LocalizedContext]: a plain `createConfigurationContext` result would still
 * satisfy `Context`, so widening this signature back would compile silently.
 */
internal fun Context.withLocale(locale: Locale): ContextWrapper = LocalizedContext(this, locale)

/**
 * A context that resolves resources in [locale] while remaining a wrapper around
 * the context it was built from.
 *
 * **Why this is not `createConfigurationContext(configuration)`.** That call
 * returns a `ContextImpl`, which is not a `ContextWrapper` and therefore has no
 * `baseContext` chain leading back to whatever it was built from. Anything that
 * recovers an activity by unwrapping a context - and in an androidx app that is a
 * long list - hits a dead end at the first step. `androidx.activity.compose`'s
 * `findOwner` is the one that bit hardest: with the old context provided as
 * `LocalContext`, `rememberLauncherForActivityResult` threw
 * `IllegalStateException: No ActivityResultRegistryOwner was provided`, killing
 * the process on every launch once a language had been chosen and written to
 * settings - an install recoverable only by clearing app data (field issue F-2).
 * `startActivity` from `PositionLine`'s map links was the same root cause
 * surfacing earlier.
 *
 * That crash is out of reach now that this is applied in `attachBaseContext`
 * rather than provided into the composition, but the wrapper still earns its
 * place: an activity's base context is unwrapped for other reasons too, and a
 * `ContextImpl` under one is a trap for the next person as much as it was for
 * this one.
 *
 * Only resources are overridden. Everything else - `startActivity`,
 * `getSystemService`, `getBaseContext` - is [ContextWrapper]'s delegation,
 * unchanged.
 *
 * `Configuration.setLocale` also replaces the configuration's locale *list* with
 * a single-entry one, which is what makes `configuration.locales[0]` - the value
 * every card's own `displayLocale()` reads - the chosen language rather than the
 * system's first preference.
 *
 * The theme is rebuilt on the localized resources and copied from the base
 * theme, the way `ContextThemeWrapper` does it. Without that override
 * `getTheme()` would hand out a theme belonging to a different `Resources`
 * instance than `getResources()` returns. An `Activity` is itself a
 * `ContextThemeWrapper` and builds its own theme from its base context's
 * resources, so the manifest theme still lands on top of this one exactly as it
 * would without the wrapper.
 */
internal class LocalizedContext(base: Context, locale: Locale) : ContextWrapper(base) {

    private val localizedResources: Resources = run {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        base.createConfigurationContext(configuration).resources
    }

    private val localizedTheme: Resources.Theme by lazy {
        localizedResources.newTheme().apply { setTo(baseContext.theme) }
    }

    override fun getResources(): Resources = localizedResources

    override fun getAssets(): AssetManager = localizedResources.assets

    override fun getTheme(): Resources.Theme = localizedTheme
}
