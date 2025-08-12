package org.zxkill.nori.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BreakfastDining
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SpeakerPhone
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.zxkill.nori.R
import org.zxkill.nori.settings.datastore.Language
import org.zxkill.nori.settings.datastore.SpeechOutputDevice
import org.zxkill.nori.settings.datastore.Theme
import org.zxkill.nori.settings.ui.ListSetting


@Composable
fun languageSetting() = ListSetting(
    title = stringResource(R.string.pref_language),
    icon = Icons.Default.Language,
    description = stringResource(R.string.pref_language_summary),
    possibleValues = listOf(
        ListSetting.Value(Language.LANGUAGE_SYSTEM, stringResource(R.string.pref_language_system)),
        ListSetting.Value(Language.LANGUAGE_CS, "Čeština"),
        ListSetting.Value(Language.LANGUAGE_DE, "Deutsch"),
        ListSetting.Value(Language.LANGUAGE_EN, "English"),
        ListSetting.Value(Language.LANGUAGE_EN_IN, "English (India)"),
        ListSetting.Value(Language.LANGUAGE_ES, "Español"),
        ListSetting.Value(Language.LANGUAGE_EL, "Ελληνικά"),
        ListSetting.Value(Language.LANGUAGE_FR, "Français"),
        ListSetting.Value(Language.LANGUAGE_IT, "Italiano"),
        ListSetting.Value(Language.LANGUAGE_PL, "Polski"),
        ListSetting.Value(Language.LANGUAGE_RU, "Русский"),
        ListSetting.Value(Language.LANGUAGE_SL, "Slovenščina"),
        ListSetting.Value(Language.LANGUAGE_SV, "Svenska"),
        ListSetting.Value(Language.LANGUAGE_UK, "Українська"),
    ),
)

@Composable
fun themeSetting() = ListSetting(
    title = stringResource(R.string.pref_theme),
    icon = Icons.Default.ColorLens,
    description = stringResource(R.string.pref_theme_summary),
    possibleValues = listOf(
        ListSetting.Value(
            value = Theme.THEME_SYSTEM,
            name = stringResource(R.string.pref_theme_system),
            icon = Icons.Default.PhoneAndroid,
        ),
        ListSetting.Value(
            value = Theme.THEME_SYSTEM_DARK_BLACK,
            name = stringResource(R.string.pref_theme_system_dark_black),
            icon = Icons.Default.PhoneAndroid,
        ),
        ListSetting.Value(
            value = Theme.THEME_LIGHT,
            name = stringResource(R.string.pref_theme_light),
            icon = Icons.Default.LightMode,
        ),
        ListSetting.Value(
            value = Theme.THEME_DARK,
            name = stringResource(R.string.pref_theme_dark),
            icon = Icons.Default.Cloud,
        ),
        ListSetting.Value(
            value = Theme.THEME_BLACK,
            name = stringResource(R.string.pref_theme_black),
            icon = Icons.Default.DarkMode,
        ),
    ),
)

@Composable
fun speechOutputDevice() = ListSetting(
    title = stringResource(R.string.pref_speech_output_method),
    icon = Icons.Default.SpeakerPhone,
    description = stringResource(R.string.pref_speech_output_method_summary),
    possibleValues = listOf(
        ListSetting.Value(
            value = SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_ANDROID_TTS,
            name = stringResource(R.string.pref_speech_output_method_android),
            icon = Icons.Default.SpeakerPhone,
        ),
        ListSetting.Value(
            value = SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_TOAST,
            name = stringResource(R.string.pref_speech_output_method_toast),
            icon = Icons.Default.BreakfastDining,
        ),
        ListSetting.Value(
            value = SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_SNACKBAR,
            name = stringResource(R.string.pref_speech_output_method_snackbar),
            icon = Icons.Default.Minimize,
        ),
        ListSetting.Value(
            value = SpeechOutputDevice.SPEECH_OUTPUT_DEVICE_NOTHING,
            name = stringResource(R.string.pref_speech_output_method_nothing),
        ),
    ),
)
