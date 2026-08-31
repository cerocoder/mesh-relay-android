package com.cerocoder.meshrelay.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.cerocoder.meshrelay.settings.LanguageOption
import java.util.Locale

/**
 * Applies the language chosen in Settings.
 *
 * A locale-overridden `Context` provided as [LocalContext], rather than
 * `AppCompatDelegate.setApplicationLocales`, which would mean adding `appcompat`
 * to a dependency chain that holds together only as a whole.
 * [LanguageOption.SYSTEM] passes the context through untouched.
 *
 * Both [LocalContext] and [LocalConfiguration] are provided, and the reason is
 * worth stating exactly, because the obvious one is wrong. `stringResource` does
 * not read [LocalContext]: in `compose-ui` 1.11.4 - the version this project's
 * BOM resolves to - every `stringResource` overload reads
 * `LocalResources.current`. `LocalResources` is declared with
 * `compositionLocalWithComputedDefaultOf`, its computation is
 * `LocalConfiguration.currentValue; LocalContext.currentValue.resources`, and the
 * platform never provides it explicitly - only [LocalContext] and
 * [LocalConfiguration] are provided at the root, in
 * `ComposeViewContext.android.kt`. So providing [LocalContext] here is what
 * redirects string lookups, and providing [LocalConfiguration] is what makes the
 * readers of those lookups invalidate when the language changes.
 *
 * [LocalConfiguration] earns its place a second time, independently of that: the
 * per-card `displayLocale()` helper repeated across this app's cards reads
 * `LocalConfiguration.current.locales` to pick the locale it formats numbers
 * with. Without it, a Spanish screen would show English-formatted decimals.
 *
 * On the deprecation the brief warned about: in `compose-ui` 1.11.4
 * [LocalConfiguration] carries no `@Deprecated` annotation - it is a plain
 * `compositionLocalOf<Configuration>` and is still the provided one of the pair.
 * `LocalResources` exists alongside it, but as a *computed* local over these two,
 * so it needs nothing from this function.
 */
@Composable
fun LocalizedApp(language: LanguageOption, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val localized = remember(language, base) {
        val locale = localeFor(language)
        if (locale == null) base else base.withLocale(locale)
    }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
    ) {
        content()
    }
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
 * `Configuration.setLocale` also replaces the configuration's locale *list* with
 * a single-entry one, which is what makes `configuration.locales[0]` - the value
 * every card's own `displayLocale()` reads - the chosen language rather than the
 * system's first preference.
 */
internal fun Context.withLocale(locale: Locale): Context {
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    return createConfigurationContext(configuration)
}
